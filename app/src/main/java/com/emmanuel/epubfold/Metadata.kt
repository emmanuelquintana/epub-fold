package com.emmanuel.epubfold

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Portadas desde Open Library para los libros que no traen una propia. */
object Metadata {

    private fun get(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "EpubFold/${BuildConfig.VERSION_NAME} (Android)")
        return try {
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }

    /** Busca la portada de [title] / [author]. Bloqueante: llamar en segundo plano. */
    fun findCover(title: String, author: String): ByteArray? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        // Quitamos subtítulos y números de volumen entre paréntesis para encontrar más
        val cleanTitle = title.substringBefore(':').replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim().ifEmpty { title }
        val query = buildString {
            append("https://openlibrary.org/search.json?limit=5&fields=cover_i&title=").append(enc(cleanTitle))
            if (author.isNotBlank()) append("&author=").append(enc(author))
        }
        val json = get(query)?.toString(Charsets.UTF_8) ?: return null
        val docs = JSONObject(json).optJSONArray("docs") ?: return null
        for (i in 0 until docs.length()) {
            val id = docs.getJSONObject(i).optLong("cover_i", 0L)
            if (id > 0L) return get("https://covers.openlibrary.org/b/id/$id-L.jpg?default=false")
        }
        return null
    }

    /** Intenta poner portada a los libros que no la tienen (una sola vez por libro). Devuelve cuántas encontró. */
    fun fillMissingCovers(context: Context): Int {
        val library = Library(context)
        var found = 0
        library.everything().filter { it.coverPath == null && !it.coverLookedUp }.take(10).forEach { b ->
            val bytes = runCatching { findCover(b.title, b.author) }.getOrNull()
            if (bytes != null && library.setCover(b.id, bytes)) found++ else library.markCoverLookedUp(b.id)
        }
        return found
    }
}
