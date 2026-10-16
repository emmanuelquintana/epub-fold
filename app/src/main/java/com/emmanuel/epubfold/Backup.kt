package com.emmanuel.epubfold

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copia de seguridad local: un ZIP con backup.json (progreso, marcadores, notas,
 * estadísticas y ajustes) y, opcionalmente, los propios EPUB.
 */
object Backup {

    data class Result(val books: Int, val updated: Int, val added: Int)

    fun export(context: Context, uri: Uri, includeBooks: Boolean) {
        val library = Library(context)
        val books = library.all()
        val json = JSONObject().apply {
            put("version", 1)
            put("created", System.currentTimeMillis())
            put("prefs", ReaderPrefs(context).toJson())
            put("stats", ReadingStats(context).toJson())
            put("books", JSONArray().apply {
                books.forEach { b ->
                    put(bookJson(context, b).apply {
                        if (includeBooks) put("file", "books/${b.key}.epub")
                    })
                }
            })
        }
        val out = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("No se pudo escribir")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(json.toString(2).toByteArray())
            zip.closeEntry()
            if (includeBooks) {
                books.forEach { b ->
                    val f = File(b.path)
                    if (f.exists()) {
                        zip.putNextEntry(ZipEntry("books/${b.key}.epub"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    fun bookJson(context: Context, b: BookEntry): JSONObject = JSONObject().apply {
        put("key", b.key)
        put("title", b.title)
        put("author", b.author)
        put("chapter", b.chapter)
        put("offset", b.charOffset)
        put("progress", b.progress.toDouble())
        put("shelf", b.shelf)
        put("finishedAt", b.finishedAt)
        put("updatedAt", b.updatedAt)
        put("lastOpened", b.lastOpened)
        put("annotations", Annotations(context, b.id).toJson())
    }

    /** Aplica los datos de un libro (de copia o de la nube) si son más recientes. */
    fun mergeBook(context: Context, local: BookEntry, o: JSONObject): Boolean {
        var changed = false
        val library = Library(context)
        if (o.optLong("updatedAt") > local.updatedAt) {
            library.update(local.id) {
                it.copy(
                    chapter = o.optInt("chapter"),
                    charOffset = o.optInt("offset"),
                    progress = o.optDouble("progress", 0.0).toFloat(),
                    shelf = o.optString("shelf"),
                    finishedAt = o.optLong("finishedAt"),
                    updatedAt = o.optLong("updatedAt"),
                    lastOpened = maxOf(it.lastOpened, o.optLong("lastOpened")),
                )
            }
            changed = true
        }
        val ann = when (val a = o.opt("annotations")) {
            is JSONObject -> a
            is String -> runCatching { JSONObject(a) }.getOrNull()
            else -> null
        }
        if (ann != null) {
            val notes = Annotations(context, local.id)
            if (ann.optLong("updatedAt") > notes.updatedAt) {
                notes.replaceWith(ann)
                changed = true
            }
        }
        return changed
    }

    fun restore(context: Context, uri: Uri): Result {
        val tmp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var jsonText: String? = null
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("No se pudo leer")
        val bytes = input.use { it.readBytes() }
        // ¿ZIP o JSON suelto?
        if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    if (name == "backup.json") {
                        jsonText = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (name.startsWith("books/") && name.endsWith(".epub") && !name.contains("..")) {
                        File(tmp, name.substringAfterLast('/')).outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        } else {
            jsonText = bytes.toString(Charsets.UTF_8)
        }
        val json = JSONObject(jsonText ?: throw IllegalArgumentException("La copia no contiene backup.json"))
        val library = Library(context)
        var updated = 0
        var added = 0
        val arr = json.optJSONArray("books") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val key = o.optString("key")
            var local = library.findByKey(key)
            if (local == null) {
                val f = File(tmp, "$key.epub")
                if (f.exists()) {
                    local = runCatching { f.inputStream().use { library.importStream(it) } }.getOrNull()
                    if (local != null) added++
                }
            }
            if (local != null && mergeBook(context, local, o)) updated++
        }
        json.optJSONObject("stats")?.let { ReadingStats(context).mergeFrom(it) }
        json.optJSONObject("prefs")?.let { ReaderPrefs(context).fromJson(it) }
        tmp.deleteRecursively()
        return Result(arr.length(), updated, added)
    }
}
