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
            if (prefs.theme.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
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
        refresh()
    }

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

    private fun refresh() {
        val all = library.all()
        val sorted = when (prefs.librarySort) {
            LibrarySort.RECENT -> all
            LibrarySort.TITLE -> all.sortedBy { it.title.lowercase() }
            LibrarySort.AUTHOR -> all.sortedBy { it.author.lowercase() }
        }
        val hero = all.firstOrNull { it.progress > 0f && it.progress < 0.999f }
        adapter.submit(sorted, hero)
    }

    private fun importBook(uri: Uri, open: Boolean) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { library.import(uri) } }
            progress.visibility = View.GONE
            result.onSuccess {
                refresh()
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
            menu.add(0, 2, 1, "Marcar como no leído")
            menu.add(0, 3, 2, "Quitar de la biblioteca")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> openBook(entry)
                    2 -> { library.resetProgress(entry.id); refresh() }
                    3 -> { library.delete(entry.id); refresh() }
                }
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
        private var hero: BookEntry? = null

        fun submit(list: List<BookEntry>, heroBook: BookEntry?) {
            items = list
            hero = heroBook
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
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

        init {
            sortBox.addView(sort)
            sort.setOptions(LibrarySort.entries.map { it.label }, prefs.librarySort.ordinal)
            sort.onSelected = { i ->
                prefs.librarySort = LibrarySort.entries[i]
                refresh()
            }
            v.findViewById<View>(R.id.emptyAdd).setOnClickListener { pickBook() }
        }

        fun bind(books: List<BookEntry>, hero: BookEntry?) {
            count.text = when (books.size) {
                0 -> ""
                1 -> "1 libro"
                else -> "${books.size} libros"
            }
            count.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
            section.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
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
