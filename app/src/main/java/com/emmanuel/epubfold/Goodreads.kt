package com.emmanuel.epubfold

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Un libro leído fuera de la app (importado del historial de Goodreads). */
data class ReadRecord(val title: String, val author: String, val readAt: Long) {
    /** Misma clave que la biblioteca, sin el «(Serie, #2)» que Goodreads añade al título. */
    val key: String get() = bookKey(title.replace(Regex("\\s*\\([^)]*#[^)]*\\)\\s*$"), ""), author)

    val year: Int get() = Calendar.getInstance().apply { timeInMillis = readAt }.get(Calendar.YEAR)
}

/** Historial de lectura importado de Goodreads («Mis libros › Importar/exportar › Exportar biblioteca»). */
object Goodreads {

    private fun file(context: Context) = File(context.filesDir, "read_history.json")

    fun all(context: Context): List<ReadRecord> = runCatching {
        val arr = JSONArray(file(context).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ReadRecord(o.optString("title"), o.optString("author"), o.optLong("readAt"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, list: List<ReadRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("author", it.author).put("readAt", it.readAt)) }
        file(context).writeText(arr.toString())
    }

    /** Importa el CSV. Devuelve cuántos libros leídos nuevos se añadieron. Bloqueante. */
    fun import(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("No se pudo leer el archivo")
        val rows = parseCsv(text.removePrefix("﻿"))
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: emptyList()
        val iTitle = header.indexOf("title")
        val iAuthor = header.indexOf("author")
        val iDate = header.indexOf("date read")
        val iShelf = header.indexOf("exclusive shelf")
        if (iTitle < 0 || iDate < 0) throw IllegalArgumentException("No parece una exportación de Goodreads")
        val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        val existing = all(context)
        val seen = existing.map { it.key + it.year }.toHashSet()
        val added = ArrayList<ReadRecord>()
        rows.drop(1).forEach { r ->
            val date = r.getOrNull(iDate)?.trim().orEmpty()
            if (date.isEmpty()) return@forEach
            if (iShelf >= 0 && r.getOrNull(iShelf)?.trim() != "read") return@forEach
            val t = runCatching { fmt.parse(date)?.time }.getOrNull() ?: return@forEach
            val rec = ReadRecord(r[iTitle].trim(), r.getOrNull(iAuthor)?.trim().orEmpty(), t)
            if (seen.add(rec.key + rec.year)) added += rec
        }
        save(context, existing + added)
        return added.size
    }

    /** CSV con comillas, comillas dobladas y saltos de línea dentro de los campos. */
    private fun parseCsv(s: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && s.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && s.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
