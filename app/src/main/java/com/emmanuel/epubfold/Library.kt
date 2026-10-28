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
    /** "" normal, "want" = Quiero leer. */
    val shelf: String = "",
    /** Momento en que se terminó (0 = no terminado). */
    val finishedAt: Long = 0L,
    /** Última vez que cambió la posición (para sincronizar). */
    val updatedAt: Long = 0L,
    val format: BookFormat = BookFormat.EPUB,
    /** Serie a la que pertenece ("" si ninguna) y número dentro de ella. */
    val series: String = "",
    val seriesIndex: Float = 0f,
    /** Ya se buscó portada en internet (para no repetir). */
    val coverLookedUp: Boolean = false,
    /** Estanterías propias (etiquetas) además de Leyendo / Quiero leer / Terminados. */
    val tags: List<String> = emptyList(),
) {
    /** Identificador estable entre dispositivos (mismo libro = mismo título y autor). */
    val key: String get() = bookKey(title, author)

    val collection: Shelf
        get() = when {
            finishedAt > 0L || progress >= 0.999f -> Shelf.FINISHED
            shelf == "want" -> Shelf.WANT
            progress > 0f -> Shelf.READING
            else -> Shelf.NEW
        }
}

enum class Shelf(val label: String) {
    READING("Leyendo"),
    WANT("Quiero leer"),
    FINISHED("Terminados"),
    NEW("Sin empezar");
}

fun bookKey(title: String, author: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val bytes = md.digest("${title.trim().lowercase()}|${author.trim().lowercase()}".toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }.take(24)
}

/** Biblioteca local: los EPUB se copian a almacenamiento privado y se indexan en JSON. */
class Library(private val context: Context) {

    private val indexFile = File(context.filesDir, "library.json")
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    fun all(): List<BookEntry> = load().sortedByDescending { it.lastOpened }

    fun get(id: String): BookEntry? = load().firstOrNull { it.id == id }

    fun findByKey(key: String): BookEntry? = load().firstOrNull { it.key == key }

    /** Copia el libro del [uri] y lo añade. Se ejecuta en segundo plano. */
    fun import(uri: Uri): BookEntry {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("No se pudo leer el archivo")
        return input.use { importStream(it, name) }
    }

    /**
     * Añade un libro (EPUB, PDF, CBZ o CBR) desde un flujo. [fileName] sirve de título
     * para los formatos sin metadatos. Si el libro ya existe, devuelve el existente.
     */
    fun importStream(input: java.io.InputStream, fileName: String? = null): BookEntry {
        val id = UUID.randomUUID().toString()
        val raw = File(booksDir, "$id.tmp")
        raw.outputStream().use { input.copyTo(it) }
        val kind = runCatching { Formats.detect(raw) }.getOrNull()
        if (kind == null) {
            raw.delete()
            throw IllegalArgumentException("Formato no compatible (usa EPUB, PDF, CBZ o CBR)")
        }
        if (kind != "epub") return importImageBook(id, raw, kind, fileName)
        val dest = File(booksDir, "$id.epub")
        raw.renameTo(dest)

        return try {
            EpubBook(dest).use { book ->
                findByKey(bookKey(book.title, book.author))?.let { existing ->
                    dest.delete()
                    return existing
                }
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
                    System.currentTimeMillis(), 0, 0, series = book.series, seriesIndex = book.seriesIndex)
                save(load() + entry)
                entry
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    private fun importImageBook(id: String, raw: File, kind: String, fileName: String?): BookEntry {
        val dest = File(booksDir, "$id.${if (kind == "pdf") "pdf" else "cbz"}")
        try {
            if (kind == "rar") {
                Formats.rarToCbz(raw, dest)
                raw.delete()
            } else {
                raw.renameTo(dest)
            }
            val format = if (kind == "pdf") BookFormat.PDF else BookFormat.CBZ
            val meta = if (format == BookFormat.CBZ) Formats.comicInfo(dest) else null
            val baseName = fileName?.substringBeforeLast('.')?.replace('_', ' ')?.trim()?.ifEmpty { null }
            val title = meta?.title ?: baseName ?: "Sin título"
            val author = meta?.author.orEmpty()
            findByKey(bookKey(title, author))?.let { existing ->
                dest.delete()
                return existing
            }
            val cover = Formats.open(format, dest).use { b ->
                b.render(0, 400, 600)?.let { bmp -> saveCover(id, bmp) }
            }
            val entry = BookEntry(
                id, title, author, dest.absolutePath, cover, 0f, System.currentTimeMillis(), 0, 0,
                format = format, series = meta?.series.orEmpty(), seriesIndex = meta?.seriesIndex ?: 0f,
                coverLookedUp = true,
            )
            save(load() + entry)
            return entry
        } catch (e: Exception) {
            raw.delete()
            dest.delete()
            throw e
        }
    }

    private fun saveCover(id: String, bmp: Bitmap): String {
        val f = File(coversDir, "$id.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return f.absolutePath
    }

    /** Guarda como portada una imagen descargada (p. ej. de Open Library). */
    fun setCover(id: String, bytes: ByteArray): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        // Open Library devuelve una imagen de 1×1 cuando no tiene portada
        if (o.outWidth < 40 || o.outHeight < 40) return false
        var sample = 1
        while (o.outWidth / (sample * 2) >= 400) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        // Nombre nuevo para que la caché de portadas no muestre la anterior
        val f = File(coversDir, "$id-${System.currentTimeMillis() % 100000}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val old = get(id)?.coverPath
        update(id) { it.copy(coverPath = f.absolutePath, coverLookedUp = true) }
        old?.let { if (it != f.absolutePath) File(it).delete() }
        return true
    }

    fun markCoverLookedUp(id: String) = update(id) { it.copy(coverLookedUp = true) }

    /** El siguiente libro de la misma serie que haya en la biblioteca (sin terminar). */
    fun nextInSeries(entry: BookEntry): BookEntry? {
        if (entry.series.isBlank()) return null
        return load().filter {
            it.id != entry.id && it.series.equals(entry.series, true) && it.seriesIndex > entry.seriesIndex &&
                it.collection != Shelf.FINISHED
        }.minByOrNull { it.seriesIndex }
    }

    /** Estanterías propias usadas en algún libro, por orden alfabético. */
    fun allTags(): List<String> = load().flatMap { it.tags }.distinct().sortedBy { it.lowercase() }

    fun setTags(id: String, tags: List<String>) =
        update(id) { it.copy(tags = tags.map { t -> t.trim() }.filter { t -> t.isNotEmpty() }.distinct(), updatedAt = System.currentTimeMillis()) }

    fun setSeries(id: String, series: String, index: Float) =
        update(id) { it.copy(series = series.trim(), seriesIndex = index) }

    fun updatePosition(id: String, chapter: Int, charOffset: Int, progress: Float) {
        val now = System.currentTimeMillis()
        update(id) {
            it.copy(
                chapter = chapter, charOffset = charOffset, progress = progress,
                lastOpened = now, updatedAt = now,
                finishedAt = if (progress >= 0.999f && it.finishedAt == 0L) now else it.finishedAt,
            )
        }
    }

    fun update(id: String, f: (BookEntry) -> BookEntry) {
        save(load().map { if (it.id == id) f(it) else it })
    }

    fun setShelf(id: String, shelf: String) = update(id) { it.copy(shelf = shelf, updatedAt = System.currentTimeMillis()) }

    fun markFinished(id: String) = update(id) {
        val now = System.currentTimeMillis()
        it.copy(finishedAt = now, shelf = "", updatedAt = now)
    }

    fun resetProgress(id: String) {
        update(id) {
            it.copy(chapter = 0, charOffset = 0, progress = 0f, finishedAt = 0L, updatedAt = System.currentTimeMillis())
        }
        Annotations(context, id).clear()
    }

    fun delete(id: String) {
        Annotations(context, id).clear()
        java.io.File(java.io.File(context.filesDir, "sketches"), "$id.json").delete()
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
                    o.optInt("chapter"), o.optInt("charOffset"),
                    o.optString("shelf"), o.optLong("finishedAt"), o.optLong("updatedAt"),
                    BookFormat.of(o.optString("format")), o.optString("series"),
                    o.optDouble("seriesIndex", 0.0).toFloat(), o.optBoolean("coverLookedUp"),
                    o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList(),
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
                put("shelf", it.shelf); put("finishedAt", it.finishedAt); put("updatedAt", it.updatedAt)
                put("format", it.format.name); put("series", it.series)
                put("seriesIndex", it.seriesIndex.toDouble()); put("coverLookedUp", it.coverLookedUp)
                put("tags", JSONArray(it.tags))
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
            if (isDarkTheme(v)) lastDark = v else lastLight = v
        }

    var lastLight: ReaderTheme
        get() = enumPref("lastLight", ReaderTheme.ORIGINAL)
        set(v) = sp.edit().putString("lastLight", v.name).apply()

    var lastDark: ReaderTheme
        get() = enumPref("lastDark", ReaderTheme.NOCHE)
        set(v) = sp.edit().putString("lastDark", v.name).apply()

    /** Apariencia de la biblioteca y del resto de pantallas fuera del lector. */
    var appMode: AppMode
        get() = enumPref("appMode", AppMode.READER)
        set(v) = sp.edit().putString("appMode", v.name).apply()

    var accent: AppAccent
        get() = enumPref("appAccent", AppAccent.NARANJA)
        set(v) = sp.edit().putString("appAccent", v.name).apply()

    var fontSizeSp: Float
        get() = sp.getFloat("fontSize", 19f)
        set(v) = sp.edit().putFloat("fontSize", v).apply()

    var font: ReaderFont
        get() = enumPref("font", ReaderFont.SERIF)
        set(v) = sp.edit().putString("font", v.name).apply()

    var customPaper: Int
        get() = sp.getInt("customPaper", 0xFFFDF6E3.toInt())
        set(v) = sp.edit().putInt("customPaper", v).apply()

    var customText: Int
        get() = sp.getInt("customText", 0xFF073642.toInt())
        set(v) = sp.edit().putInt("customText", v).apply()

    /** Textura de papel en todos los temas. */
    var texture: Boolean
        get() = sp.getBoolean("texture", false)
        set(v) = sp.edit().putBoolean("texture", v).apply()

    var autoTheme: AutoTheme
        get() = enumPref("autoTheme", AutoTheme.OFF)
        set(v) = sp.edit().putString("autoTheme", v.name).apply()

    /** Inicio y fin de la noche en minutos desde medianoche (tema automático por horario). */
    var nightStartMin: Int
        get() = sp.getInt("nightStart", 21 * 60)
        set(v) = sp.edit().putInt("nightStart", v).apply()

    var nightEndMin: Int
        get() = sp.getInt("nightEnd", 7 * 60)
        set(v) = sp.edit().putInt("nightEnd", v).apply()

    var ttsRate: Float
        get() = sp.getFloat("ttsRate", 1f)
        set(v) = sp.edit().putFloat("ttsRate", v).apply()

    var sidePanel: Boolean
        get() = sp.getBoolean("sidePanel", false)
        set(v) = sp.edit().putBoolean("sidePanel", v).apply()

    var translateTarget: String
        get() = sp.getString("translateTarget", null) ?: java.util.Locale.getDefault().language
        set(v) = sp.edit().putString("translateTarget", v).apply()

    var dailyGoalMin: Int
        get() = sp.getInt("dailyGoal", 20)
        set(v) = sp.edit().putInt("dailyGoal", v).apply()

    var yearlyGoalBooks: Int
        get() = sp.getInt("yearlyGoal", 12)
        set(v) = sp.edit().putInt("yearlyGoal", v).apply()

    var libraryFilter: Int
        get() = sp.getInt("libraryFilter", 0)
        set(v) = sp.edit().putInt("libraryFilter", v).apply()

    /** Estantería mostrada: "all", "reading", "want", "finished" o "tag:Nombre". */
    var libraryShelf: String
        get() = sp.getString("libraryShelf", null) ?: when (libraryFilter) {
            1 -> "reading"; 2 -> "want"; 3 -> "finished"; else -> "all"
        }
        set(v) = sp.edit().putString("libraryShelf", v).apply()

    var reminderOn: Boolean
        get() = sp.getBoolean("reminderOn", false)
        set(v) = sp.edit().putBoolean("reminderOn", v).apply()

    /** Hora del recordatorio en minutos desde medianoche. */
    var reminderMin: Int
        get() = sp.getInt("reminderMin", 21 * 60)
        set(v) = sp.edit().putInt("reminderMin", v).apply()

    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(v) = sp.edit().putLong("lastSync", v).apply()

    /** Exporta los ajustes (para la copia de seguridad). */
    fun toJson(): JSONObject = JSONObject().apply {
        sp.all.forEach { (k, v) ->
            when (v) {
                is Boolean, is Int, is Long, is Float, is String -> put(k, JSONObject().put("t", v.javaClass.simpleName).put("v", v))
            }
        }
    }

    fun fromJson(o: JSONObject) {
        val e = sp.edit()
        o.keys().forEach { k ->
            val w = o.optJSONObject(k) ?: return@forEach
            when (w.optString("t")) {
                "Boolean" -> e.putBoolean(k, w.optBoolean("v"))
                "Integer" -> e.putInt(k, w.optInt("v"))
                "Long" -> e.putLong(k, w.optLong("v"))
                "Float" -> e.putFloat(k, w.optDouble("v").toFloat())
                "String" -> e.putString(k, w.optString("v"))
            }
        }
        e.apply()
    }

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

    /** Palabras por minuto de la lectura rápida. */
    var speedWpm: Int
        get() = sp.getInt("speedWpm", 300)
        set(v) = sp.edit().putInt("speedWpm", v).apply()

    /** Desplazamiento continuo en lugar de páginas. */
    var scrollMode: Boolean
        get() = sp.getBoolean("scrollMode", false)
        set(v) = sp.edit().putBoolean("scrollMode", v).apply()

    var handsFree: HandsFreeMode
        get() = enumPref("handsFree", HandsFreeMode.OFF)
        set(v) = sp.edit().putString("handsFree", v.name).apply()

    /** Voz elegida para leer en voz alta (null = la del sistema). */
    var ttsVoice: String?
        get() = sp.getString("ttsVoice", null)
        set(v) = sp.edit().putString("ttsVoice", v).apply()

    /** Regla de lectura visible. */
    var ruler: Boolean
        get() = sp.getBoolean("ruler", false)
        set(v) = sp.edit().putBoolean("ruler", v).apply()

    var librarySort: LibrarySort
        get() = enumPref("librarySort", LibrarySort.RECENT)
        set(v) = sp.edit().putString("librarySort", v.name).apply()
}
