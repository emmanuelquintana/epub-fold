package com.emmanuel.epubfold

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class LibraryActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var prefs: ReaderPrefs
    private lateinit var adapter: LibraryAdapter
    private lateinit var list: RecyclerView
    private lateinit var progress: CircularProgressIndicator

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importBook(it, open = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = ReaderPrefs(this)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.isDarkTheme(prefs.effectiveTheme())) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_library)
        library = Library(this)

        val root = findViewById<View>(R.id.root)
        val topBar = findViewById<View>(R.id.topBar)
        val smallTitle = findViewById<View>(R.id.smallTitle)
        val topDivider = findViewById<View>(R.id.topDivider)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)

        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            list.updatePadding(
                top = bars.top + dpi(52),
                left = bars.left + dpi(14), right = bars.right + dpi(14),
                bottom = bars.bottom + dpi(24)
            )
            insets
        }

        // Más columnas cuando el Fold está abierto
        val columns = max(3, resources.configuration.screenWidthDp / 132)
        val glm = GridLayoutManager(this, columns)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position == 0) columns else 1
        }
        list.layoutManager = glm
        adapter = LibraryAdapter()
        list.adapter = adapter

        // Título grande que se convierte en título compacto al desplazar (iOS)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val off = rv.computeVerticalScrollOffset().toFloat()
                val f = min(1f, max(0f, (off - dp(20f)) / dp(24f)))
                smallTitle.alpha = f
                topDivider.alpha = f
                topBar.setBackgroundColor(if (f > 0f) withAlpha(color(R.color.bg_grouped), (0xF0 * f).toInt()) else 0)
            }
        })

        findViewById<View>(R.id.add).setOnClickListener { pickBook() }
        findViewById<View>(R.id.stats).setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        findViewById<View>(R.id.account).setOnClickListener { startActivity(Intent(this, AccountActivity::class.java)) }
        handleIntent(intent)
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private fun pickBook() {
        picker.launch(arrayOf("application/epub+zip", "application/octet-stream", "*/*"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Tema automático: la biblioteca sigue el modo claro/oscuro del tema de lectura
        val wantDark = prefs.isDarkTheme(prefs.effectiveTheme())
        AppCompatDelegate.setDefaultNightMode(
            if (wantDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        refresh()
        autoSync()
    }

    /** Sincroniza en segundo plano como mucho cada 10 minutos si hay sesión. */
    private fun autoSync() {
        if (CloudSync.user(this) == null) return
        if (System.currentTimeMillis() - prefs.lastSync < 10 * 60_000L) return
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { CloudSync.syncAll(this@LibraryActivity) } }
            if (r.getOrNull()?.pulled ?: 0 > 0) refresh()
        }
    }

    private var query = ""

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) {
            intent.action = null
            importBook(uri, open = true)
        }
    }

    private val filters = listOf("Todos", "Leyendo", "Quiero leer", "Terminados")

    private fun refresh() {
        val all = library.all()
        val sorted = when (prefs.librarySort) {
            LibrarySort.RECENT -> all
            LibrarySort.TITLE -> all.sortedBy { it.title.lowercase() }
            LibrarySort.AUTHOR -> all.sortedBy { it.author.lowercase() }
        }
        val q = query.trim().lowercase()
        val filtered = sorted.filter { b ->
            val shelfOk = when (prefs.libraryFilter) {
                1 -> b.collection == Shelf.READING
                2 -> b.collection == Shelf.WANT
                3 -> b.collection == Shelf.FINISHED
                else -> true
            }
            shelfOk && (q.isEmpty() || b.title.lowercase().contains(q) || b.author.lowercase().contains(q))
        }
        val hero = if (q.isEmpty() && prefs.libraryFilter == 0)
            all.firstOrNull { it.collection == Shelf.READING } else null
        adapter.submit(filtered, hero, all.size)
    }

    private fun importBook(uri: Uri, open: Boolean) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { library.import(uri) } }
            progress.visibility = View.GONE
            result.onSuccess {
                refresh()
                CloudSync.pullOne(this@LibraryActivity, it.id) { refresh() }
                if (open) openBook(it)
            }.onFailure {
                Toast.makeText(this@LibraryActivity, "No se pudo abrir el libro: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openBook(entry: BookEntry) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, entry.id))
    }

    private fun showBookMenu(anchor: View, entry: BookEntry) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Leer")
            if (entry.shelf == "want") menu.add(0, 4, 1, "Quitar de «Quiero leer»")
            else if (entry.collection != Shelf.FINISHED) menu.add(0, 5, 1, "Añadir a «Quiero leer»")
            if (entry.collection != Shelf.FINISHED) menu.add(0, 6, 2, "Marcar como terminado")
            menu.add(0, 2, 3, "Marcar como no leído")
            menu.add(0, 3, 4, "Quitar de la biblioteca")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> openBook(entry)
                    2 -> { library.resetProgress(entry.id); refresh() }
                    3 -> {
                        androidx.appcompat.app.AlertDialog.Builder(this@LibraryActivity)
                            .setTitle("¿Quitar «${entry.title}»?")
                            .setMessage("Se borrarán el libro, sus marcadores y notas de este dispositivo.")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Quitar") { _, _ -> library.delete(entry.id); refresh() }
                            .show()
                    }
                    4 -> { library.setShelf(entry.id, ""); refresh() }
                    5 -> { library.setShelf(entry.id, "want"); refresh() }
                    6 -> { library.markFinished(entry.id); refresh() }
                }
                CloudSync.schedulePush(this@LibraryActivity, entry.id)
                true
            }
            show()
        }
    }

    // ------------------------------------------------------------------ portadas

    private val coverCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun bindCover(path: String?, title: String, image: ImageView, fallback: TextView) {
        val bmp = path?.let { p ->
            coverCache.get(p) ?: (if (File(p).exists()) BitmapFactory.decodeFile(p) else null)?.also { coverCache.put(p, it) }
        }
        if (bmp != null) {
            image.setImageBitmap(bmp)
            fallback.visibility = View.GONE
        } else {
            image.setImageDrawable(null)
            fallback.text = title
            fallback.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ adaptador

    private inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<BookEntry> = emptyList()
        var hero: BookEntry? = null
        var total = 0
        private var first = true

        fun submit(list: List<BookEntry>, heroBook: BookEntry?, totalBooks: Int) {
            val old = items.size
            items = list
            hero = heroBook
            total = totalBooks
            if (first) {
                first = false
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
                return
            }
            // La cabecera se actualiza con payload para no perder el foco del buscador
            notifyItemChanged(0, "header")
            val n = list.size
            if (n > old) {
                notifyItemRangeChanged(1, old)
                notifyItemRangeInserted(1 + old, n - old)
            } else {
                notifyItemRangeChanged(1, n)
                if (old > n) notifyItemRangeRemoved(1 + n, old - n)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (holder is HeaderHolder) holder.bind(items, hero) else super.onBindViewHolder(holder, position, payloads)
        }

        override fun getItemCount() = items.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderHolder(inf.inflate(R.layout.item_library_header, parent, false))
            else BookHolder(inf.inflate(R.layout.item_book, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> holder.bind(items, hero)
                is BookHolder -> holder.bind(items[position - 1])
            }
        }
    }

    private inner class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val count: TextView = v.findViewById(R.id.count)
        val heroBox: View = v.findViewById(R.id.hero)
        val heroCover: ImageView = v.findViewById(R.id.heroCover)
        val heroCoverTitle: TextView = v.findViewById(R.id.heroCoverTitle)
        val heroTitle: TextView = v.findViewById(R.id.heroTitle)
        val heroAuthor: TextView = v.findViewById(R.id.heroAuthor)
        val heroProgress: LinearProgressIndicator = v.findViewById(R.id.heroProgress)
        val heroPercent: TextView = v.findViewById(R.id.heroPercent)
        val heroContinue: View = v.findViewById(R.id.heroContinue)
        val empty: View = v.findViewById(R.id.empty)
        val section: View = v.findViewById(R.id.section)
        val sortBox: FrameLayout = v.findViewById(R.id.sortBox)
        val sort = SegmentedControl(v.context)
        val search: android.widget.EditText = v.findViewById(R.id.search)
        val filterBox: FrameLayout = v.findViewById(R.id.filterBox)
        val filter = SegmentedControl(v.context)
        val sectionTitle: TextView = v.findViewById(R.id.sectionTitle)

        init {
            filterBox.addView(filter)
            filter.setOptions(filters, prefs.libraryFilter)
            filter.onSelected = { i ->
                prefs.libraryFilter = i
                refresh()
            }
            search.setText(query)
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString() ?: ""
                    if (q != query) {
                        query = q
                        refresh()
                    }
                }
            })
            sortBox.addView(sort)
            sort.setOptions(LibrarySort.entries.map { it.label }, prefs.librarySort.ordinal)
            sort.onSelected = { i ->
                prefs.librarySort = LibrarySort.entries[i]
                refresh()
            }
            v.findViewById<View>(R.id.emptyAdd).setOnClickListener { pickBook() }
        }

        fun bind(books: List<BookEntry>, hero: BookEntry?) {
            val total = adapter.total
            count.text = when (total) {
                0 -> ""
                1 -> "1 libro"
                else -> "$total libros"
            }
            val libEmpty = total == 0
            count.visibility = if (libEmpty) View.GONE else View.VISIBLE
            empty.visibility = if (libEmpty) View.VISIBLE else View.GONE
            search.visibility = if (libEmpty) View.GONE else View.VISIBLE
            filterBox.visibility = if (libEmpty) View.GONE else View.VISIBLE
            section.visibility = if (libEmpty) View.GONE else View.VISIBLE
            sectionTitle.text = when {
                query.isNotBlank() -> if (books.isEmpty()) "Sin resultados" else "Resultados (${books.size})"
                prefs.libraryFilter == 0 -> "Todos los libros"
                books.isEmpty() -> "${filters[prefs.libraryFilter]}: ninguno"
                else -> "${filters[prefs.libraryFilter]} (${books.size})"
            }
            if (hero == null) {
                heroBox.visibility = View.GONE
            } else {
                heroBox.visibility = View.VISIBLE
                bindCover(hero.coverPath, hero.title, heroCover, heroCoverTitle)
                heroTitle.text = hero.title
                heroAuthor.text = hero.author
                heroProgress.progress = (hero.progress * 1000).toInt()
                heroPercent.text = "${(hero.progress * 100).toInt()} % leído"
                heroBox.setOnClickListener { openBook(hero) }
                heroContinue.setOnClickListener { openBook(hero) }
            }
        }
    }

    private inner class BookHolder(v: View) : RecyclerView.ViewHolder(v) {
        val coverCard: View = v.findViewById(R.id.coverCard)
        val cover: ImageView = v.findViewById(R.id.cover)
        val coverTitle: TextView = v.findViewById(R.id.coverTitle)
        val title: TextView = v.findViewById(R.id.title)
        val status: TextView = v.findViewById(R.id.status)

        fun bind(e: BookEntry) {
            title.text = e.title
            val pct = (e.progress * 100).toInt()
            when {
                e.progress <= 0f -> {
                    status.text = "NUEVO"
                    status.setTextColor(color(R.color.accent))
                }
                e.progress >= 0.999f -> {
                    status.text = "Terminado"
                    status.setTextColor(color(R.color.label_secondary))
                }
                else -> {
                    status.text = "$pct %"
                    status.setTextColor(color(R.color.label_secondary))
                }
            }
            // Portada con proporción 2:3
            itemView.post {
                val w = coverCard.width
                if (w > 0 && coverCard.layoutParams.height != w * 3 / 2) {
                    coverCard.layoutParams.height = w * 3 / 2
                    coverCard.requestLayout()
                }
            }
            bindCover(e.coverPath, e.title, cover, coverTitle)
            coverCard.setOnClickListener { openBook(e) }
            coverCard.setOnLongClickListener { showBookMenu(it, e); true }
        }
    }
}
