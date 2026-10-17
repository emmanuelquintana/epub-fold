package com.emmanuel.epubfold

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.slider.Slider

/**
 * Lectura rápida palabra a palabra (RSVP): cada palabra aparece en el centro con
 * su letra de enfoque resaltada, a la velocidad elegida. Al salir se continúa
 * leyendo desde la última palabra mostrada.
 */
class SpeedReader(
    private val activity: Activity,
    private val prefs: ReaderPrefs,
    private val colors: ThemeColors,
    private val accent: Int,
    private val typeface: Typeface,
    private val textOf: (Int) -> CharSequence,
    private val chapterCount: Int,
    private val onExit: (chapter: Int, offset: Int) -> Unit,
) {
    private data class Word(val start: Int, val end: Int)

    private val handler = Handler(Looper.getMainLooper())
    private var chapter = 0
    private var words: List<Word> = emptyList()
    private var index = 0
    private var playing = false
    private lateinit var wordView: TextView
    private lateinit var info: TextView
    private lateinit var toggle: TextView
    private lateinit var dialog: Dialog

    fun show(startChapter: Int, startOffset: Int) {
        loadChapter(startChapter, startOffset)
        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(activity).apply { setBackgroundColor(colors.paper) }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dpi(24), activity.dpi(28), activity.dpi(24), activity.dpi(28))
        }
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        col.addView(TextView(activity).apply {
            text = "‹ Volver al libro"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, activity.dpi(8), 0, activity.dpi(8))
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Guías finas encima y debajo de la palabra, como en los lectores RSVP
        val guide = ColorUtils.blendARGB(colors.paper, colors.text, 0.2f)
        val stage = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { if (playing) pause() else play() }
        }
        stage.addView(View(activity).apply { setBackgroundColor(guide) }, LinearLayout.LayoutParams(activity.dpi(2), activity.dpi(18)))
        wordView = TextView(activity).apply {
            gravity = Gravity.CENTER
            this.typeface = this@SpeedReader.typeface
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            maxLines = 1
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 18, 40, 2, TypedValue.COMPLEX_UNIT_SP
            )
            setPadding(0, activity.dpi(16), 0, activity.dpi(16))
        }
        stage.addView(wordView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        stage.addView(View(activity).apply { setBackgroundColor(guide) }, LinearLayout.LayoutParams(activity.dpi(2), activity.dpi(18)))
        col.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        info = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(colors.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        col.addView(info)

        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, activity.dpi(14), 0, activity.dpi(6))
        }
        fun button(label: String, onClick: () -> Unit) = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = roundRect(ColorUtils.blendARGB(colors.paper, colors.text, 0.08f), activity.dp(20f))
            setPadding(activity.dpi(18), activity.dpi(10), activity.dpi(18), activity.dpi(10))
            setOnClickListener { onClick() }
        }
        controls.addView(button("« 10") { jump(-10) })
        toggle = button("Empezar") { if (playing) pause() else play() }.apply {
            setTextColor(0xFFFFFFFF.toInt())
            background = roundRect(accent, activity.dp(20f))
        }
        controls.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = activity.dpi(12); marginEnd = activity.dpi(12)
        })
        controls.addView(button("10 »") { jump(10) })
        col.addView(controls)

        val wpmLabel = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(colors.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, activity.dpi(10), 0, 0)
        }
        fun showWpm() { wpmLabel.text = "${prefs.speedWpm} palabras por minuto" }
        showWpm()
        col.addView(wpmLabel)
        col.addView(Slider(activity).apply {
            valueFrom = 150f
            valueTo = 900f
            stepSize = 25f
            value = prefs.speedWpm.toFloat().coerceIn(150f, 900f)
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            trackActiveTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            trackInactiveTintList = ColorStateList.valueOf(guide)
            addOnChangeListener { _, v, fromUser -> if (fromUser) { prefs.speedWpm = v.toInt(); showWpm() } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(root)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.setOnDismissListener {
            pause()
            val w = words.getOrNull(index)
            onExit(chapter, w?.start ?: 0)
        }
        render()
        dialog.show()
    }

    private fun loadChapter(ch: Int, fromOffset: Int) {
        var c = ch
        while (c < chapterCount) {
            val text = textOf(c)
            val list = ArrayList<Word>()
            var i = 0
            val n = text.length
            while (i < n) {
                while (i < n && (text[i].isWhitespace() || text[i] == '￼')) i++
                val s = i
                while (i < n && !text[i].isWhitespace() && text[i] != '￼') i++
                if (i > s) list += Word(s, i)
            }
            if (list.isNotEmpty()) {
                chapter = c
                words = list
                index = if (c == ch) list.indexOfFirst { it.end > fromOffset }.coerceAtLeast(0) else 0
                return
            }
            c++
        }
        words = emptyList()
        index = 0
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            if (index + 1 >= words.size) {
                // Fin del capítulo: pasamos al siguiente
                if (chapter + 1 >= chapterCount) {
                    pause()
                    return
                }
                loadChapter(chapter + 1, 0)
                if (words.isEmpty()) { pause(); return }
            } else {
                index++
            }
            render()
            handler.postDelayed(this, delayFor(words[index]))
        }
    }

    /** Pausa más larga al final de frase y con palabras largas, para que se entienda. */
    private fun delayFor(w: Word): Long {
        val base = 60_000.0 / prefs.speedWpm
        val text = textOf(chapter)
        val last = text[w.end - 1]
        val len = w.end - w.start
        var f = 1.0
        if (last == '.' || last == '!' || last == '?' || last == '…' || last == '»' || last == '”') f = 2.2
        else if (last == ',' || last == ';' || last == ':') f = 1.5
        if (len > 8) f += (len - 8) * 0.08
        return (base * f).toLong()
    }

    private fun play() {
        if (words.isEmpty()) return
        playing = true
        toggle.text = "Pausa"
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayFor(words[index]))
    }

    private fun pause() {
        playing = false
        handler.removeCallbacks(tick)
        if (::toggle.isInitialized) toggle.text = "Seguir"
    }

    private fun jump(delta: Int) {
        index = (index + delta).coerceIn(0, (words.size - 1).coerceAtLeast(0))
        render()
    }

    private fun render() {
        val w = words.getOrNull(index)
        if (w == null) {
            wordView.text = "Fin del libro"
            info.text = ""
            return
        }
        val word = textOf(chapter).subSequence(w.start, w.end).toString()
        // Letra de enfoque: un poco antes del centro de la palabra
        val letters = word.indices.filter { word[it].isLetterOrDigit() }
        val pivot = if (letters.isEmpty()) 0 else letters[((letters.size - 1) * 0.35).toInt()]
        wordView.text = SpannableString(word).apply {
            setSpan(ForegroundColorSpan(accent), pivot, pivot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val left = words.size - index
        val min = (left / prefs.speedWpm.toFloat()).let { if (it < 1f) "menos de 1 min" else "≈ ${it.toInt()} min" }
        info.text = "Capítulo ${chapter + 1} · ${index * 100 / words.size.coerceAtLeast(1)} % · quedan $min"
    }
}
