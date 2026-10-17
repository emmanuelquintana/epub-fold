package com.emmanuel.epubfold

import android.content.Context
import java.util.Calendar

/** Un subrayado junto al libro al que pertenece. */
data class Memory(val book: BookEntry, val highlight: Highlight)

/** Repaso de subrayados al estilo Readwise: uno distinto cada día. */
object Review {

    fun all(context: Context): List<Memory> {
        val out = ArrayList<Memory>()
        Library(context).all().forEach { b ->
            Annotations(context, b.id).highlights
                .filter { it.text.trim().length >= 12 }
                .forEach { out += Memory(b, it) }
        }
        return out
    }

    /** El recuerdo de hoy (el mismo durante todo el día); [skip] avanza a otros. */
    fun ofTheDay(context: Context, skip: Int = 0): Memory? = pick(all(context), skip)

    /** Como [ofTheDay] pero sobre una lista ya cargada. */
    fun pick(memories: List<Memory>, skip: Int = 0): Memory? {
        val list = memories.sortedBy { it.highlight.id }
        if (list.isEmpty()) return null
        val c = Calendar.getInstance()
        val day = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
        // Paso coprimo con el tamaño para recorrer todos antes de repetir
        val n = list.size
        var step = 7919 % n
        if (step == 0) step = 1
        while (gcd(step, n) != 1) step++
        val i = ((day.toLong() * step + skip.toLong() * step) % n).toInt()
        return list[i]
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
