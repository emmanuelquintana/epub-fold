package com.emmanuel.epubfold

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Datos de un libro traídos de Google Books: sinopsis, géneros, páginas, año, editorial. */
data class BookInfo(
    val description: String,
    val genres: List<String>,
    val pageCount: Int,
    val published: String,
    val publisher: String,
)

object BookInfoStore {

    private fun file(c: Context) = File(c.filesDir, "book_info.json")

    @Volatile private var cache: MutableMap<String, BookInfo?>? = null

    @Synchronized
    private fun all(c: Context): MutableMap<String, BookInfo?> {
        cache?.let { return it }
        val m = HashMap<String, BookInfo?>()
        runCatching {
            val o = JSONObject(file(c).readText())
            o.keys().forEach { id ->
                val v = o.optJSONObject(id)
                m[id] = if (v == null || !v.has("d")) null else BookInfo(
                    v.optString("d"),
                    v.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    v.optInt("p"), v.optString("y"), v.optString("e"),
                )
            }
        }
        cache = m
        return m
    }

    @Synchronized
    private fun save(c: Context) {
        val o = JSONObject()
        all(c).forEach { (id, i) ->
            o.put(id, if (i == null) JSONObject().put("none", true) else JSONObject()
                .put("d", i.description).put("g", JSONArray(i.genres)).put("p", i.pageCount)
                .put("y", i.published).put("e", i.publisher))
        }
        file(c).writeText(o.toString())
    }

    fun get(c: Context, id: String): BookInfo? = all(c)[id]

    /** ¿Ya se buscó (aunque no se encontrara nada)? */
    fun looked(c: Context, id: String) = all(c).containsKey(id)

    fun put(c: Context, id: String, info: BookInfo?) {
        all(c)[id] = info
        save(c)
    }

    /** Géneros para filtrar: los de Google Books o «Cómics» para los libros de imágenes. */
    fun genresOf(c: Context, b: BookEntry): List<String> =
        get(c, b.id)?.genres?.takeIf { it.isNotEmpty() } ?: if (b.format == BookFormat.CBZ) listOf("Cómics") else emptyList()

    fun isShort(c: Context, b: BookEntry): Boolean = get(c, b.id)?.pageCount?.let { it in 1..199 } == true
}

object BookInfoFetch {

    private val GENRES = mapOf(
        "science fiction" to "Ciencia ficción", "fantasy" to "Fantasía", "comics & graphic novels" to "Cómics",
        "comics" to "Cómics", "graphic novels" to "Cómics", "manga" to "Manga", "biography & autobiography" to "Biografías",
        "history" to "Historia", "mystery & detective" to "Misterio", "mystery" to "Misterio", "thrillers" to "Suspense",
        "suspense" to "Suspense", "romance" to "Romance", "horror" to "Terror", "poetry" to "Poesía",
        "philosophy" to "Filosofía", "self-help" to "Autoayuda", "business & economics" to "Negocios",
        "psychology" to "Psicología", "juvenile fiction" to "Juvenil", "young adult fiction" to "Juvenil",
        "science" to "Ciencia", "religion" to "Religión", "cooking" to "Cocina", "travel" to "Viajes",
        "humor" to "Humor", "literary collections" to "Antologías", "drama" to "Teatro", "art" to "Arte",
        "computers" to "Informática", "health & fitness" to "Salud", "true crime" to "Crímenes reales",
        "dystopian" to "Distopía", "action & adventure" to "Aventura", "historical" to "Histórica",
        "literary" to "Literaria", "classics" to "Clásicos", "fiction" to "Ficción",
    )

    /** «Fiction / Science Fiction / General» → «Ciencia ficción». */
    fun genre(category: String): String? {
        val parts = category.split('/').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("general", true) }
        for (p in parts.reversed()) GENRES[p.lowercase()]?.let { return it }
        return parts.lastOrNull()
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("User-Agent", "EpubFold/${BuildConfig.VERSION_NAME} (Android)")
        return try {
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) else null
        } finally {
            conn.disconnect()
        }
    }

    /** Busca el libro en Google Books. Bloqueante. */
    fun fetch(title: String, author: String): BookInfo? {
        val t = cleanBookTitle(title).substringBefore(':').trim()
        val q = buildString {
            append("intitle:").append(t)
            if (author.isNotBlank()) append(" inauthor:").append(author.substringBefore(',').trim())
        }
        val json = get("https://www.googleapis.com/books/v1/volumes?maxResults=5&printType=books&q=" + URLEncoder.encode(q, "UTF-8"))
            ?: return null
        val items = JSONObject(json).optJSONArray("items") ?: return null
        // El primer resultado con sinopsis; si ninguno tiene, el primero
        var best: JSONObject? = null
        for (i in 0 until items.length()) {
            val v = items.getJSONObject(i).optJSONObject("volumeInfo") ?: continue
            if (best == null) best = v
            if (v.optString("description").length > 80) { best = v; break }
        }
        val v = best ?: return null
        val genres = v.optJSONArray("categories")?.let { a -> (0 until a.length()).mapNotNull { genre(a.getString(it)) } }
            ?.distinct()?.take(3) ?: emptyList()
        return BookInfo(
            android.text.Html.fromHtml(v.optString("description"), android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim(),
            genres,
            v.optInt("pageCount"),
            v.optString("publishedDate").take(4),
            v.optString("publisher"),
        )
    }

    /** Rellena los libros que aún no tienen datos (pocos cada vez). Devuelve cuántos encontró. */
    fun fillMissing(context: Context, limit: Int = 8): Int {
        var n = 0
        Library(context).everything().filter { !BookInfoStore.looked(context, it.id) }.take(limit).forEach { b ->
            val info = runCatching { fetch(b.title, b.author) }.getOrNull()
            BookInfoStore.put(context, b.id, info)
            if (info != null) n++
        }
        return n
    }
}

/** Hoja «Detalles del libro». */
object BookDetailsSheet {
    fun show(activity: Activity, scope: LifecycleCoroutineScope, b: BookEntry, onChanged: () -> Unit) {
        val ctx = activity
        val dialog = BottomSheetDialog(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(20), ctx.dpi(12), ctx.dpi(20), ctx.dpi(28))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(CardView(ctx).apply {
            radius = ctx.dp(6f)
            cardElevation = ctx.dp(5f)
            useCompatPadding = true
            addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.cover_placeholder)
                BookThemes.loadCover(b.coverPath)?.let { setImageBitmap(it) }
            })
        }, LinearLayout.LayoutParams(ctx.dpi(92), ctx.dpi(138)))
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(14), ctx.dpi(6), 0, 0)
        }
        titles.addView(TextView(ctx).apply {
            text = b.title
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(ctx.color(R.color.label))
        })
        titles.addView(TextView(ctx).apply { text = b.author; setTextAppearance(R.style.Text_Subhead) })
        val facts = TextView(ctx).apply { setTextAppearance(R.style.Text_Footnote); setPadding(0, ctx.dpi(8), 0, 0) }
        titles.addView(facts)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)
        val desc = TextView(ctx).apply {
            setTextAppearance(R.style.Text_Body)
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
            setPadding(0, ctx.dpi(16), 0, 0)
        }
        col.addView(desc)
        val action = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(ctx.color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = roundRect(ctx.color(R.color.fill), ctx.dp(12f))
            setPadding(ctx.dpi(12), ctx.dpi(11), ctx.dpi(12), ctx.dpi(11))
        }
        col.addView(action, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dpi(18) })

        fun fill() {
            val info = BookInfoStore.get(ctx, b.id)
            val list = ArrayList<String>()
            BookInfoStore.genresOf(ctx, b).takeIf { it.isNotEmpty() }?.let { list += it.joinToString(", ") }
            if (info != null) {
                if (info.pageCount > 0) list += "${info.pageCount} páginas"
                if (info.published.isNotBlank()) list += info.published
                if (info.publisher.isNotBlank()) list += info.publisher
            }
            if (b.series.isNotBlank()) list += "Serie: ${b.series}" + if (b.seriesIndex > 0f) " #${Formats.fmtIndex(b.seriesIndex)}" else ""
            facts.text = list.joinToString("\n")
            desc.text = info?.description?.ifBlank { null } ?: "Sin sinopsis todavía."
            action.text = if (info == null) "Buscar datos en internet" else "Volver a buscar datos"
        }
        action.setOnClickListener {
            action.text = "Buscando…"
            scope.launch {
                val info = withContext(Dispatchers.IO) { runCatching { BookInfoFetch.fetch(b.title, b.author) }.getOrNull() }
                if (info != null) BookInfoStore.put(ctx, b.id, info)
                else android.widget.Toast.makeText(ctx, "No se encontraron datos. Prueba a corregir el título o el autor.", android.widget.Toast.LENGTH_LONG).show()
                fill()
                onChanged()
            }
        }
        fill()
        dialog.setContentView(NestedScrollView(ctx).apply { addView(col) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = ctx.dpi(680)
        dialog.show()
    }
}
