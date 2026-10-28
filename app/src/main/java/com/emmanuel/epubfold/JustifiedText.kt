package com.emmanuel.epubfold

import android.graphics.Path
import android.text.Layout

/*
 * StaticLayout dibuja el texto justificado, pero en muchas versiones de Android mide
 * las posiciones (getPrimaryHorizontal, getSelectionPath, getOffsetForHorizontal)
 * como si no lo estuviera: la selección y los subrayados se quedan cortos y las asas
 * caen en mitad de una palabra. Aquí se reparte el espacio sobrante de cada línea
 * entre sus espacios, igual que al dibujar. Si la plataforma ya lo tiene en cuenta,
 * no queda espacio sobrante y no se corrige nada.
 */

/** La línea ocupa todo el ancho: justificada y no es la última de su párrafo. */
private fun Layout.fullWidth(line: Int, justified: Boolean): Boolean {
    if (!justified || line >= lineCount - 1) return false
    if (getParagraphDirection(line) != Layout.DIR_LEFT_TO_RIGHT) return false
    val start = getLineStart(line)
    val end = getLineEnd(line)
    if (end <= start || text[end - 1] == '\n') return false
    return (start until getLineVisibleEnd(line)).any { text[it] == ' ' }
}

/** Borde derecho real de [line]. */
private fun Layout.rightEdge(line: Int, justified: Boolean): Float =
    if (fullWidth(line, justified)) getParagraphRight(line).toFloat() else getLineRight(line)

/** Píxeles que la justificación añade a cada espacio de [line] (0 si no hace falta corregir). */
private fun Layout.extraPerSpace(line: Int, justified: Boolean): Float {
    if (!fullWidth(line, justified)) return 0f
    val start = getLineStart(line)
    val end = getLineEnd(line)
    val visibleEnd = getLineVisibleEnd(line)
    var spaces = 0
    for (i in start until visibleEnd) if (text[i] == ' ') spaces++
    val natural = if (visibleEnd < end) getPrimaryHorizontal(visibleEnd) else getLineRight(line)
    val gap = getParagraphRight(line) - natural
    return if (gap > 0.5f) gap / spaces else 0f
}

/** Posición horizontal real del carácter [offset] dentro de [line]. */
private fun Layout.xInLine(offset: Int, line: Int, extra: Float, justified: Boolean): Float {
    if (offset >= getLineEnd(line)) return rightEdge(line, justified)
    val x = getPrimaryHorizontal(offset)
    if (extra == 0f) return x
    var n = 0
    for (i in getLineStart(line) until minOf(offset, getLineVisibleEnd(line))) if (text[i] == ' ') n++
    return x + n * extra
}

fun Layout.justifiedX(offset: Int, justified: Boolean): Float {
    val line = getLineForOffset(offset)
    return xInLine(offset, line, extraPerSpace(line, justified), justified)
}

fun Layout.justifiedLineRight(line: Int, justified: Boolean): Float = rightEdge(line, justified)

fun Layout.justifiedSelectionPath(start: Int, end: Int, path: Path, justified: Boolean) {
    if (end <= start) return
    val first = getLineForOffset(start)
    if (!justified || getParagraphDirection(first) != Layout.DIR_LEFT_TO_RIGHT) {
        getSelectionPath(start, end, path)
        return
    }
    var last = getLineForOffset(end)
    if (last > first && end == getLineStart(last)) last--
    for (line in first..last) {
        val extra = extraPerSpace(line, true)
        val l = if (line == first) xInLine(start, line, extra, true) else getLineLeft(line)
        val r = if (line == last) xInLine(end, line, extra, true) else rightEdge(line, true)
        if (r > l) path.addRect(l, getLineTop(line).toFloat(), r, getLineBottom(line).toFloat(), Path.Direction.CW)
    }
}

/** Carácter más cercano a [x] en [line], con las posiciones justificadas. */
fun Layout.justifiedOffsetForX(line: Int, x: Float, justified: Boolean): Int {
    val extra = extraPerSpace(line, justified)
    if (extra == 0f) return getOffsetForHorizontal(line, x)
    val start = getLineStart(line)
    val end = getLineVisibleEnd(line)
    var best = start
    var bestD = Float.MAX_VALUE
    for (o in start..end) {
        val d = kotlin.math.abs(xInLine(o, line, extra, justified) - x)
        if (d < bestD) { bestD = d; best = o }
    }
    return best
}
