package com.emmanuel.epubfold

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Entrada de la tabla de contenidos. */
data class TocEntry(val title: String, val spineIndex: Int)

/**
 * Lector mínimo de EPUB 2/3: container.xml -> OPF -> manifest/spine/TOC.
 */
class EpubBook(file: File) : Closeable {

    private val zip = ZipFile(file)

    var title: String = file.nameWithoutExtension
        private set
    var author: String = ""
        private set
    var language: String = ""
        private set
    var coverPath: String? = null
        private set
    /** Serie (calibre:series o belongs-to-collection de EPUB3) y número dentro de ella. */
    var series: String = ""
        private set
    var seriesIndex: Float = 0f
        private set

    /** Rutas completas (dentro del zip) de los documentos del spine, en orden. */
    val spine = ArrayList<String>()
    val toc = ArrayList<TocEntry>()
    private val chapterTitles = HashMap<String, String>()
    private var titleSet = false

    init {
        parse()
    }

    fun readBytes(path: String): ByteArray? {
        val entry = zip.getEntry(path) ?: zip.getEntry(path.removePrefix("/")) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun readText(path: String): String? = readBytes(path)?.let { decodeText(it) }

    /** Título visible para el capítulo [index] (hereda el anterior si no tiene propio). */
    fun chapterTitle(index: Int): String {
        var i = index
        while (i >= 0) {
            chapterTitles[spine[i]]?.let { return it }
            i--
        }
        return title
    }

    override fun close() = zip.close()

    private fun parse() {
        val container = readText("META-INF/container.xml")
            ?: throw IllegalArgumentException("No es un EPUB válido (falta container.xml)")
        val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("No es un EPUB válido (sin OPF)")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = readText(opfPath) ?: throw IllegalArgumentException("OPF no encontrado")

        data class Item(val id: String, val href: String, val type: String, val props: String)

        val items = LinkedHashMap<String, Item>()
        val spineIds = ArrayList<String>()
        var tocId: String? = null
        var coverId: String? = null

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(opf.reader())
        var textTarget: String? = null
        try {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':').lowercase()
                    when (name) {
                        "title", "creator", "language" -> textTarget = name
                        "meta" -> {
                            val metaName = parser.attr("name")
                            when {
                                metaName == "cover" -> coverId = parser.attr("content")
                                metaName == "calibre:series" -> series = parser.attr("content")?.trim().orEmpty()
                                metaName == "calibre:series_index" ->
                                    seriesIndex = parser.attr("content")?.toFloatOrNull() ?: seriesIndex
                                parser.attr("property") == "belongs-to-collection" && series.isEmpty() ->
                                    textTarget = "collection"
                                parser.attr("property") == "group-position" && seriesIndex == 0f ->
                                    textTarget = "position"
                            }
                        }
                        "item" -> {
                            val id = parser.attr("id") ?: ""
                            val href = parser.attr("href") ?: ""
                            items[id] = Item(
                                id,
                                resolve(opfDir, href),
                                parser.attr("media-type") ?: "",
                                parser.attr("properties") ?: ""
                            )
                        }
                        "spine" -> tocId = parser.attr("toc")
                        "itemref" -> parser.attr("idref")?.let { spineIds += it }
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text.trim()
                    if (t.isNotEmpty()) when (textTarget) {
                        "title" -> if (!titleSet) { title = t; titleSet = true }
                        "creator" -> if (author.isEmpty()) author = t
                        "language" -> if (language.isEmpty()) language = t
                        "collection" -> series = t
                        "position" -> seriesIndex = t.toFloatOrNull() ?: 0f
                    }
                }
                XmlPullParser.END_TAG -> textTarget = null
            }
            event = parser.next()
        }
        } catch (e: Exception) {
            // OPF mal formado: usamos lo que se haya podido leer
        }

        spineIds.mapNotNullTo(spine) { items[it]?.href }
        if (spine.isEmpty()) throw IllegalArgumentException("El EPUB no tiene contenido")

        coverPath = items.values.firstOrNull { it.props.contains("cover-image") }?.href
            ?: coverId?.let { items[it] }?.takeIf { it.type.startsWith("image") }?.href
            ?: items.values.firstOrNull {
                it.type.startsWith("image") && (it.id.contains("cover", true) || it.href.contains("cover", true))
            }?.href

        // Tabla de contenidos: EPUB3 nav o EPUB2 NCX
        val nav = items.values.firstOrNull { it.props.split(' ').contains("nav") }
        val ncx = tocId?.let { items[it] }
            ?: items.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
        runCatching {
            if (nav != null) parseNav(nav.href) else if (ncx != null) parseNcx(ncx.href)
        }
        if (toc.isEmpty() && ncx != null && nav != null) runCatching { parseNcx(ncx.href) }
        if (toc.isEmpty()) {
            spine.indices.forEach { toc += TocEntry("Sección ${it + 1}", it) }
        }
    }

    private fun addToc(title: String, path: String) {
        val clean = stripTags(title).trim()
        if (clean.isEmpty()) return
        val idx = spine.indexOf(path)
        if (idx < 0) return
        chapterTitles.putIfAbsent(path, clean)
        toc += TocEntry(clean, idx)
    }

    private fun parseNav(path: String) {
        val html = readText(path) ?: return
        val dir = path.substringBeforeLast('/', "")
        val navBlock = Regex(
            "<nav[^>]*toc[^>]*>(.*?)</nav>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html)?.groupValues?.get(1) ?: return
        Regex("<a[^>]*href\\s*=\\s*\"([^\"]*)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(navBlock).forEach { m ->
                addToc(m.groupValues[2], resolve(dir, m.groupValues[1]))
            }
    }

    private fun parseNcx(path: String) {
        val xml = readText(path) ?: return
        val dir = path.substringBeforeLast('/', "")
        Regex(
            "<navLabel[^>]*>\\s*<text[^>]*>(.*?)</text>\\s*</navLabel>\\s*<content[^>]*src\\s*=\\s*\"([^\"]*)\"",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(xml).forEach { m ->
            addToc(m.groupValues[1], resolve(dir, m.groupValues[2]))
        }
    }

    companion object {
        /** Resuelve [href] relativo a [baseDir], quitando fragmentos y normalizando "..". */
        fun resolve(baseDir: String, href: String): String {
            val clean = Uri.decode(href.substringBefore('#').substringBefore('?'))
            if (clean.isEmpty()) return baseDir
            val parts = ArrayList<String>()
            if (!clean.startsWith("/")) {
                baseDir.split('/').filter { it.isNotEmpty() }.forEach { parts += it }
            }
            clean.split('/').forEach { seg ->
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts += seg
                }
            }
            return parts.joinToString("/")
        }

        fun stripTags(s: String): String =
            s.replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")

        private fun decodeText(bytes: ByteArray): String {
            // BOM UTF-16
            if (bytes.size >= 2) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) {
                    return String(bytes, Charsets.UTF_16)
                }
            }
            return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        }

        private fun XmlPullParser.attr(name: String): String? {
            for (i in 0 until attributeCount) {
                if (getAttributeName(i).substringAfter(':') == name) return getAttributeValue(i)
            }
            return null
        }
    }
}
