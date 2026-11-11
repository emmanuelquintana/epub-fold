package com.emmanuel.epubfold

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Análisis de páginas de cómic sobre una imagen reducida en escala de grises
 * (luminancia 0..255, fila a fila). Las funciones de cálculo no dependen de Android
 * para poder probarlas en el ordenador.
 */
object ImageAnalysis {

    /** Imagen reducida a [maxW] píxeles de ancho como luminancias. */
    class Gray(val lum: IntArray, val w: Int, val h: Int)

    fun gray(bmp: Bitmap, maxW: Int = 320): Gray {
        val scale = min(1f, maxW.toFloat() / bmp.width)
        val w = max(1, (bmp.width * scale).toInt())
        val h = max(1, (bmp.height * scale).toInt())
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return Gray(px, w, h)
    }

    /** Color de fondo: mediana de los píxeles del borde (blanco en casi todos los cómics, negro en algunos). */
    fun backgroundLevel(lum: IntArray, w: Int, h: Int): Int {
        val border = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) { border += lum[x]; border += lum[(h - 1) * w + x] }
        for (y in 0 until h) { border += lum[y * w]; border += lum[y * w + w - 1] }
        border.sort()
        return border[border.size / 2]
    }

    private fun rowIsBackground(lum: IntArray, w: Int, y: Int, x0: Int, x1: Int, bg: Int, tol: Int, noise: Float): Boolean {
        var off = 0
        val limit = ((x1 - x0) * noise).toInt()
        for (x in x0 until x1) if (abs(lum[y * w + x] - bg) > tol && ++off > limit) return false
        return true
    }

    private fun colIsBackground(lum: IntArray, w: Int, x: Int, y0: Int, y1: Int, bg: Int, tol: Int, noise: Float): Boolean {
        var off = 0
        val limit = ((y1 - y0) * noise).toInt()
        for (y in y0 until y1) if (abs(lum[y * w + x] - bg) > tol && ++off > limit) return false
        return true
    }

    /**
     * Rectángulo con contenido (sin márgenes de fondo): [izq, arriba, der, abajo) en píxeles,
     * o null si la página está vacía.
     */
    fun contentBounds(lum: IntArray, w: Int, h: Int, tol: Int = 28, noise: Float = 0.01f): IntArray? {
        if (w < 4 || h < 4) return null
        val bg = backgroundLevel(lum, w, h)
        var top = 0
        while (top < h && rowIsBackground(lum, w, top, 0, w, bg, tol, noise)) top++
        if (top >= h) return null
        var bottom = h
        while (bottom > top && rowIsBackground(lum, w, bottom - 1, 0, w, bg, tol, noise)) bottom--
        var left = 0
        while (left < w && colIsBackground(lum, w, left, top, bottom, bg, tol, noise)) left++
        var right = w
        while (right > left && colIsBackground(lum, w, right - 1, top, bottom, bg, tol, noise)) right--
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * Viñetas de la página en orden de lectura, como rectángulos normalizados
     * [izq, arriba, der, abajo] entre 0 y 1. Corta la página por las calles (franjas de
     * fondo) de arriba abajo y luego de lado a lado, varias veces. En manga ([rtl]) las
     * columnas se leen de derecha a izquierda. Si no encuentra viñetas devuelve la página entera.
     */
    fun panels(lum: IntArray, w: Int, h: Int, rtl: Boolean = false): List<FloatArray> {
        val whole = listOf(floatArrayOf(0f, 0f, 1f, 1f))
        if (w < 8 || h < 8) return whole
        val bg = backgroundLevel(lum, w, h)
        val tol = 30
        val noise = 0.02f
        val minGap = max(2, (min(w, h) * 0.008f).toInt())
        val out = ArrayList<IntArray>()

        fun trim(r: IntArray): IntArray? {
            var (l, t, rr, b) = r.toList()
            while (t < b && rowIsBackground(lum, w, t, l, rr, bg, tol, noise)) t++
            while (b > t && rowIsBackground(lum, w, b - 1, l, rr, bg, tol, noise)) b--
            while (l < rr && colIsBackground(lum, w, l, t, b, bg, tol, noise)) l++
            while (rr > l && colIsBackground(lum, w, rr - 1, t, b, bg, tol, noise)) rr--
            return if (rr - l < 2 || b - t < 2) null else intArrayOf(l, t, rr, b)
        }

        fun split(r: IntArray, horizontal: Boolean): List<IntArray> {
            val (l, t, rr, b) = r.toList()
            val parts = ArrayList<IntArray>()
            var start = -1
            var gap = 0
            val from = if (horizontal) t else l
            val to = if (horizontal) b else rr
            for (i in from until to) {
                val isBg = if (horizontal) rowIsBackground(lum, w, i, l, rr, bg, tol, noise)
                else colIsBackground(lum, w, i, t, b, bg, tol, noise)
                if (!isBg) {
                    if (start < 0) start = i
                    gap = 0
                } else if (start >= 0) {
                    gap++
                    if (gap >= minGap) {
                        val end = i - gap + 1
                        parts += if (horizontal) intArrayOf(l, start, rr, end) else intArrayOf(start, t, end, b)
                        start = -1
                        gap = 0
                    }
                }
            }
            if (start >= 0) parts += if (horizontal) intArrayOf(l, start, rr, to) else intArrayOf(start, t, to, b)
            return parts
        }

        fun cut(r: IntArray, depth: Int, horizontal: Boolean) {
            val tr = trim(r) ?: return
            if (depth >= 5) { out += tr; return }
            var parts = split(tr, horizontal)
            var dir = horizontal
            if (parts.size <= 1) {
                parts = split(tr, !horizontal)
                dir = !horizontal
            }
            if (parts.size <= 1) { out += tr; return }
            val ordered = if (!dir && rtl) parts.reversed() else parts
            ordered.forEach { cut(it, depth + 1, !dir) }
        }

        cut(intArrayOf(0, 0, w, h), 0, true)
        // Fuera trozos minúsculos (números de página, firmas, ruido)
        val area = (w * h).toFloat()
        val panels = out.filter { (it[2] - it[0]) * (it[3] - it[1]) >= area * 0.015f &&
            it[2] - it[0] >= w * 0.08f && it[3] - it[1] >= h * 0.04f }
        if (panels.size <= 1) return whole
        return panels.map { floatArrayOf(it[0].toFloat() / w, it[1].toFloat() / h, it[2].toFloat() / w, it[3].toFloat() / h) }
    }
}

/** Utilidades de texto puras (probadas en el ordenador). */
object TextTools {

    /**
     * Lectura biónica: rango de cada palabra que va en negrita (su comienzo).
     * Palabras de 1 letra no se marcan; hasta 3 letras, una; luego cerca de la mitad.
     */
    fun bionicRanges(text: CharSequence): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (!text[i].isLetter()) { i++; continue }
            val start = i
            while (i < n && (text[i].isLetter() || text[i] == '\'' || text[i] == '’')) i++
            val len = i - start
            if (len >= 2) {
                val k = if (len <= 3) 1 else (len * 0.45f + 0.5f).toInt().coerceIn(1, len - 1)
                out += start until start + k
            }
        }
        return out
    }

    private val TAG = Regex("<[^>]+>")
    private val BLOCKS = setOf("p", "li", "aside", "div", "dd", "dt", "section", "td", "blockquote", "note", "footnote")

    /**
     * Texto del elemento con id="[id]" en [html] (para mostrar una nota al pie). Si el id
     * está en un elemento en línea (un enlace de vuelta, un span), devuelve el párrafo que lo contiene.
     */
    fun elementText(html: String, id: String): String? {
        val m = Regex("<([a-zA-Z0-9:]+)[^>]*\\s(?:id|name)\\s*=\\s*[\"']${Regex.escape(id)}[\"'][^>]*>").find(html) ?: return null
        var tag = m.groupValues[1].lowercase().substringAfter(':')
        var openStart = m.range.first
        var contentStart = m.range.last + 1
        if (tag !in BLOCKS) {
            // Buscar el bloque que contiene al elemento
            val before = html.substring(0, m.range.first)
            val block = Regex("<(${BLOCKS.joinToString("|")})(\\s[^>]*)?>", RegexOption.IGNORE_CASE).findAll(before).lastOrNull()
            if (block != null) {
                tag = block.groupValues[1].lowercase()
                openStart = block.range.first
                contentStart = block.range.last + 1
            } else {
                // Sin bloque: el texto hasta el siguiente cierre de párrafo
                val end = Regex("</(p|li|div|aside)>", RegexOption.IGNORE_CASE).find(html, contentStart)?.range?.first ?: html.length
                return clean(html.substring(contentStart, end))
            }
        }
        // Cierre correspondiente teniendo en cuenta anidamiento del mismo tipo
        val tokens = Regex("<(/?)$tag(\\s[^>]*)?>", RegexOption.IGNORE_CASE).findAll(html, openStart)
        var depth = 0
        var end = html.length
        for (t in tokens) {
            if (t.groupValues[1].isEmpty()) depth++ else depth--
            if (depth == 0) { end = t.range.first; break }
        }
        return clean(html.substring(contentStart, end.coerceAtLeast(contentStart))).ifEmpty { null }
    }

    private fun clean(s: String) = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(TAG, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n\\s*"), "\n")
        .trim()
}
