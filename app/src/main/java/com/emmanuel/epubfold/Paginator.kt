package com.emmanuel.epubfold

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.util.LruCache
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Una página: rango de líneas [startLine, endLine) del capítulo [chapter]. */
data class Page(val chapter: Int, val startLine: Int, val endLine: Int, val startChar: Int)

/** Posición estable de lectura que sobrevive a cambios de tamaño/fuente. */
data class ReadingPosition(val chapter: Int, val charOffset: Int)

/** Caché global de imágenes decodificadas (se comparten entre paginaciones). */
object ImageCache {
    val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

/**
 * Drawable que conoce su tamaño de antemano y decodifica la imagen sólo al dibujarse,
 * así un libro con cientos de ilustraciones no agota la memoria.
 */
class LazyImageDrawable(
    val book: EpubBook,
    val path: String,
    private val targetW: Int,
    private val targetH: Int,
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val key = "${book.hashCode()}:$path:$targetW"

    init {
        setBounds(0, 0, targetW, targetH)
    }

    private fun bitmap(): Bitmap? {
        ImageCache.cache.get(key)?.let { return it }
        val bytes = book.readBytes(path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        ImageCache.cache.put(key, bmp)
        return bmp
    }

    override fun draw(canvas: Canvas) {
        val bmp = bitmap() ?: return
        canvas.drawBitmap(bmp, null, bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Convierte cada capítulo del EPUB en un StaticLayout justificado y lo corta en páginas
 * de [contentHeight] píxeles.
 */
class Paginator(
    private val book: EpubBook,
    val paint: TextPaint,
    val contentWidth: Int,
    val contentHeight: Int,
    private val lineSpacing: Float,
    private val density: Float,
    val justify: Boolean = true,
    private val bionic: Boolean = false,
    private val bookStyles: Boolean = false,
) {
    /** Estilos del libro ya leídos, por hoja de estilos. */
    private val cssCache = HashMap<String, List<BookCss.Rule>>()
    val layouts = arrayOfNulls<StaticLayout>(book.spine.size)
    val pages = ArrayList<Page>()
    val chapterFirstPage = IntArray(book.spine.size)

    val pageCount get() = pages.size

    fun paginate(isCancelled: () -> Boolean) {
        if (book.language.isNotEmpty() && Build.VERSION.SDK_INT >= 24) {
            paint.textLocale = Locale.forLanguageTag(book.language)
        }
        for (ch in book.spine.indices) {
            if (isCancelled()) return
            chapterFirstPage[ch] = pages.size
            val text = chapterText(ch)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setLineSpacing(0f, lineSpacing)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setJustificationMode(if (justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
                .build()
            layouts[ch] = layout
            var start = 0
            val lines = layout.lineCount
            if (text.isEmpty()) continue
            while (start < lines) {
                val top = layout.getLineTop(start)
                var end = start
                while (end < lines && layout.getLineBottom(end) - top <= contentHeight) end++
                // Una línea más alta que la página (imagen grande) ocupa su propia página
                if (end == start) end = start + 1
                pages += Page(ch, start, end, layout.getLineStart(start))
                start = end
            }
        }
        if (pages.isEmpty()) pages += Page(0, 0, 0, 0)
    }

    fun pageFor(pos: ReadingPosition): Int {
        if (pages.isEmpty()) return 0
        val ch = pos.chapter.coerceIn(0, book.spine.size - 1)
        var result = chapterFirstPage[ch].coerceAtMost(pages.size - 1)
        for (i in result until pages.size) {
            val p = pages[i]
            if (p.chapter != ch || p.startChar > pos.charOffset) break
            result = i
        }
        return result
    }

    /** Rango de caracteres [inicio, fin) que ocupa la página dentro de su capítulo. */
    fun charRange(page: Int): IntRange {
        val p = pages.getOrNull(page) ?: return IntRange.EMPTY
        val next = pages.getOrNull(page + 1)
        val end = if (next != null && next.chapter == p.chapter) next.startChar
        else layouts[p.chapter]?.text?.length ?: Int.MAX_VALUE
        return p.startChar until end
    }

    /** Páginas que faltan para terminar el capítulo de [page] (sin contarla). */
    fun pagesLeftInChapter(page: Int): Int {
        val p = pages.getOrNull(page) ?: return 0
        var i = page + 1
        while (i < pages.size && pages[i].chapter == p.chapter) i++
        return i - page - 1
    }

    fun textOf(chapter: Int): CharSequence = layouts.getOrNull(chapter)?.text ?: ""

    fun positionOf(page: Int): ReadingPosition {
        val p = pages.getOrNull(page) ?: return ReadingPosition(0, 0)
        return ReadingPosition(p.chapter, p.startChar)
    }

    private fun chapterText(index: Int): Spanned {
        val path = book.spine[index]
        val dir = path.substringBeforeLast('/', "")
        val raw = book.readText(path) ?: return SpannableStringBuilder()

        val opts = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        var html = Regex("<body[^>]*>(.*)</body>", opts).find(raw)?.groupValues?.get(1)
            ?: raw.replace(Regex("<head.*?</head>", opts), "")
        if (bookStyles) {
            val rules = BookCss.rulesFor(raw, dir, { p -> book.readText(p) }, cssCache)
            if (rules.isNotEmpty()) html = BookCss.apply(html, rules)
        }
        html = html
            .replace(Regex("<script.*?</script>", opts), "")
            .replace(Regex("<style.*?</style>", opts), "")
            // Portadas SVG: <image xlink:href="..."> -> <img src="...">
            .replace(Regex("<image[^>]*?href\\s*=\\s*\"([^\"]+)\"[^>]*>", opts)) { "<img src=\"${it.groupValues[1]}\"/>" }

        val maxW = contentWidth
        val maxH = (contentHeight * 0.94f).toInt()
        val imageGetter = Html.ImageGetter { src ->
            val imgPath = EpubBook.resolve(dir, src)
            val bytes = book.readBytes(imgPath) ?: return@ImageGetter null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return@ImageGetter null
            val scale = min(min(maxW.toFloat() / o.outWidth, maxH.toFloat() / o.outHeight), density)
            LazyImageDrawable(
                book, imgPath,
                max(1, (o.outWidth * scale).roundToInt()),
                max(1, (o.outHeight * scale).roundToInt())
            )
        }

        val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, imageGetter, null)
        val sb = SpannableStringBuilder(spanned)

        // Colores fijos del libro rompen los modos claro/oscuro
        sb.getSpans(0, sb.length, ForegroundColorSpan::class.java).forEach { sb.removeSpan(it) }
        sb.getSpans(0, sb.length, BackgroundColorSpan::class.java).forEach { sb.removeSpan(it) }
        sb.getSpans(0, sb.length, AbsoluteSizeSpan::class.java).forEach { sb.removeSpan(it) }
        // Citas: sangría en vez de la barra azul por defecto
        sb.getSpans(0, sb.length, QuoteSpan::class.java).forEach {
            val s = sb.getSpanStart(it); val e = sb.getSpanEnd(it)
            sb.removeSpan(it)
            sb.setSpan(LeadingMarginSpan.Standard((24 * density).toInt()), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Imágenes centradas
        sb.getSpans(0, sb.length, ImageSpan::class.java).forEach {
            var s = sb.getSpanStart(it)
            var e = sb.getSpanEnd(it)
            while (s > 0 && sb[s - 1] != '\n') s--
            while (e < sb.length && sb[e] != '\n') e++
            if (e < sb.length) e++
            sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s, e, Spannable.SPAN_PARAGRAPH)
        }

        // Lectura biónica: el inicio de cada palabra en negrita para guiar la vista
        if (bionic) {
            TextTools.bionicRanges(sb).forEach { r ->
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), r.first, r.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // Quita espacios en blanco al inicio y al final
        var end = sb.length
        while (end > 0 && sb[end - 1].isWhitespace()) end--
        if (end < sb.length) sb.delete(end, sb.length)
        var start = 0
        while (start < sb.length && sb[start].isWhitespace()) start++
        if (start > 0) sb.delete(0, start)
        return sb
    }
}
