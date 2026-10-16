package com.emmanuel.epubfold

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Bookmark(val chapter: Int, val charOffset: Int, val snippet: String, val created: Long)

/** Marcadores de un libro, guardados en JSON junto a la biblioteca. */
class Bookmarks(context: Context, bookId: String) {

    private val file = File(File(context.filesDir, "marks").apply { mkdirs() }, "$bookId.json")

    val items: MutableList<Bookmark> = load()

    /** Marcador cuyo inicio cae dentro del rango [start, end) del capítulo, si existe. */
    fun find(chapter: Int, start: Int, end: Int): Bookmark? =
        items.firstOrNull { it.chapter == chapter && it.charOffset >= start && it.charOffset < end }

    fun add(b: Bookmark) {
        items += b
        items.sortWith(compareBy({ it.chapter }, { it.charOffset }))
        save()
    }

    fun remove(b: Bookmark) {
        items.remove(b)
        save()
    }

    fun clear() {
        items.clear()
        file.delete()
    }

    private fun load(): MutableList<Bookmark> {
        if (!file.exists()) return ArrayList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapTo(ArrayList()) { i ->
                val o = arr.getJSONObject(i)
                Bookmark(o.getInt("chapter"), o.getInt("offset"), o.optString("snippet"), o.optLong("created"))
            }
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("chapter", it.chapter); put("offset", it.charOffset)
                put("snippet", it.snippet); put("created", it.created)
            })
        }
        file.writeText(arr.toString())
    }
}
