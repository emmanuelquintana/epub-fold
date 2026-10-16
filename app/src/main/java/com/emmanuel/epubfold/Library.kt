package com.emmanuel.epubfold

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class BookEntry(
    val id: String,
    val title: String,
    val author: String,
    val path: String,
    val coverPath: String?,
    val progress: Float,
    val lastOpened: Long,
    val chapter: Int,
    val charOffset: Int,
)

/** Biblioteca local: los EPUB se copian a almacenamiento privado y se indexan en JSON. */
class Library(private val context: Context) {

    private val indexFile = File(context.filesDir, "library.json")
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    fun all(): List<BookEntry> = load().sortedByDescending { it.lastOpened }

    fun get(id: String): BookEntry? = load().firstOrNull { it.id == id }

    /** Copia el EPUB del [uri] y lo añade. Se ejecuta en segundo plano. */
    fun import(uri: Uri): BookEntry {
        val id = UUID.randomUUID().toString()
        val dest = File(booksDir, "$id.epub")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalArgumentException("No se pudo leer el archivo")

        return try {
            EpubBook(dest).use { book ->
                var cover: String? = null
                book.coverPath?.let { cp ->
                    book.readBytes(cp)?.let { bytes ->
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                        var sample = 1
                        while (o.outWidth / (sample * 2) >= 400) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                            BitmapFactory.Options().apply { inSampleSize = sample })?.let { bmp ->
                            val f = File(coversDir, "$id.jpg")
                            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                            cover = f.absolutePath
                        }
                    }
                }
                val entry = BookEntry(id, book.title, book.author, dest.absolutePath, cover, 0f,
                    System.currentTimeMillis(), 0, 0)
                save(load() + entry)
                entry
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    fun updatePosition(id: String, chapter: Int, charOffset: Int, progress: Float) {
        save(load().map {
            if (it.id == id) it.copy(chapter = chapter, charOffset = charOffset, progress = progress,
                lastOpened = System.currentTimeMillis()) else it
        })
    }

    fun resetProgress(id: String) {
        save(load().map { if (it.id == id) it.copy(chapter = 0, charOffset = 0, progress = 0f) else it })
        Bookmarks(context, id).clear()
    }

    fun delete(id: String) {
        Bookmarks(context, id).clear()
        val list = load()
        list.firstOrNull { it.id == id }?.let { e ->
            File(e.path).delete()
            e.coverPath?.let { File(it).delete() }
        }
        save(list.filter { it.id != id })
    }

    @Synchronized
    private fun load(): List<BookEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BookEntry(
                    o.getString("id"), o.optString("title"), o.optString("author"),
                    o.getString("path"), o.optString("cover").ifEmpty { null },
                    o.optDouble("progress", 0.0).toFloat(), o.optLong("lastOpened"),
                    o.optInt("chapter"), o.optInt("charOffset")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(list: List<BookEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("title", it.title); put("author", it.author)
                put("path", it.path); put("cover", it.coverPath ?: "")
                put("progress", it.progress.toDouble()); put("lastOpened", it.lastOpened)
                put("chapter", it.chapter); put("charOffset", it.charOffset)
            })
        }
        val tmp = File(indexFile.parentFile, "library.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
    }
}

enum class LayoutMode(val label: String) { AUTO("Automático"), SINGLE("Una página"), DOUBLE("Doble página") }

/** Preferencias de lectura. */
class ReaderPrefs(context: Context) {
    private val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumPref(key: String, def: E): E {
        val name = sp.getString(key, null) ?: return def
        return enumValues<E>().firstOrNull { it.name == name } ?: def
    }

    var theme: ReaderTheme
        get() = enumPref("themeName", ReaderTheme.ORIGINAL)
        set(v) {
            sp.edit().putString("themeName", v.name).apply()
            if (v.isDark) lastDark = v else lastLight = v
        }

    var lastLight: ReaderTheme
        get() = enumPref("lastLight", ReaderTheme.ORIGINAL)
        set(v) = sp.edit().putString("lastLight", v.name).apply()

    var lastDark: ReaderTheme
        get() = enumPref("lastDark", ReaderTheme.NOCHE)
        set(v) = sp.edit().putString("lastDark", v.name).apply()

    var fontSizeSp: Float
        get() = sp.getFloat("fontSize", 19f)
        set(v) = sp.edit().putFloat("fontSize", v).apply()

    var serif: Boolean
        get() = sp.getBoolean("serif", true)
        set(v) = sp.edit().putBoolean("serif", v).apply()

    var lineSpacing: Float
        get() = sp.getFloat("lineSpacing", 1.3f)
        set(v) = sp.edit().putFloat("lineSpacing", v).apply()

    var marginScale: Float
        get() = sp.getFloat("marginScale", 1f)
        set(v) = sp.edit().putFloat("marginScale", v).apply()

    var justify: Boolean
        get() = sp.getBoolean("justify", true)
        set(v) = sp.edit().putBoolean("justify", v).apply()

    var layoutMode: LayoutMode
        get() = enumPref("layoutName", LayoutMode.AUTO)
        set(v) = sp.edit().putString("layoutName", v.name).apply()

    var animation: PageAnimation
        get() = enumPref("animation", PageAnimation.CURL)
        set(v) = sp.edit().putString("animation", v.name).apply()

    var volumeKeys: Boolean
        get() = sp.getBoolean("volumeKeys", true)
        set(v) = sp.edit().putBoolean("volumeKeys", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", true)
        set(v) = sp.edit().putBoolean("keepScreenOn", v).apply()

    /** Brillo de la ventana 0..1, o -1 para usar el del sistema. */
    var brightness: Float
        get() = sp.getFloat("brightness", -1f)
        set(v) = sp.edit().putFloat("brightness", v).apply()

    /** Segundos medios que tardas en leer una página (para estimar tiempo restante). */
    var secondsPerPage: Float
        get() = sp.getFloat("secondsPerPage", 50f)
        set(v) = sp.edit().putFloat("secondsPerPage", v).apply()

    var librarySort: LibrarySort
        get() = enumPref("librarySort", LibrarySort.RECENT)
        set(v) = sp.edit().putString("librarySort", v.name).apply()
}
