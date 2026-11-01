package com.emmanuel.epubfold

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipFile

/** Una portada candidata de internet: miniatura para la lista y versión grande para guardar. */
class CoverSuggestion(val thumbUrl: String, val fullUrl: String, val source: String, val label: String)

/** Búsqueda de portadas en servicios gratuitos que no necesitan clave. */
object CoverSearch {

    private fun get(url: String, timeout: Int = 12_000): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "EpubFold/${BuildConfig.VERSION_NAME} (Android)")
        return try {
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }

    fun download(url: String): ByteArray? = runCatching { get(url, 20_000) }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Apple Books: tiene muchos cómics (Marvel, DC…) y portadas grandes. */
    private fun itunes(q: String): List<CoverSuggestion> {
        val json = get("https://itunes.apple.com/search?media=ebook&limit=15&term=${enc(q)}")?.toString(Charsets.UTF_8) ?: return emptyList()
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val art = o.optString("artworkUrl100").ifEmpty { return@mapNotNull null }
            CoverSuggestion(
                art.replace("100x100bb", "300x300bb"),
                art.replace("100x100bb", "1000x1000bb"),
                "Apple Books",
                o.optString("trackName"),
            )
        }
    }

    /** Google Books: libros y recopilatorios de cómics. */
    private fun googleBooks(q: String): List<CoverSuggestion> {
        val json = get("https://www.googleapis.com/books/v1/volumes?maxResults=15&printType=books&q=${enc(q)}")
            ?.toString(Charsets.UTF_8) ?: return emptyList()
        val arr = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val info = arr.getJSONObject(i).optJSONObject("volumeInfo") ?: return@mapNotNull null
            val img = info.optJSONObject("imageLinks")?.optString("thumbnail")?.ifEmpty { null } ?: return@mapNotNull null
            val https = img.replace("http://", "https://").replace("&edge=curl", "")
            CoverSuggestion(https, https.replace(Regex("zoom=\\d"), "zoom=0") + "&fife=w900", "Google Books", info.optString("title"))
        }
    }

    /** Open Library: catálogo libre, bueno para novelas. */
    private fun openLibrary(q: String): List<CoverSuggestion> {
        val json = get("https://openlibrary.org/search.json?limit=15&fields=cover_i,title&q=${enc(q)}")?.toString(Charsets.UTF_8)
            ?: return emptyList()
        val arr = JSONObject(json).optJSONArray("docs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.optLong("cover_i", 0L).takeIf { it > 0L } ?: return@mapNotNull null
            CoverSuggestion(
                "https://covers.openlibrary.org/b/id/$id-M.jpg",
                "https://covers.openlibrary.org/b/id/$id-L.jpg",
                "Open Library",
                o.optString("title"),
            )
        }
    }

    /** AniList: portadas de manga y novelas gráficas (API pública sin clave). */
    private fun aniList(q: String): List<CoverSuggestion> {
        val body = JSONObject().apply {
            put("query", "query(\$q:String){Page(perPage:12){media(search:\$q,type:MANGA){title{romaji english}coverImage{large extraLarge}}}}")
            put("variables", JSONObject().put("q", q))
        }.toString()
        val conn = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        val json = try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) return emptyList()
            conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
        val arr = JSONObject(json).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val img = o.optJSONObject("coverImage") ?: return@mapNotNull null
            val large = img.optString("large").ifEmpty { return@mapNotNull null }
            val t = o.optJSONObject("title")
            CoverSuggestion(large, img.optString("extraLarge").ifEmpty { large }, "AniList",
                t?.optString("english")?.takeIf { it.isNotEmpty() && it != "null" } ?: t?.optString("romaji").orEmpty())
        }
    }

    /**
     * Todas las fuentes en paralelo. Con [comic] se añaden búsquedas pensadas para
     * cómics y manga. Los resultados cuyo título coincide más con la búsqueda van primero.
     */
    suspend fun search(query: String, comic: Boolean = false): List<CoverSuggestion> = coroutineScope {
        fun safe(block: () -> List<CoverSuggestion>) = async(Dispatchers.IO) { runCatching(block).getOrDefault(emptyList()) }
        val jobs = ArrayList(listOf(
            safe { itunes(query) },
            safe { googleBooks(query) },
            safe { openLibrary(query) },
        ))
        if (comic) {
            jobs += safe { itunes("$query comic") }
            jobs += safe { googleBooks("$query subject:comics") }
            jobs += safe { googleBooks("$query graphic novel") }
            jobs += safe { aniList(query) }
        }
        val sources = jobs.awaitAll()
        val out = ArrayList<CoverSuggestion>()
        val seen = HashSet<String>()
        val max = sources.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) sources.forEach { list -> list.getOrNull(i)?.let { if (seen.add(it.fullUrl)) out += it } }
        // Relevancia: cuántas palabras de la búsqueda aparecen en el título del resultado
        val words = normalize(query).split(' ').filter { it.length > 1 }
        out.withIndex()
            .sortedWith(compareByDescending<IndexedValue<CoverSuggestion>> { iv ->
                val t = normalize(iv.value.label)
                words.count { t.contains(it) }
            }.thenBy { it.index })
            .map { it.value }
            .take(48)
    }

    private fun normalize(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Miniatura descargada y reducida. */
    fun thumb(url: String, maxW: Int): Bitmap? = runCatching {
        val bytes = get(url) ?: return null
        decodeScaled(bytes, maxW)
    }.getOrNull()

    fun decodeScaled(bytes: ByteArray, maxW: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth < 40 || o.outHeight < 40) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxW) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * Hoja «Cambiar portada»: páginas o imágenes del propio libro, sugerencias de internet
 * (Apple Books, Google Books, Open Library) y una imagen de la galería.
 */
class CoverPickerSheet(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val entry: BookEntry,
    private val onPickFromGallery: () -> Unit,
    private val onChanged: () -> Unit,
) {
    private val ctx = activity
    private val library = Library(activity)
    private val dialog = BottomSheetDialog(activity)
    private val jobs = ArrayList<Job>()
    private var searchJob: Job? = null

    private val tileW get() = ctx.dpi(if (ctx.widthDp() >= 600) 118 else 98)

    fun show() {
        val scroll = NestedScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(28))
        }
        scroll.addView(col)

        col.addView(TextView(ctx).apply {
            text = "Cambiar portada"
            setTextAppearance(R.style.Text_Headline)
            gravity = Gravity.CENTER
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(2))
        })
        col.addView(TextView(ctx).apply {
            text = entry.title
            setTextAppearance(R.style.Text_Footnote)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ctx.dpi(20), 0, ctx.dpi(20), ctx.dpi(12))
        })

        // --- Galería y buscadores de imágenes
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill("Galería") {
            dialog.dismiss()
            onPickFromGallery()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dpi(6) })
        actions.addView(pill("Google Imágenes") {
            dialog.dismiss()
            val q = cleanBookTitle(entry.title) + if (entry.format == BookFormat.EPUB) " portada libro" else " comic cover"
            activity.startActivity(CoverWebActivity.intent(activity, entry.id, q))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dpi(6) })
        col.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dpi(16), 0, ctx.dpi(16), ctx.dpi(6))
        })

        // --- Del propio libro
        col.addView(caption(if (entry.format == BookFormat.EPUB) "Imágenes del libro" else "Páginas del libro"))
        val ownRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val ownScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(ctx.dpi(12), 0, ctx.dpi(12), 0)
            addView(ownRow)
        }
        val ownStatus = note("Cargando…")
        col.addView(ownStatus)
        col.addView(ownScroll)
        loadOwnImages(ownRow, ownStatus)

        // --- Internet
        col.addView(caption("Sugerencias de internet"))
        val field = EditText(ctx).apply {
            setText(defaultQuery())
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = ctx.getDrawable(R.drawable.bg_search_field)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = ctx.dpi(8)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(ctx.color(R.color.label_secondary))
            setTextColor(ctx.color(R.color.label))
            setPadding(ctx.dpi(10), ctx.dpi(9), ctx.dpi(10), ctx.dpi(9))
        }
        col.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dpi(16), 0, ctx.dpi(16), ctx.dpi(8))
        })
        val webStatus = note("")
        col.addView(webStatus)
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(12), 0, ctx.dpi(12), 0)
        }
        col.addView(grid)
        field.setOnEditorActionListener { v, _, _ ->
            (ctx.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            search(v.text.toString(), grid, webStatus)
            true
        }
        col.addView(TextView(ctx).apply {
            text = "Portadas de Apple Books, Google Books, Open Library y AniList (manga). ¿No aparece? Usa «Google Imágenes». Se guarda una copia sólo en tu teléfono."
            setTextAppearance(R.style.Text_Footnote)
            setPadding(ctx.dpi(20), ctx.dpi(10), ctx.dpi(20), 0)
        })
        search(field.text.toString(), grid, webStatus)

        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = ctx.dpi(760)
        dialog.setOnDismissListener {
            jobs.forEach { it.cancel() }
            searchJob?.cancel()
        }
        dialog.show()
    }

    /** Título limpio para buscar: sin extensión, guiones bajos ni números de archivo. */
    private fun defaultQuery(): String {
        val t = cleanBookTitle(entry.title)
        return if (entry.author.isNotBlank() && entry.format == BookFormat.EPUB) "$t ${entry.author}" else t
    }

    // ---------------------------------------------------------------- imágenes del libro

    private fun loadOwnImages(row: LinearLayout, status: TextView) {
        jobs += scope.launch {
            val file = File(entry.path)
            val maxW = tileW
            var count = 0
            if (entry.format == BookFormat.EPUB) {
                val names = withContext(Dispatchers.IO) {
                    runCatching {
                        ZipFile(file).use { z ->
                            z.entries().asSequence()
                                .filter { !it.isDirectory && Formats.isImageName(it.name) && it.size > 15_000 }
                                .sortedByDescending { it.size }
                                .take(16)
                                .map { it.name }
                                .toList()
                        }
                    }.getOrDefault(emptyList())
                }
                for (name in names) {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { ZipFile(file).use { z -> z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } } } }.getOrNull()
                    } ?: continue
                    val bmp = withContext(Dispatchers.Default) { CoverSearch.decodeScaled(bytes, maxW * 2) } ?: continue
                    count++
                    row.addView(tile(bmp, null) { apply(bytes) })
                }
            } else {
                val book = withContext(Dispatchers.IO) { runCatching { Formats.open(entry.format, file) }.getOrNull() }
                if (book != null) {
                    try {
                        val n = minOf(book.pageCount, 24)
                        for (i in 0 until n) {
                            val bmp = withContext(Dispatchers.IO) { runCatching { book.render(i, maxW * 2, maxW * 3) }.getOrNull() } ?: continue
                            count++
                            row.addView(tile(bmp, "Pág. ${i + 1}") {
                                // Para guardar se dibuja la página más grande
                                scope.launch {
                                    val big = withContext(Dispatchers.IO) {
                                        runCatching { Formats.open(entry.format, file).use { it.render(i, 800, 1200) } }.getOrNull()
                                    } ?: bmp
                                    apply(toJpeg(big))
                                }
                            })
                        }
                    } finally {
                        withContext(Dispatchers.IO) { runCatching { book.close() } }
                    }
                }
            }
            status.visibility = if (count == 0) View.VISIBLE else View.GONE
            status.text = "No hay imágenes que sirvan como portada."
        }
    }

    // ---------------------------------------------------------------- internet

    private fun search(query: String, grid: LinearLayout, status: TextView) {
        searchJob?.cancel()
        grid.removeAllViews()
        val q = query.trim()
        if (q.length < 2) {
            status.text = "Escribe el título para buscar."
            return
        }
        status.visibility = View.VISIBLE
        status.text = "Buscando portadas…"
        searchJob = scope.launch {
            val results = CoverSearch.search(q, comic = entry.format != BookFormat.EPUB)
            if (results.isEmpty()) {
                status.text = "Sin resultados (o sin conexión). Prueba con otras palabras, p. ej. el título en inglés."
                return@launch
            }
            status.text = "${results.size} portadas encontradas · toca una para usarla"
            val columns = maxOf(3, (grid.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels) / (tileW + ctx.dpi(12)))
            var row: LinearLayout? = null
            results.forEachIndexed { i, s ->
                if (i % columns == 0) {
                    row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    grid.addView(row)
                }
                val holder = FrameLayout(ctx)
                row!!.addView(holder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val t = tile(null, s.source) {
                    scope.launch {
                        Toast.makeText(ctx, "Descargando portada…", Toast.LENGTH_SHORT).show()
                        val bytes = withContext(Dispatchers.IO) { CoverSearch.download(s.fullUrl) ?: CoverSearch.download(s.thumbUrl) }
                        if (bytes == null) Toast.makeText(ctx, "No se pudo descargar esa portada", Toast.LENGTH_LONG).show()
                        else apply(bytes)
                    }
                }
                holder.addView(t)
                launch {
                    val bmp = withContext(Dispatchers.IO) { CoverSearch.thumb(s.thumbUrl, tileW * 2) }
                    val img = t.findViewWithTag<ImageView>("img")
                    val spinner = t.findViewWithTag<View>("spin")
                    spinner?.visibility = View.GONE
                    if (bmp != null) img?.setImageBitmap(bmp) else holder.visibility = View.INVISIBLE
                }
            }
            // Completa la última fila para que las portadas no se estiren
            val last = row
            if (last != null) repeat((columns - results.size % columns) % columns) {
                last.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            }
        }
    }

    // ---------------------------------------------------------------- guardar

    private fun toJpeg(bmp: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun apply(bytes: ByteArray) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { library.setCover(entry.id, bytes) }.getOrDefault(false) }
            if (ok) {
                Toast.makeText(ctx, "Portada actualizada", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onChanged()
            } else {
                Toast.makeText(ctx, "Esa imagen no sirve como portada", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- piezas

    private fun tile(bmp: Bitmap?, label: String?, onClick: () -> Unit): View {
        val w = tileW
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dpi(6), ctx.dpi(6), ctx.dpi(6), ctx.dpi(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val card = CardView(ctx).apply {
            radius = ctx.dp(6f)
            cardElevation = ctx.dp(4f)
            useCompatPadding = true
            setCardBackgroundColor(ctx.color(R.color.fill))
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            foreground = ctx.getDrawable(tv.resourceId)
            setOnClickListener { onClick() }
        }
        val frame = FrameLayout(ctx)
        frame.addView(ImageView(ctx).apply {
            tag = "img"
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (bmp != null) setImageBitmap(bmp)
            contentDescription = label ?: "Portada"
        }, FrameLayout.LayoutParams(w, w * 3 / 2))
        if (bmp == null) frame.addView(ProgressBar(ctx).apply { tag = "spin" },
            FrameLayout.LayoutParams(ctx.dpi(28), ctx.dpi(28), Gravity.CENTER))
        card.addView(frame)
        box.addView(card)
        if (label != null) box.addView(TextView(ctx).apply {
            text = label
            setTextAppearance(R.style.Text_Footnote)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            gravity = Gravity.CENTER
        })
        return box
    }

    private fun caption(t: String) = TextView(ctx).apply {
        text = t
        setTextAppearance(R.style.Text_Caption)
        setPadding(ctx.dpi(20), ctx.dpi(18), ctx.dpi(20), ctx.dpi(8))
    }

    private fun note(t: String) = TextView(ctx).apply {
        text = t
        setTextAppearance(R.style.Text_Footnote)
        setPadding(ctx.dpi(20), 0, ctx.dpi(20), ctx.dpi(6))
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(ctx.color(R.color.accent))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = roundRect(ctx.color(R.color.fill), ctx.dp(12f))
        setPadding(ctx.dpi(12), ctx.dpi(12), ctx.dpi(12), ctx.dpi(12))
        setOnClickListener { onClick() }
    }
}

/** Quita extensiones de archivo y guiones bajos que se cuelan en los títulos («Civil War II.cbz»). */
fun cleanBookTitle(title: String): String {
    var t = title.trim()
    val ext = Regex("\\.(cbz|cbr|zip|rar|pdf|epub)$", RegexOption.IGNORE_CASE)
    while (ext.containsMatchIn(t)) t = t.replace(ext, "").trim()
    return t.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { title }
}
