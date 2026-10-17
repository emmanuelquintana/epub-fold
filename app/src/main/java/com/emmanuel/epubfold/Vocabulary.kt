package com.emmanuel.epubfold

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Palabra buscada al leer, con su contexto y repaso espaciado (cajas de Leitner). */
data class VocabWord(
    val word: String,
    val context: String,
    val meaning: String,
    val book: String,
    val created: Long,
    /** Caja 0-5: cuanto más alta, más días hasta el siguiente repaso. */
    val box: Int = 0,
    val due: Long = 0L,
)

/** Lista de vocabulario guardada en vocab.json. */
class Vocabulary(context: Context) {

    private val file = File(context.filesDir, "vocab.json")
    val words: MutableList<VocabWord> = ArrayList()

    init {
        load()
    }

    private fun norm(w: String) = w.trim().trim { !it.isLetterOrDigit() }.lowercase()

    fun find(word: String) = words.firstOrNull { norm(it.word) == norm(word) }

    /** Guarda la palabra (o actualiza su contexto si ya estaba). */
    fun add(word: String, context: String, book: String) {
        val clean = word.trim().trim { !it.isLetterOrDigit() }
        if (clean.isEmpty()) return
        val existing = find(clean)
        if (existing != null) {
            replace(existing, existing.copy(context = context.ifEmpty { existing.context }))
        } else {
            words += VocabWord(clean, context, "", book, System.currentTimeMillis())
            save()
        }
    }

    fun setMeaning(word: String, meaning: String) {
        val w = find(word) ?: return
        // La traducción breve es más útil en la tarjeta que la definición larga: no la pisamos con otra peor
        if (w.meaning.isNotEmpty() && w.meaning.length < meaning.length && !meaning.startsWith("→")) return
        replace(w, w.copy(meaning = meaning.take(400)))
    }

    fun remove(w: VocabWord) {
        words.removeAll { it.word == w.word && it.created == w.created }
        save()
    }

    /** Palabras para repasar hoy. */
    fun due(): List<VocabWord> {
        val now = System.currentTimeMillis()
        return words.filter { it.due <= now }.sortedBy { it.due }
    }

    /** Respuesta al repaso: 0 otra vez, 1 bien, 2 fácil. */
    fun answer(w: VocabWord, grade: Int) {
        val box = when (grade) {
            0 -> 0
            1 -> (w.box + 1).coerceAtMost(5)
            else -> (w.box + 2).coerceAtMost(5)
        }
        val days = intArrayOf(0, 1, 3, 7, 16, 35)[box]
        val due = if (grade == 0) System.currentTimeMillis() + 10 * 60_000L
        else System.currentTimeMillis() + days * 86_400_000L
        replace(w, w.copy(box = box, due = due))
    }

    private fun replace(old: VocabWord, new: VocabWord) {
        val i = words.indexOfFirst { it.word == old.word && it.created == old.created }
        if (i >= 0) words[i] = new
        save()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                words += VocabWord(
                    o.optString("word"), o.optString("context"), o.optString("meaning"), o.optString("book"),
                    o.optLong("created"), o.optInt("box"), o.optLong("due"),
                )
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        words.forEach {
            arr.put(JSONObject().put("word", it.word).put("context", it.context).put("meaning", it.meaning)
                .put("book", it.book).put("created", it.created).put("box", it.box).put("due", it.due))
        }
        file.writeText(arr.toString())
    }

    companion object {
        /** Frase que contiene [start]..[end] dentro de [text] (para dar contexto a la palabra). */
        fun sentenceAround(text: CharSequence, start: Int, end: Int): String {
            var s = start.coerceIn(0, text.length)
            var e = end.coerceIn(s, text.length)
            while (s > 0 && s > start - 220 && text[s - 1] !in ".!?…\n") s--
            while (e < text.length && e < end + 220 && text[e] !in ".!?…\n") e++
            if (e < text.length && text[e] != '\n') e++
            return text.subSequence(s, e).toString().replace('￼', ' ').replace(Regex("\\s+"), " ").trim()
        }
    }
}
