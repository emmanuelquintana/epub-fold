package com.emmanuel.epubfold

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Tiempo de lectura por día (segundos), racha y libros terminados. */
class ReadingStats(context: Context) {

    private val file = File(context.filesDir, "stats.json")
    val days: MutableMap<String, Int> = HashMap()

    init {
        load()
    }

    fun addSeconds(seconds: Int, bookKey: String? = null) {
        if (seconds <= 0) return
        val k = dayKey(Calendar.getInstance())
        days[k] = (days[k] ?: 0) + seconds
        save()
        if (bookKey != null) {
            val y = Calendar.getInstance().get(Calendar.YEAR)
            val bk = "$y|$bookKey"
            books[bk] = (books[bk] ?: 0) + seconds
            saveBooks()
        }
    }

    /** Segundos por año y libro: "2026|clave" -> segundos. */
    val books: MutableMap<String, Int> by lazy {
        HashMap<String, Int>().also { m ->
            runCatching {
                val o = JSONObject(booksFile.readText())
                o.keys().forEach { m[it] = o.optInt(it) }
            }
        }
    }
    private val booksFile = File(context.filesDir, "stats_books.json")

    private fun saveBooks() {
        booksFile.writeText(JSONObject().apply { books.forEach { (k, v) -> put(k, v) } }.toString())
    }

    fun booksJson(): JSONObject = JSONObject().apply { books.forEach { (k, v) -> put(k, v) } }

    fun mergeBooks(o: JSONObject) {
        o.keys().forEach { k -> books[k] = maxOf(books[k] ?: 0, o.optInt(k)) }
        saveBooks()
    }

    fun secondsOn(cal: Calendar): Int = days[dayKey(cal)] ?: 0

    fun todaySeconds() = secondsOn(Calendar.getInstance())

    /** Días seguidos cumpliendo la meta diaria (hoy cuenta si ya se cumplió). */
    fun streak(goalMinutes: Int): Int {
        val goal = goalMinutes.coerceAtLeast(1) * 60
        val c = Calendar.getInstance()
        var n = 0
        if (secondsOn(c) >= goal) n++
        c.add(Calendar.DAY_OF_YEAR, -1)
        while (secondsOn(c) >= goal) {
            n++
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return n
    }

    /** Minutos de los últimos [n] días, del más antiguo a hoy. */
    fun lastDays(n: Int): List<Pair<Calendar, Int>> {
        val out = ArrayList<Pair<Calendar, Int>>()
        for (i in n - 1 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            out += c to secondsOn(c) / 60
        }
        return out
    }

    fun totalSeconds() = days.values.sum()

    fun bestStreakDays(goalMinutes: Int): Int {
        val goal = goalMinutes.coerceAtLeast(1) * 60
        val keys = days.filter { it.value >= goal }.keys.sorted()
        var best = 0
        var cur = 0
        var prev: Calendar? = null
        keys.forEach { k ->
            val c = parse(k) ?: return@forEach
            val p = prev
            cur = if (p != null && daysBetween(p, c) == 1) cur + 1 else 1
            best = maxOf(best, cur)
            prev = c
        }
        return best
    }

    fun toJson(): JSONObject = JSONObject().apply { days.forEach { (k, v) -> put(k, v) } }

    /** Mezcla con otra copia quedándose con el máximo de cada día. */
    fun mergeFrom(o: JSONObject) {
        o.keys().forEach { k -> days[k] = maxOf(days[k] ?: 0, o.optInt(k)) }
        save()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            o.keys().forEach { days[it] = o.optInt(it) }
        }
    }

    private fun save() {
        file.writeText(toJson().toString())
    }

    companion object {
        private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun dayKey(c: Calendar): String = synchronized(fmt) { fmt.format(c.time) }
        fun parse(k: String): Calendar? = runCatching {
            Calendar.getInstance().apply { time = synchronized(fmt) { fmt.parse(k)!! } }
        }.getOrNull()

        fun daysBetween(a: Calendar, b: Calendar): Int {
            val ms = b.timeInMillis - a.timeInMillis
            return Math.round(ms / 86_400_000.0).toInt()
        }
    }
}
