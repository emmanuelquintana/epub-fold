package com.emmanuel.epubfold

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class LibraryActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var adapter: BookAdapter
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: CircularProgressIndicator

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importBook(it, open = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = ReaderPrefs(this)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.theme.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        library = Library(this)

        val root = findViewById<View>(R.id.root)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val fab = findViewById<ExtendedFloatingActionButton>(R.id.add)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            list.updatePadding(
                left = bars.left + dp(12), right = bars.right + dp(12),
                bottom = bars.bottom + dp(96)
            )
            (fab.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = bars.bottom + dp(24)
                rightMargin = bars.right + dp(24)
            }
            fab.requestLayout()
            insets
        }

        // Más columnas cuando el Fold está abierto
        val columns = max(2, resources.configuration.screenWidthDp / 150)
        list.layoutManager = GridLayoutManager(this, columns)
        adapter = BookAdapter(
            onOpen = { openBook(it) },
            onLongPress = { confirmDelete(it) },
        )
        list.adapter = adapter

        fab.setOnClickListener {
            picker.launch(arrayOf("application/epub+zip", "application/octet-stream", "*/*"))
        }

        handleIntent(intent)
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
        val books = library.all()
        adapter.submit(books)
        empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
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

    private fun confirmDelete(entry: BookEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.title)
            .setMessage("¿Quitar este libro de la biblioteca?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Quitar") { _, _ ->
                library.delete(entry.id)
                refresh()
            }
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class BookAdapter(
        val onOpen: (BookEntry) -> Unit,
        val onLongPress: (BookEntry) -> Unit,
    ) : RecyclerView.Adapter<BookAdapter.Holder>() {

        private var items: List<BookEntry> = emptyList()

        fun submit(list: List<BookEntry>) {
            items = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val coverBox: View = v.findViewById(R.id.coverBox)
            val cover: ImageView = v.findViewById(R.id.cover)
            val coverTitle: TextView = v.findViewById(R.id.coverTitle)
            val title: TextView = v.findViewById(R.id.title)
            val author: TextView = v.findViewById(R.id.author)
            val progress: LinearProgressIndicator = v.findViewById(R.id.bookProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = items[position]
            h.title.text = e.title
            h.author.text = e.author
            h.progress.progress = (e.progress * 1000).toInt()
            // Portada con proporción 2:3
            h.itemView.post {
                val w = h.coverBox.width
                if (w > 0 && h.coverBox.layoutParams.height != w * 3 / 2) {
                    h.coverBox.layoutParams.height = w * 3 / 2
                    h.coverBox.requestLayout()
                }
            }
            val bmp = e.coverPath?.let { if (File(it).exists()) BitmapFactory.decodeFile(it) else null }
            if (bmp != null) {
                h.cover.setImageBitmap(bmp)
                h.coverTitle.visibility = View.GONE
            } else {
                h.cover.setImageDrawable(null)
                h.coverTitle.text = e.title
                h.coverTitle.visibility = View.VISIBLE
            }
            h.itemView.setOnClickListener { onOpen(e) }
            h.itemView.setOnLongClickListener { onLongPress(e); true }
        }
    }
}
