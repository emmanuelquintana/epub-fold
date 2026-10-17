package com.emmanuel.epubfold

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Lectura en voz alta frase a frase con el motor TTS del sistema.
 * Avisa de la frase actual para resaltarla y pasar de página.
 */
class ReadAloud(
    context: Context,
    private val textOf: (Int) -> CharSequence,
    private val chapterCount: () -> Int,
    private val onSentence: (chapter: Int, start: Int, end: Int) -> Unit,
    private val onStateChanged: (playing: Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingStart: Pair<Int, Int>? = null
    private var language: Locale = Locale.getDefault()

    var playing = false
        private set
    var rate = 1f
        set(value) {
            field = value
            tts?.setSpeechRate(value)
        }

    /** Última frase empezada (para reanudar). */
    var chapter = 0
        private set
    var offset = 0
        private set
    private var lastUtterance = ""
    private var session = 0
    /** Fin de la frase que se está leyendo (para saltar a la siguiente). */
    private var sentenceEnd = 0

    /** Temporizador "al final del capítulo": se detiene al terminar el capítulo actual. */
    var stopAtChapterEnd = false
    /** Se llama cuando el temporizador de fin de capítulo detiene la lectura. */
    var onChapterEndStop: (() -> Unit)? = null

    fun setLanguage(tag: String) {
        if (tag.isNotEmpty()) language = Locale.forLanguageTag(tag)
        if (ready) applyLanguage()
    }

    private fun ensureEngine(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    applyLanguage()
                    tts?.setSpeechRate(rate)
                    tts?.setOnUtteranceProgressListener(listener)
                    pendingStart?.let { (c, o) -> speakFrom(c, o) }
                    pendingStart = null
                } else {
                    onError("No hay un motor de voz disponible en el teléfono")
                    stop()
                }
            }
        }
    }

    private fun applyLanguage() {
        val t = tts ?: return
        val r = t.setLanguage(language)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())
        }
    }

    fun play(context: Context, chapter: Int, offset: Int) {
        playing = true
        onStateChanged(true)
        ensureEngine(context)
        if (!ready) pendingStart = chapter to offset else speakFrom(chapter, offset)
    }

    fun pause() {
        playing = false
        session++
        tts?.stop()
        onStateChanged(false)
    }

    fun stop() {
        pause()
        onSentence(-1, 0, 0)
    }

    /** Salta a la frase siguiente. */
    fun skipSentence(context: Context) {
        if (sentenceEnd > offset) play(context, chapter, sentenceEnd)
    }

    fun shutdown() {
        session++
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        playing = false
    }

    private fun speakFrom(ch: Int, off: Int) {
        val t = tts ?: return
        session++
        t.stop()
        var c = ch
        var o = off
        // Salta capítulos vacíos
        while (c < chapterCount() && o >= textOf(c).length) {
            c++
            o = 0
        }
        if (c >= chapterCount()) {
            stop()
            return
        }
        chapter = c
        offset = o
        val text = textOf(c)
        val sentences = split(text, o)
        if (sentences.isEmpty()) {
            speakFrom(c + 1, 0)
            return
        }
        sentences.forEachIndexed { i, (s, e) ->
            val id = "$session:$c:$s:$e:${if (i == sentences.size - 1) 1 else 0}"
            val chunk = text.subSequence(s, e).toString().replace('￼', ' ')
            t.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
            lastUtterance = id
        }
    }

    /** Divide en frases a partir de [from]; cada trozo cabe en el límite del motor. */
    private fun split(text: CharSequence, from: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(200)
        var s = from
        val n = text.length
        while (s < n) {
            while (s < n && text[s].isWhitespace()) s++
            if (s >= n) break
            var e = s
            while (e < n && e - s < max) {
                val ch = text[e]
                e++
                if (ch == '\n') break
                if ((ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == ';') &&
                    (e >= n || text[e].isWhitespace() || text[e] == '"' || text[e] == '”' || text[e] == '»')
                ) {
                    while (e < n && (text[e] == '"' || text[e] == '”' || text[e] == '»')) e++
                    break
                }
            }
            if (text.subSequence(s, e).any { it.isLetterOrDigit() }) out += s to e
            s = e
        }
        return out
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val p = utteranceId.split(':')
            if (p.size < 5 || p[0].toInt() != session) return
            main.post {
                if (!playing) return@post
                chapter = p[1].toInt()
                offset = p[2].toInt()
                sentenceEnd = p[3].toInt()
                onSentence(chapter, offset, p[3].toInt())
            }
        }

        override fun onDone(utteranceId: String) {
            val p = utteranceId.split(':')
            if (p.size < 5 || p[0].toInt() != session || p[4] != "1") return
            // Fin del capítulo: seguimos con el siguiente (salvo temporizador de fin de capítulo)
            main.post {
                if (!playing) return@post
                if (stopAtChapterEnd) {
                    stopAtChapterEnd = false
                    chapter = p[1].toInt() + 1
                    offset = 0
                    pause()
                    onChapterEndStop?.invoke()
                } else {
                    speakFrom(p[1].toInt() + 1, 0)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            main.post { onError("Error del motor de voz"); stop() }
        }
    }
}
