package com.emmanuel.epubfold

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Formatos de libro admitidos. Los CBR se convierten a CBZ al importarlos. */
enum class BookFormat(val ext: String) {
    EPUB("epub"), PDF("pdf"), CBZ("cbz");

    /** PDF y cómics se leen como imágenes de página, no como texto. */
    val isImage get() = this != EPUB

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name.equals(name, true) } ?: EPUB
    }
}

/** Metadatos leídos al importar un libro de imágenes. */
data class ImageMeta(val title: String?, val author: String, val series: String, val seriesIndex: Float)

object Formats {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun isImageName(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT &&
        !name.substringAfterLast('/').startsWith(".") && !name.startsWith("__MACOSX")

    /** Tipo de archivo según sus primeros bytes. null si no es un formato conocido. */
    fun detect(file: File): String? {
        val head = ByteArray(8)
        val n = file.inputStream().use { it.read(head) }
        if (n < 4) return null
        val s = String(head, 0, n, Charsets.ISO_8859_1)
        return when {
            s.startsWith("%PDF") -> "pdf"
            s.startsWith("Rar!") -> "rar"
            s.startsWith("PK") -> runCatching {
                ZipFile(file).use { z ->
                    when {
                        z.getEntry("META-INF/container.xml") != null -> "epub"
                        z.entries().asSequence().any { !it.isDirectory && isImageName(it.name) } -> "cbz"
                        else -> null
                    }
                }
            }.getOrNull()
            else -> null
        }
    }

    /** Convierte un CBR (RAR) en CBZ copiando sus imágenes y ComicInfo.xml. */
    fun rarToCbz(rar: File, out: File) {
        com.github.junrar.Archive(rar).use { archive ->
            if (archive.isEncrypted) throw IllegalArgumentException("El CBR está protegido con contraseña")
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                var count = 0
                for (h in archive.fileHeaders) {
                    if (h.isDirectory) continue
                    val name = h.fileName.replace('\\', '/')
                    if (!isImageName(name) && !name.endsWith("ComicInfo.xml", true)) continue
                    // STORED: las imágenes ya están comprimidas
                    zip.putNextEntry(ZipEntry(name))
                    archive.extractFile(h, zip)
                    zip.closeEntry()
                    if (isImageName(name)) count++
                }
                if (count == 0) throw IllegalArgumentException("El CBR no contiene imágenes")
            }
        }
    }

    /** Lee título, guionista y serie de ComicInfo.xml (formato de ComicRack) si existe. */
    fun comicInfo(cbz: File): ImageMeta? = runCatching {
        ZipFile(cbz).use { z ->
            val e = z.entries().asSequence().firstOrNull { it.name.substringAfterLast('/').equals("ComicInfo.xml", true) }
                ?: return null
            val p = Xml.newPullParser()
            p.setInput(z.getInputStream(e).reader())
            val values = HashMap<String, String>()
            var tag: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> tag = p.name
                    XmlPullParser.TEXT -> tag?.let { t -> p.text.trim().takeIf { it.isNotEmpty() }?.let { values.putIfAbsent(t, it) } }
                    XmlPullParser.END_TAG -> tag = null
                }
                ev = p.next()
            }
            val series = values["Series"].orEmpty()
            val number = values["Number"]?.replace(',', '.')?.toFloatOrNull() ?: 0f
            val title = values["Title"] ?: if (series.isNotEmpty() && number > 0f) "$series ${fmtIndex(number)}" else null
            ImageMeta(title, values["Writer"].orEmpty(), series, number)
        }
    }.getOrNull()

    fun fmtIndex(i: Float) = if (i == i.toInt().toFloat()) i.toInt().toString() else i.toString()

    fun open(format: BookFormat, file: File): ImageBook = when (format) {
        BookFormat.PDF -> PdfBook(file)
        BookFormat.CBZ -> ComicBook(file)
        BookFormat.EPUB -> throw IllegalArgumentException("EPUB no es un libro de imágenes")
    }
}

/** Libro formado por páginas que se dibujan como imágenes (PDF, cómic). */
interface ImageBook : Closeable {
    val pageCount: Int

    /** Proporción ancho/alto de la página (para colocar la doble página sin renderizar). */
    fun aspect(index: Int): Float

    /** Dibuja la página [index] para que quepa en [maxW]×[maxH] píxeles. */
    fun render(index: Int, maxW: Int, maxH: Int): Bitmap?
}

class PdfBook(file: File) : ImageBook {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val pdf = try {
        PdfRenderer(fd)
    } catch (e: Exception) {
        fd.close()
        throw IllegalArgumentException("No se pudo abrir el PDF (¿protegido con contraseña?)")
    }
    private val aspects = HashMap<Int, Float>()

    override val pageCount: Int get() = pdf.pageCount

    // PdfRenderer sólo permite una página abierta a la vez
    @Synchronized
    override fun aspect(index: Int): Float = aspects.getOrPut(index) {
        pdf.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }

    @Synchronized
    override fun render(index: Int, maxW: Int, maxH: Int): Bitmap? {
        if (index !in 0 until pageCount) return null
        return pdf.openPage(index).use { page ->
            val scale = minOf(maxW.toFloat() / page.width, maxH.toFloat() / page.height)
            val w = (page.width * scale).toInt().coerceIn(1, 4096)
            val h = (page.height * scale).toInt().coerceIn(1, 4096)
            aspects[index] = page.width.toFloat() / page.height.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    override fun close() {
        pdf.close()
        fd.close()
    }
}

class ComicBook(file: File) : ImageBook {
    private val zip = ZipFile(file)
    private val pages: List<ZipEntry> = zip.entries().asSequence()
        .filter { !it.isDirectory && Formats.isImageName(it.name) }
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        .toList()
    private val aspects = HashMap<Int, Float>()

    init {
        if (pages.isEmpty()) {
            zip.close()
            throw IllegalArgumentException("El cómic no contiene imágenes")
        }
    }

    override val pageCount: Int get() = pages.size

    @Synchronized
    override fun aspect(index: Int): Float = aspects.getOrPut(index) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(pages[index]).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 0.7f
    }

    @Synchronized
    override fun render(index: Int, maxW: Int, maxH: Int): Bitmap? {
        val e = pages.getOrNull(index) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        aspects[index] = o.outWidth.toFloat() / o.outHeight.coerceAtLeast(1)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) sample *= 2
        return zip.getInputStream(e).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    override fun close() = zip.close()

    companion object {
        /** Orden "natural": página2 antes que página10. */
        fun naturalCompare(a: String, b: String): Int {
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]
                val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 }
                        ?: x.trimStart('0').compareTo(y.trimStart('0'))
                } else x.compareTo(y)
                if (c != 0) return c
            }
            return ra.size.compareTo(rb.size)
        }
    }
}
