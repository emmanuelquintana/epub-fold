package com.emmanuel.epubfold

import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Catálogos OPDS (Project Gutenberg, Feedbooks, tu servidor de Calibre…):
 * navegar, buscar y descargar libros directamente a la biblioteca.
 */
class OpdsActivity : AppCompatActivity() {

    data class Catalog(val name: String, val url: String, val user: String = "", val pass: String = "", val custom: Boolean = false)

    data class Link(val href: String, val type: String, val rel: String, val title: String)

    data class Entry(
        val title: String,
        val author: String,
        val summary: String,
        val navigation: Link?,
        val acquisitions: List<Link>,
    )

    data class Feed(
        val title: String,
        val entries: List<Entry>,
        val next: String?,
        val searchTemplate: String?,
    )

    private val presets = listOf(
        Catalog("Project Gutenberg", "https://www.gutenberg.org/ebooks.opds/"),
        Catalog("Feedbooks · dominio público", "https://catalog.feedbooks.com/catalog/public_domain.atom"),
    )

    private lateinit var title: TextView
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val adapter = RowAdapter()

    /** Pila de páginas visitadas para «atrás». */
    private val history = ArrayList<Pair<Catalog, String>>()
    private var catalog: Catalog? = null
    private var feed: Feed? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_grouped))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(8), dpi(16), dpi(4))
        }
        bar.addView(TextView(this).apply {
            text = "‹ Atrás"
            setTextColor(color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dpi(8), dpi(16), dpi(8))
            setOnClickListener { goBack() }
        })
        progress = ProgressBar(this).apply { visibility = View.GONE }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(progress, LinearLayout.LayoutParams(dpi(24), dpi(24)))
        col.addView(bar)
        title = TextView(this).apply {
            setTextAppearance(R.style.Text_LargeTitle)
            setPadding(dpi(20), 0, dpi(20), dpi(8))
            maxLines = 2
        }
        col.addView(title)
        search = EditText(this).apply {
            hint = "Buscar en el catálogo"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = getDrawable(R.drawable.bg_search_field)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dpi(8)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color(R.color.label_secondary))
            setTextColor(color(R.color.label))
            setHintTextColor(color(R.color.label_secondary))
            setPadding(dpi(10), dpi(9), dpi(10), dpi(9))
            visibility = View.GONE
            setOnEditorActionListener { v, _, _ ->
                runSearch(v.text.toString())
                true
            }
        }
        col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dpi(16), 0, dpi(16), dpi(8))
        })
        status = TextView(this).apply {
            setTextAppearance(R.style.Text_Footnote)
            setPadding(dpi(20), 0, dpi(20), dpi(6))
            visibility = View.GONE
        }
        col.addView(status)
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@OpdsActivity)
            adapter = this@OpdsActivity.adapter
            clipToPadding = false
            setBackgroundColor(color(R.color.group_bg))
        }
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
        ViewCompat.setOnApplyWindowInsetsListener(col) { _, i ->
            val b = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            col.updatePadding(top = b.top, left = b.left, right = b.right)
            list.updatePadding(bottom = b.bottom + dpi(16))
            i
        }
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        showCatalogs()
    }

    // ---------------------------------------------------------------- catálogos guardados

    private fun customCatalogs(): List<Catalog> {
        val arr = runCatching { JSONArray(getSharedPreferences("opds", MODE_PRIVATE).getString("catalogs", "[]")) }
            .getOrDefault(JSONArray())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Catalog(o.optString("name"), o.optString("url"), o.optString("user"), o.optString("pass"), custom = true)
        }
    }

    private fun saveCustom(list: List<Catalog>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url).put("user", it.user).put("pass", it.pass)) }
        getSharedPreferences("opds", MODE_PRIVATE).edit().putString("catalogs", arr.toString()).apply()
    }

    private fun showCatalogs() {
        loadJob?.cancel()
        progress.visibility = View.GONE
        catalog = null
        feed = null
        history.clear()
        title.text = "Catálogos"
        search.visibility = View.GONE
        setStatus("Libros gratuitos y tu propio servidor (Calibre, Kavita, Komga…). Mantén pulsado uno tuyo para borrarlo.")
        val rows = ArrayList<SheetRow>()
        (presets + customCatalogs()).forEach { c ->
            rows += SheetRow(c.name, caption = c.url, onLongClick = if (c.custom) ({ confirmDelete(c) }) else null) {
                open(c, c.url, push = false)
            }
        }
        rows += SheetRow("Añadir catálogo…", caption = "Dirección OPDS, p. ej. http://192.168.1.10:8080/opds", highlighted = true) {
            addCatalog()
        }
        adapter.rows = rows
    }

    private fun confirmDelete(c: Catalog) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Quitar «${c.name}»?")
            .setMessage("Sólo se quita de la lista; no se borra ningún libro.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Quitar") { _, _ ->
                saveCustom(customCatalogs().filter { it.url != c.url || it.name != c.name })
                showCatalogs()
            }
            .show()
    }

    private fun addCatalog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), 0)
        }
        fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            this.hint = hint
            inputType = type
            setSingleLine()
            box.addView(this)
        }
        val name = field("Nombre (p. ej. Mi Calibre)")
        val url = field("Dirección OPDS", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field("Usuario (opcional)")
        val pass = field("Contraseña (opcional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        MaterialAlertDialogBuilder(this)
            .setTitle("Añadir catálogo OPDS")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Añadir") { _, _ ->
                var u = url.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
                val c = Catalog(name.text.toString().trim().ifEmpty { URL(u).host }, u, user.text.toString().trim(), pass.text.toString(), true)
                saveCustom(customCatalogs() + c)
                showCatalogs()
            }
            .show()
    }

    // ---------------------------------------------------------------- navegación

    private fun setStatus(msg: String?) {
        status.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
        status.text = msg
    }

    private fun goBack() {
        when {
            history.size > 1 -> {
                history.removeAt(history.size - 1)
                val (c, u) = history.last()
                open(c, u, push = false, keepHistory = true)
            }
            catalog != null -> showCatalogs()
            else -> finish()
        }
    }

    private fun open(c: Catalog, url: String, push: Boolean = true, keepHistory: Boolean = false) {
        if (!push && !keepHistory) history.clear()
        if (!keepHistory) history += c to url
        catalog = c
        loadJob?.cancel()
        progress.visibility = View.VISIBLE
        setStatus("Cargando…")
        adapter.rows = emptyList()
        loadJob = lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { parse(fetch(c, url), url) } }
            progress.visibility = View.GONE
            r.onSuccess { show(c, it) }
                .onFailure { setStatus("No se pudo cargar el catálogo: ${it.message}") }
        }
    }

    private fun show(c: Catalog, f: Feed) {
        feed = f
        title.text = f.title.ifEmpty { c.name }
        search.visibility = if (f.searchTemplate != null) View.VISIBLE else View.GONE
        setStatus(if (f.entries.isEmpty()) "No hay nada aquí." else null)
        val rows = ArrayList<SheetRow>()
        f.entries.forEach { e ->
            if (e.acquisitions.isNotEmpty()) {
                val fmt = e.acquisitions.joinToString(" · ") { formatLabel(it.type) }
                rows += SheetRow(e.title, caption = listOf(e.author, fmt).filter { it.isNotEmpty() }.joinToString(" — "),
                    trailing = "↓") { confirmDownload(c, e) }
            } else if (e.navigation != null) {
                rows += SheetRow(e.title, caption = e.summary.take(140).ifEmpty { null }, trailing = "›") {
                    open(c, e.navigation.href)
                }
            }
        }
        f.next?.let { next -> rows += SheetRow("Más resultados…", highlighted = true) { open(c, next) } }
        adapter.rows = rows
    }

    private fun runSearch(q: String) {
        val c = catalog ?: return
        val template = feed?.searchTemplate ?: return
        if (q.isBlank()) return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        loadJob?.cancel()
        progress.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val tpl = if (template.startsWith("opensearch:")) resolveOpenSearch(c, template.removePrefix("opensearch:")) else template
                    val url = tpl.replace("{searchTerms}", URLEncoder.encode(q.trim(), "UTF-8"))
                        .replace(Regex("\\{[^}]*\\?}"), "")
                    url
                }
            }
            progress.visibility = View.GONE
            r.onSuccess { open(c, it) }.onFailure { setStatus("Este catálogo no permite buscar: ${it.message}") }
        }
    }

    private fun formatLabel(type: String) = when {
        type.contains("epub") -> "EPUB"
        type.contains("pdf") -> "PDF"
        type.contains("cbz") || type.contains("comicbook+zip") -> "CBZ"
        type.contains("cbr") || type.contains("rar") -> "CBR"
        else -> type.substringAfter('/')
    }

    private fun confirmDownload(c: Catalog, e: Entry) {
        // Preferimos EPUB; luego cómics y PDF
        val best = e.acquisitions.sortedBy {
            when {
                it.type.contains("epub") && !it.href.contains("noimages") -> 0
                it.type.contains("epub") -> 1
                it.type.contains("comicbook") || it.type.contains("cbz") -> 2
                it.type.contains("pdf") -> 3
                else -> 4
            }
        }.first()
        MaterialAlertDialogBuilder(this)
            .setTitle(e.title)
            .setMessage(listOf(e.author, e.summary.take(600)).filter { it.isNotBlank() }.joinToString("\n\n")
                .ifEmpty { "Formato: ${formatLabel(best.type)}" })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Descargar (${formatLabel(best.type)})") { _, _ -> download(c, e, best) }
            .show()
    }

    private fun download(c: Catalog, e: Entry, link: Link) {
        progress.visibility = View.VISIBLE
        Toast.makeText(this, "Descargando «${e.title}»…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val conn = connect(c, link.href)
                    try {
                        val ext = when {
                            link.type.contains("pdf") -> "pdf"
                            link.type.contains("rar") || link.type.contains("cbr") -> "cbr"
                            link.type.contains("comicbook") || link.type.contains("cbz") -> "cbz"
                            else -> "epub"
                        }
                        conn.inputStream.use { Library(this@OpdsActivity).importStream(it, "${e.title}.$ext") }
                    } finally {
                        conn.disconnect()
                    }
                }
            }
            progress.visibility = View.GONE
            r.onSuccess { entry ->
                MaterialAlertDialogBuilder(this@OpdsActivity)
                    .setTitle("Añadido a tu biblioteca")
                    .setMessage(entry.title)
                    .setNegativeButton("Seguir buscando", null)
                    .setPositiveButton("Leer ahora") { _, _ ->
                        startActivity(LibraryActivity.readerIntent(this@OpdsActivity, entry))
                    }
                    .show()
            }.onFailure {
                Toast.makeText(this@OpdsActivity, "No se pudo descargar: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- red y OPDS

    /** Abre la conexión siguiendo redirecciones (también de http a https) con usuario y contraseña si hay. */
    private fun connect(c: Catalog, url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "EpubFold/${BuildConfig.VERSION_NAME} (Android)")
            conn.setRequestProperty("Accept", "application/atom+xml, application/xml, */*")
            if (c.user.isNotEmpty()) {
                val token = Base64.encodeToString("${c.user}:${c.pass}".toByteArray(), Base64.NO_WRAP)
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirección sin destino")
                conn.disconnect()
                u = URL(u, loc)
                return@repeat
            }
            if (code == 401) throw IllegalStateException("Usuario o contraseña incorrectos")
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn
        }
        throw IllegalStateException("Demasiadas redirecciones")
    }

    private fun fetch(c: Catalog, url: String): String {
        val conn = connect(c, url)
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveOpenSearch(c: Catalog, descriptionUrl: String): String {
        val xml = fetch(c, descriptionUrl)
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        var fallback: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name.substringAfter(':') == "Url") {
                val t = p.getAttributeValue(null, "template")
                val type = p.getAttributeValue(null, "type") ?: ""
                if (t != null) {
                    val abs = URL(URL(descriptionUrl), t).toString().replace("%7B", "{").replace("%7D", "}")
                    if (type.contains("atom")) return abs
                    if (fallback == null) fallback = abs
                }
            }
            ev = p.next()
        }
        return fallback ?: throw IllegalStateException("sin plantilla de búsqueda")
    }

    private fun parse(xml: String, base: String): Feed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        var feedTitle = ""
        var next: String? = null
        var search: String? = null
        val entries = ArrayList<Entry>()

        var inEntry = false
        var inAuthor = false
        var text: String? = null
        var eTitle = ""
        var eAuthor = ""
        var eSummary = ""
        var links = ArrayList<Link>()

        fun abs(href: String) = runCatching { URL(URL(base), href).toString() }.getOrDefault(href)

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.substringAfter(':')
                    text = null
                    when (name) {
                        "entry" -> {
                            inEntry = true
                            eTitle = ""; eAuthor = ""; eSummary = ""; links = ArrayList()
                        }
                        "author" -> inAuthor = true
                        "link" -> {
                            val href = p.getAttributeValue(null, "href")
                            if (href != null) {
                                val l = Link(abs(href), p.getAttributeValue(null, "type") ?: "",
                                    p.getAttributeValue(null, "rel") ?: "", p.getAttributeValue(null, "title") ?: "")
                                if (inEntry) links += l
                                else when {
                                    l.rel == "next" -> next = l.href
                                    l.rel == "search" && l.type.contains("atom") && href.contains("{searchTerms}") ->
                                        search = l.href.replace("%7B", "{").replace("%7D", "}")
                                    l.rel == "search" && search == null -> search = "opensearch:${l.href}"
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> text = (text ?: "") + p.text
                XmlPullParser.END_TAG -> {
                    val name = p.name.substringAfter(':')
                    val t = text?.trim().orEmpty()
                    when (name) {
                        "title" -> if (inEntry) eTitle = t else if (feedTitle.isEmpty()) feedTitle = t
                        "name" -> if (inEntry && inAuthor && eAuthor.isEmpty()) eAuthor = t
                        "author" -> inAuthor = false
                        "summary", "content" -> if (inEntry && eSummary.isEmpty()) eSummary = EpubBook.stripTags(t).trim()
                        "entry" -> {
                            inEntry = false
                            val acq = links.filter {
                                it.rel.startsWith("http://opds-spec.org/acquisition") &&
                                    (it.type.contains("epub") || it.type.contains("pdf") || it.type.contains("comicbook") ||
                                        it.type.contains("cbz") || it.type.contains("cbr") || it.type.contains("rar"))
                            }
                            val nav = links.firstOrNull {
                                it.type.contains("atom+xml") && !it.rel.contains("image") && !it.rel.contains("acquisition")
                            }
                            if (eTitle.isNotEmpty() && (acq.isNotEmpty() || nav != null)) {
                                entries += Entry(eTitle, eAuthor, eSummary, nav, acq)
                            }
                        }
                    }
                    text = null
                }
            }
            ev = p.next()
        }
        return Feed(feedTitle, entries, next, search)
    }
}
