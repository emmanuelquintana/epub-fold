package com.emmanuel.epubfold

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * «Qué leer después»: el siguiente de tus series y sugerencias de Open Library
 * según los temas de los libros que terminaste.
 */
class DiscoverActivity : GroupedActivity() {

    override val screenTitle = "Qué leer después"

    data class Suggestion(val title: String, val author: String, val key: String, val because: String)

    private var suggestions: List<Suggestion>? = null
    private var error: String? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { loadSuggestions() } }
            suggestions = r.getOrNull() ?: emptyList()
            error = r.exceptionOrNull()?.message
            rebuild()
        }
    }

    override fun build() {
        val library = Library(this)
        val all = library.all()

        // --- Series
        val seriesRows = ArrayList<android.view.View>()
        all.filter { it.series.isNotBlank() }.groupBy { it.series.lowercase() }.values.forEach { books ->
            val started = books.filter { it.progress > 0f || it.finishedAt > 0L }
            if (started.isEmpty()) return@forEach
            val last = started.maxByOrNull { it.seriesIndex } ?: return@forEach
            val next = library.nextInSeries(last)
            if (next != null) {
                seriesRows += row("Sigue con «${next.title}»", "${next.series}" +
                    (if (next.seriesIndex > 0f) " · nº ${Formats.fmtIndex(next.seriesIndex)}" else ""), trailing = "›") {
                    startActivity(LibraryActivity.readerIntent(this, next))
                }
            } else if (last.collection == Shelf.FINISHED && last.seriesIndex > 0f) {
                val want = Formats.fmtIndex(last.seriesIndex + 1)
                seriesRows += row("Te falta el nº $want de ${last.series}", "No está en tu biblioteca: búscalo en los catálogos") {
                    openCatalogSearch("${last.series} $want")
                }
            }
        }
        caption("Tus series")
        val sg = group()
        if (seriesRows.isEmpty()) sg.addView(paragraph("Cuando leas libros de una serie (se detecta del EPUB o puedes ponerla con «Añadir a una serie…»), aquí aparecerá el siguiente."))
        else sg.rows(*seriesRows.toTypedArray())

        // --- Quiero leer
        val want = all.filter { it.collection == Shelf.WANT }
        if (want.isNotEmpty()) {
            spacer(22)
            caption("En tu lista «Quiero leer»")
            val wg = group()
            want.take(8).forEach { b -> wg.rows(row(b.title, b.author, trailing = "›") { startActivity(LibraryActivity.readerIntent(this, b)) }) }
        }

        // --- Open Library
        spacer(22)
        caption("Sugerencias por lo que has leído")
        val og = group()
        val s = suggestions
        when {
            s == null -> og.addView(paragraph("Buscando en Open Library…"))
            s.isEmpty() -> og.addView(paragraph(
                error?.let { "No se pudieron cargar sugerencias ($it)." }
                    ?: "Termina algún libro (o márcalo como terminado) para recibir sugerencias por temas."
            ))
            else -> s.forEach { sug ->
                og.rows(row(sug.title, listOf(sug.author, "por ${sug.because}").filter { it.isNotBlank() }.joinToString(" · ")) {
                    showSuggestion(sug)
                })
            }
        }
        footnote("Las sugerencias vienen de Open Library. Los libros de dominio público suelen estar gratis en Project Gutenberg.")
    }

    private fun showSuggestion(s: Suggestion) {
        MaterialAlertDialogBuilder(this)
            .setTitle(s.title)
            .setMessage(listOf(s.author, "Sugerido por: ${s.because}").filter { it.isNotBlank() }.joinToString("\n"))
            .setPositiveButton("Buscar gratis (Gutenberg)") { _, _ -> openCatalogSearch("${s.title} ${s.author}") }
            .setNeutralButton("Ver en Open Library") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openlibrary.org${s.key}"))) }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openCatalogSearch(q: String) {
        val url = "https://www.gutenberg.org/ebooks/search.opds/?query=" + URLEncoder.encode(q.trim(), "UTF-8")
        startActivity(Intent(this, OpdsActivity::class.java)
            .putExtra(OpdsActivity.EXTRA_URL, url)
            .putExtra(OpdsActivity.EXTRA_NAME, "Project Gutenberg"))
    }

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "EpubFold/${BuildConfig.VERSION_NAME} (Android)")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun loadSuggestions(): List<Suggestion> {
        val library = Library(this).all()
        val owned = library.map { it.title.lowercase().substringBefore(':').trim() }.toSet()
        // Los últimos terminados; si no hay, los que más has avanzado
        val seeds = library.filter { it.collection == Shelf.FINISHED }.sortedByDescending { it.finishedAt }.take(3)
            .ifEmpty { library.filter { it.progress > 0.3f }.sortedByDescending { it.lastOpened }.take(2) }
        val out = LinkedHashMap<String, Suggestion>()
        seeds.forEach { b ->
            val q = "https://openlibrary.org/search.json?limit=1&fields=subject&title=" + URLEncoder.encode(b.title.substringBefore(':'), "UTF-8") +
                (if (b.author.isNotBlank()) "&author=" + URLEncoder.encode(b.author, "UTF-8") else "")
            val subjects = runCatching { get(q).getJSONArray("docs").getJSONObject(0).optJSONArray("subject") }.getOrNull() ?: return@forEach
            // Temas concretos (los muy genéricos dan sugerencias malas)
            val generic = setOf("fiction", "accessible book", "protected daisy", "in library", "novel", "lending library")
            val picked = (0 until subjects.length()).map { subjects.getString(it) }
                .filter { s -> generic.none { s.lowercase().contains(it) } && s.length < 40 }.take(2)
            picked.forEach { subject ->
                val slug = subject.lowercase().replace(Regex("[^a-z0-9áéíóúñü]+"), "_").trim('_')
                val works = runCatching { get("https://openlibrary.org/subjects/$slug.json?limit=8").getJSONArray("works") }.getOrNull()
                    ?: return@forEach
                for (i in 0 until works.length()) {
                    val w = works.getJSONObject(i)
                    val title = w.optString("title")
                    if (title.isBlank() || title.lowercase().substringBefore(':').trim() in owned || out.containsKey(title.lowercase())) continue
                    val author = w.optJSONArray("authors")?.optJSONObject(0)?.optString("name").orEmpty()
                    out[title.lowercase()] = Suggestion(title, author, w.optString("key"), "«${b.title}»")
                    if (out.size >= 15) return out.values.toList()
                }
            }
        }
        return out.values.toList()
    }
}
