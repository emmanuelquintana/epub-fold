package com.emmanuel.epubfold

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

data class Bookmark(val chapter: Int, val charOffset: Int, val snippet: String, val created: Long)

data class Highlight(
    val id: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val color: Int,      // índice en HIGHLIGHT_COLORS
    val note: String,
    val text: String,
    val created: Long,
)

/** Marcadores, subrayados y notas de un libro, guardados en JSON. */
class Annotations(context: Context, bookId: String) {

    private val dir = File(context.filesDir, "annotations").apply { mkdirs() }
    private val file = File(dir, "$bookId.json")
    private val legacyFile = File(File(context.filesDir, "marks"), "$bookId.json")

    val bookmarks: MutableList<Bookmark> = ArrayList()
    val highlights: MutableList<Highlight> = ArrayList()

    /** Última modificación (para sincronizar entre dispositivos). */
    var updatedAt: Long = 0L
        private set

    /** Cuaderno libre del libro (notas largas). */
    var notebook: String = ""
        private set

    init {
        load()
    }

    // ---------------------------------------------------------------- marcadores

    fun findBookmark(chapter: Int, start: Int, end: Int): Bookmark? =
        bookmarks.firstOrNull { it.chapter == chapter && it.charOffset >= start && it.charOffset < end }

    fun addBookmark(b: Bookmark) {
        bookmarks += b
        bookmarks.sortWith(compareBy({ it.chapter }, { it.charOffset }))
        save()
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks.remove(b)
        save()
    }

    // ---------------------------------------------------------------- subrayados

    fun highlightsIn(chapter: Int): List<Highlight> = highlights.filter { it.chapter == chapter }

    fun highlightAt(chapter: Int, offset: Int): Highlight? =
        highlights.lastOrNull { it.chapter == chapter && offset >= it.start && offset < it.end }

    fun addHighlight(chapter: Int, start: Int, end: Int, color: Int, text: String, note: String = ""): Highlight {
        // Un subrayado nuevo sustituye a los que se solapan por completo
        highlights.removeAll { it.chapter == chapter && it.start >= start && it.end <= end }
        val h = Highlight(UUID.randomUUID().toString(), chapter, start, end, color, note, text, System.currentTimeMillis())
        highlights += h
        highlights.sortWith(compareBy({ it.chapter }, { it.start }))
        save()
        return h
    }

    fun updateHighlight(h: Highlight) {
        val i = highlights.indexOfFirst { it.id == h.id }
        if (i >= 0) highlights[i] = h else highlights += h
        save()
    }

    fun removeHighlight(h: Highlight) {
        highlights.removeAll { it.id == h.id }
        save()
    }

    fun setNotebook(text: String) {
        notebook = text
        save()
    }

    fun clear() {
        notebook = ""
        bookmarks.clear()
        highlights.clear()
        file.delete()
        legacyFile.delete()
    }

    // ---------------------------------------------------------------- exportar

    /** Notas y subrayados en Markdown, para compartir o guardar. */
    fun toMarkdown(title: String, author: String, chapterTitle: (Int) -> String): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append('\n')
        if (author.isNotEmpty()) sb.append("*").append(author).append("*\n")
        sb.append('\n')
        var lastChapter = -1
        highlights.sortedWith(compareBy({ it.chapter }, { it.start })).forEach { h ->
            if (h.chapter != lastChapter) {
                sb.append("\n## ").append(chapterTitle(h.chapter)).append("\n\n")
                lastChapter = h.chapter
            }
            sb.append("> ").append(h.text.replace("\n", " ").trim()).append('\n')
            if (h.note.isNotBlank()) sb.append("\n**Nota:** ").append(h.note.trim()).append('\n')
            sb.append("\n— ").append(HIGHLIGHT_NAMES.getOrElse(h.color) { "" }).append(" · ")
                .append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(h.created))).append("\n\n")
        }
        if (notebook.isNotBlank()) {
            sb.append("\n## Cuaderno\n\n").append(notebook.trim()).append('\n')
        }
        if (bookmarks.isNotEmpty()) {
            sb.append("\n## Marcadores\n\n")
            bookmarks.forEach { sb.append("- ").append(chapterTitle(it.chapter)).append(": ").append(it.snippet).append('\n') }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- JSON

    fun toJson(): JSONObject = JSONObject().apply {
        put("updatedAt", updatedAt)
        if (notebook.isNotEmpty()) put("notebook", notebook)
        put("bookmarks", JSONArray().apply {
            bookmarks.forEach {
                put(JSONObject().put("chapter", it.chapter).put("offset", it.charOffset)
                    .put("snippet", it.snippet).put("created", it.created))
            }
        })
        put("highlights", JSONArray().apply {
            highlights.forEach {
                put(JSONObject().put("id", it.id).put("chapter", it.chapter).put("start", it.start)
                    .put("end", it.end).put("color", it.color).put("note", it.note)
                    .put("text", it.text).put("created", it.created))
            }
        })
    }

    /** Sustituye el contenido por el de [o] (si es más reciente o se fuerza). */
    fun replaceWith(o: JSONObject) {
        parse(o)
        writeFile()
    }

    private fun parse(o: JSONObject) {
        bookmarks.clear()
        highlights.clear()
        updatedAt = o.optLong("updatedAt")
        notebook = o.optString("notebook")
        o.optJSONArray("bookmarks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                bookmarks += Bookmark(b.optInt("chapter"), b.optInt("offset"), b.optString("snippet"), b.optLong("created"))
            }
        }
        o.optJSONArray("highlights")?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                highlights += Highlight(
                    h.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    h.optInt("chapter"), h.optInt("start"), h.optInt("end"), h.optInt("color"),
                    h.optString("note"), h.optString("text"), h.optLong("created"),
                )
            }
        }
    }

    private fun load() {
        try {
            if (file.exists()) {
                parse(JSONObject(file.readText()))
            } else if (legacyFile.exists()) {
                // Formato antiguo: sólo marcadores en un array
                val arr = JSONArray(legacyFile.readText())
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    bookmarks += Bookmark(b.optInt("chapter"), b.optInt("offset"), b.optString("snippet"), b.optLong("created"))
                }
            }
        } catch (e: Exception) {
            bookmarks.clear()
            highlights.clear()
        }
    }

    private fun save() {
        updatedAt = System.currentTimeMillis()
        writeFile()
    }

    private fun writeFile() {
        file.writeText(toJson().toString())
    }
}
