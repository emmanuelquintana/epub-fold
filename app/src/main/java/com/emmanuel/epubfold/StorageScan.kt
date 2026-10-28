package com.emmanuel.epubfold

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.text.Normalizer

/** Un libro encontrado en el almacenamiento, todavía sin importar. */
class FoundBook(
    val uri: Uri,
    val fileName: String,
    /** Carpeta legible, p. ej. «Download/Libros» o «Tarjeta SD · Libros». */
    val folder: String,
    val size: Long,
    val modified: Long,
) {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val title = fileName.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    /** Identifica el archivo entre escaneos aunque cambie de nombre la biblioteca. */
    val key get() = "$fileName|$size"
    var inLibrary = false
    var checked = false
}

/**
 * Busca EPUB, PDF y cómics por todo el almacenamiento (memoria interna y tarjeta SD)
 * para que el usuario elija cuáles añadir. En Android 11+ necesita «Acceso a todos los
 * archivos»; si no se concede, se puede elegir una carpeta concreta.
 */
object StorageScan {

    private fun prefs(context: Context) = context.getSharedPreferences("storage_scan", Context.MODE_PRIVATE)

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Pantalla de Ajustes donde se concede el acceso a todos los archivos (Android 11+). */
    fun accessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /** Raíces de los volúmenes montados: memoria interna y tarjetas SD o USB. */
    private fun roots(context: Context): List<Pair<File, String>> =
        context.getExternalFilesDirs(null).filterNotNull().mapIndexedNotNull { i, dir ->
            val root = File(dir.absolutePath.substringBefore("/Android/data"))
            if (!root.canRead()) null
            else root to if (i == 0) "" else "Tarjeta SD"
        }.distinctBy { it.first.absolutePath }

    /**
     * Recorre el almacenamiento. Bloqueante. [onProgress] recibe las carpetas revisadas y
     * los libros encontrados; [cancelled] corta el recorrido si se cierra la hoja.
     */
    fun scan(context: Context, cancelled: () -> Boolean, onProgress: (dirs: Int, found: Int) -> Unit): List<FoundBook> {
        val out = ArrayList<FoundBook>()
        var dirs = 0
        roots(context).forEach { (root, volume) ->
            fun walk(d: File, rel: String, depth: Int) {
                if (depth > 12 || cancelled()) return
                dirs++
                if (dirs % 40 == 0) onProgress(dirs, out.size)
                val files = d.listFiles() ?: return
                for (f in files) {
                    val n = f.name
                    if (n.startsWith(".")) continue
                    if (f.isDirectory) {
                        // Datos privados de otras apps y caché del sistema: nunca hay libros del usuario
                        if (depth == 0 && n == "Android") continue
                        walk(f, if (rel.isEmpty()) n else "$rel/$n", depth + 1)
                    } else if (n.substringAfterLast('.', "").lowercase() in LibraryActivity.BOOK_EXT && f.length() > 1024) {
                        val folder = listOf(volume, rel).filter { it.isNotEmpty() }.joinToString(" · ")
                            .ifEmpty { "Almacenamiento interno" }
                        out += FoundBook(Uri.fromFile(f), n, folder, f.length(), f.lastModified())
                    }
                }
            }
            walk(root, "", 0)
        }
        onProgress(dirs, out.size)
        return out
    }

    /** Lo mismo que [scan] pero dentro de una carpeta elegida con el selector del sistema. */
    fun scanTree(context: Context, tree: Uri, cancelled: () -> Boolean): List<FoundBook> {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return emptyList()
        val out = ArrayList<FoundBook>()
        fun walk(d: DocumentFile, rel: String, depth: Int) {
            if (depth > 8 || cancelled()) return
            d.listFiles().forEach { f ->
                val n = f.name ?: return@forEach
                if (f.isDirectory) walk(f, "$rel/$n", depth + 1)
                else if (n.substringAfterLast('.', "").lowercase() in LibraryActivity.BOOK_EXT) {
                    out += FoundBook(f.uri, n, rel, f.length(), f.lastModified())
                }
            }
        }
        walk(root, root.name ?: "Carpeta", 0)
        return out
    }

    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]"), "")

    /** Señala los que ya se importaron antes o cuyo título coincide con uno de la biblioteca. */
    fun markKnown(context: Context, found: List<FoundBook>) {
        val imported = prefs(context).getStringSet("imported", emptySet()) ?: emptySet()
        val titles = Library(context).everything().map { fold(it.title) }.filter { it.length >= 6 }
        found.forEach { b ->
            val name = fold(b.title)
            b.inLibrary = b.key in imported || titles.any { name.contains(it) }
            b.checked = false
        }
    }

    fun markImported(context: Context, book: FoundBook) {
        val sp = prefs(context)
        val set = HashSet(sp.getStringSet("imported", emptySet()) ?: emptySet())
        set += book.key
        sp.edit().putStringSet("imported", set).apply()
    }
}

/**
 * Hoja para elegir qué libros encontrados se añaden. Se abre al empezar a buscar
 * (muestra el avance) y se rellena al terminar; nada se importa hasta pulsar «Añadir».
 */
class ScanPickerSheet(
    private val activity: Activity,
    title: String,
    private val onImport: (List<FoundBook>) -> Unit,
    private val onPickFolder: () -> Unit,
) {
    val dialog = BottomSheetDialog(activity)

    private var all: List<FoundBook> = emptyList()
    private var shown: List<FoundBook> = emptyList()
    private var filter = "all"
    private var query = ""

    private val status = TextView(activity).apply {
        setTextAppearance(R.style.Text_Footnote)
        setPadding(activity.dpi(20), activity.dpi(2), activity.dpi(20), activity.dpi(6))
    }
    private val progress = LinearProgressIndicator(activity).apply {
        isIndeterminate = true
        trackCornerRadius = activity.dpi(2)
        setIndicatorColor(activity.color(R.color.accent))
        trackColor = activity.color(R.color.fill)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(activity.dpi(20), activity.dpi(4), activity.dpi(20), activity.dpi(8)) }
    }
    private val search = android.widget.EditText(activity).apply {
        hint = "Filtrar por nombre o carpeta"
        setSingleLine()
        background = activity.getDrawable(R.drawable.bg_search_field)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        compoundDrawablePadding = activity.dpi(8)
        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(activity.color(R.color.label_secondary))
        setTextColor(activity.color(R.color.label))
        setHintTextColor(activity.color(R.color.label_secondary))
        setPadding(activity.dpi(10), activity.dpi(9), activity.dpi(10), activity.dpi(9))
        visibility = View.GONE
    }
    private val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private val chipScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(activity.dpi(16), activity.dpi(10), activity.dpi(16), activity.dpi(4))
        addView(chips)
        visibility = View.GONE
    }
    private val adapter = Adapter()
    private val toggleAll = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        setTextColor(activity.color(R.color.accent))
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setOnClickListener {
            val target = !shown.all { it.checked }
            shown.forEach { it.checked = target }
            adapter.notifyItemRangeChanged(0, shown.size, PAYLOAD_CHECK)
            updateCount()
        }
    }
    private val add = MaterialButton(activity).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        cornerRadius = activity.dpi(24)
        minHeight = activity.dpi(48)
        backgroundTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(activity.color(R.color.accent), activity.color(R.color.fill)),
        )
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xFFFFFFFF.toInt(), activity.color(R.color.label_secondary)),
        ))
        setPadding(activity.dpi(22), 0, activity.dpi(22), 0)
        setOnClickListener {
            val chosen = all.filter { it.checked }
            if (chosen.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            onImport(chosen)
        }
    }
    private val bar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(activity.color(R.color.sheet_bg))
        setPadding(activity.dpi(8), activity.dpi(8), activity.dpi(16), activity.dpi(12))
        addView(toggleAll)
        addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        addView(add)
        visibility = View.GONE
    }

    init {
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@ScanPickerSheet.adapter
            clipToPadding = false
            setPadding(0, 0, 0, activity.dpi(8))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dpi(6), 0, 0)
            addView(TextView(activity).apply {
                setTextAppearance(R.style.Text_Headline)
                text = title
                gravity = Gravity.CENTER
                setPadding(0, activity.dpi(6), 0, activity.dpi(10))
            })
            addView(LinearLayout(activity).apply {
                setPadding(activity.dpi(16), 0, activity.dpi(16), 0)
                addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            addView(chipScroll)
            addView(status)
            addView(progress)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hairline(activity))
            addView(bar)
        }
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            v.updatePadding(bottom = activity.dpi(12) + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
        })
        val h = (activity.resources.displayMetrics.heightPixels * 0.86f).toInt()
        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = activity.dpi(680)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setProgress(0, 0)
    }

    fun show() = dialog.show()

    fun setProgress(dirs: Int, found: Int) {
        status.text = if (dirs == 0) "Buscando EPUB, PDF y cómics…"
        else "Buscando… $dirs carpetas revisadas · $found libros"
    }

    /** Muestra el resultado. Todo llega sin marcar: el usuario elige qué añadir. */
    fun setResults(found: List<FoundBook>) {
        progress.visibility = View.GONE
        all = found.sortedWith(compareBy<FoundBook> { it.inLibrary }.thenBy { it.folder.lowercase() }.thenBy { it.title.lowercase() })
        if (all.isEmpty()) {
            status.text = "No se encontró ningún EPUB, PDF ni cómic."
            bar.visibility = View.VISIBLE
            toggleAll.text = "Elegir una carpeta…"
            toggleAll.setOnClickListener { dialog.dismiss(); onPickFolder() }
            add.visibility = View.GONE
            return
        }
        search.visibility = if (all.size > 8) View.VISIBLE else View.GONE
        val kinds = listOf(
            "all" to "Todos",
            "epub" to "EPUB",
            "pdf" to "PDF",
            "comic" to "Cómics",
        ).filter { (k, _) -> k == "all" || all.any { matchesKind(it, k) } }
        if (kinds.size > 2) {
            chipScroll.visibility = View.VISIBLE
            fun build() {
                chips.removeAllViews()
                kinds.forEach { (k, label) ->
                    val n = all.count { matchesKind(it, k) }
                    chips.addView(chip("$label  $n", k == filter) {
                        filter = k
                        build()
                        applyFilter()
                    })
                }
            }
            build()
        }
        bar.visibility = View.VISIBLE
        applyFilter()
    }

    fun setError(message: String) {
        progress.visibility = View.GONE
        status.text = message
    }

    private fun matchesKind(b: FoundBook, k: String) = when (k) {
        "all" -> true
        "comic" -> b.ext == "cbz" || b.ext == "cbr"
        else -> b.ext == k
    }

    private fun applyFilter() {
        shown = all.filter { matchesKind(it, filter) &&
            (query.isEmpty() || it.title.lowercase().contains(query) || it.folder.lowercase().contains(query)) }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        val n = all.count { it.checked }
        val known = all.count { it.inLibrary }
        status.text = buildString {
            append(if (all.size == 1) "1 libro encontrado" else "${all.size} libros encontrados")
            if (known > 0) append(" · $known ya en tu biblioteca")
            if (shown.isEmpty()) append("\nNada coincide con el filtro.")
        }
        add.isEnabled = n > 0
        add.text = when (n) {
            0 -> "Elige libros"
            1 -> "Añadir 1 libro"
            else -> "Añadir $n libros"
        }
        toggleAll.text = if (shown.isNotEmpty() && shown.all { it.checked }) "Quitar todos" else "Marcar todos"
        toggleAll.isEnabled = shown.isNotEmpty()
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = android.graphics.Typeface.create(if (on) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
        setTextColor(if (on) 0xFFFFFFFF.toInt() else activity.color(R.color.label))
        background = roundRect(if (on) activity.color(R.color.accent) else activity.color(R.color.seg_bg), activity.dp(16f))
        setPadding(activity.dpi(14), activity.dpi(7), activity.dpi(14), activity.dpi(7))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = activity.dpi(8) }
        setOnClickListener { onClick() }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val row: LinearLayout, val check: MaterialCheckBox, val title: TextView, val caption: TextView) :
            RecyclerView.ViewHolder(row)

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val check = MaterialCheckBox(ctx).apply {
                isClickable = false
                isFocusable = false
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ctx.color(R.color.accent), ctx.color(R.color.label_secondary)),
                )
            }
            val title = TextView(ctx).apply {
                setTextAppearance(R.style.Text_Body)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val caption = TextView(ctx).apply {
                setTextAppearance(R.style.Text_Footnote)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(caption)
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dpi(60)
                setPadding(ctx.dpi(8), ctx.dpi(8), ctx.dpi(20), ctx.dpi(8))
                val tv = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(check)
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dpi(4) })
            }
            return Holder(row, check, title, caption)
        }

        override fun onBindViewHolder(h: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_CHECK)) h.check.isChecked = shown[position].checked
            else onBindViewHolder(h, position)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val b = shown[position]
            val ctx = h.row.context
            h.title.text = b.title
            h.title.alpha = if (b.inLibrary) 0.6f else 1f
            val parts = arrayListOf(b.ext.uppercase(), Formatter.formatShortFileSize(ctx, b.size), b.folder)
            if (b.inLibrary) parts.add(0, "Ya en tu biblioteca")
            h.caption.text = parts.joinToString(" · ")
            h.check.isChecked = b.checked
            h.row.contentDescription = "${b.title}, ${parts.joinToString(", ")}"
            h.row.setOnClickListener {
                b.checked = !b.checked
                h.check.isChecked = b.checked
                updateCount()
            }
        }
    }

    private companion object { const val PAYLOAD_CHECK = "check" }
}
