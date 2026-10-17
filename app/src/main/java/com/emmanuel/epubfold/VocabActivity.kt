package com.emmanuel.epubfold

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

/** Vocabulario: tarjetas de repaso espaciado y lista de palabras buscadas. */
class VocabActivity : GroupedActivity() {

    override val screenTitle = "Vocabulario"
    private lateinit var vocab: Vocabulary
    private var revealed = false

    override fun build() {
        vocab = Vocabulary(this)
        if (vocab.words.isEmpty()) {
            group().addView(paragraph(
                "Aquí se guardan las palabras que buscas con «Definir» o «Traducir» mientras lees, " +
                    "junto con la frase donde aparecían. Luego puedes repasarlas como tarjetas."
            ))
            return
        }
        val due = vocab.due()
        caption("Repasar hoy")
        val card = group()
        val w = due.firstOrNull()
        if (w == null) {
            card.addView(paragraph("¡Al día! No hay tarjetas pendientes. Vuelve mañana."))
        } else {
            buildCard(card, w, due.size)
        }

        spacer(24)
        caption("Todas las palabras (${vocab.words.size})")
        val list = group()
        vocab.words.sortedByDescending { it.created }.forEach { v ->
            val next = if (v.due <= System.currentTimeMillis()) "hoy" else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(v.due))
            list.rows(row(
                v.word,
                sub = listOf(v.meaning.take(90), v.book).filter { it.isNotBlank() }.joinToString(" · "),
                trailing = "Nivel ${v.box} · $next",
                plain = true,
                onLongClick = {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("¿Borrar «${v.word}»?")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Borrar") { _, _ -> vocab.remove(v); rebuild() }
                        .show()
                },
            ) { showWord(v) })
        }
        footnote("Mantén pulsada una palabra para borrarla.")
    }

    private fun buildCard(card: LinearLayout, w: VocabWord, pending: Int) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(20), dpi(22), dpi(20), dpi(18))
        }
        box.addView(TextView(this).apply {
            text = "Quedan $pending"
            setTextAppearance(R.style.Text_Footnote)
        })
        box.addView(TextView(this).apply {
            text = w.word
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            setTextColor(color(R.color.label))
            gravity = Gravity.CENTER
            setPadding(0, dpi(10), 0, dpi(8))
        })
        if (w.context.isNotEmpty()) box.addView(TextView(this).apply {
            // La palabra se oculta en la frase para obligar a recordarla
            text = "“" + w.context.replace(Regex(Regex.escape(w.word), RegexOption.IGNORE_CASE), "＿＿＿") + "”"
            setTextAppearance(R.style.Text_Subhead)
            gravity = Gravity.CENTER
            typeface = Typeface.SERIF
        })
        val meaning = TextView(this).apply {
            text = w.meaning.ifEmpty { "(sin definición guardada: búscala de nuevo con «Definir»)" }
            setTextAppearance(R.style.Text_Body)
            gravity = Gravity.CENTER
            setPadding(0, dpi(16), 0, 0)
            visibility = if (revealed) View.VISIBLE else View.GONE
        }
        box.addView(meaning)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpi(18), 0, 0)
        }
        fun lp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dpi(4); marginEnd = dpi(4)
        }
        if (!revealed) {
            buttons.addView(pill("Mostrar significado") { revealed = true; rebuild() }, lp())
        } else {
            buttons.addView(pill("Otra vez", filled = false) { answer(w, 0) }, lp())
            buttons.addView(pill("Bien") { answer(w, 1) }, lp())
            buttons.addView(pill("Fácil", filled = false) { answer(w, 2) }, lp())
        }
        box.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(box)
    }

    private fun answer(w: VocabWord, grade: Int) {
        vocab.answer(w, grade)
        revealed = false
        rebuild()
    }

    private fun showWord(v: VocabWord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(v.word)
            .setMessage(listOf(v.meaning, if (v.context.isNotEmpty()) "“${v.context}”" else "", v.book)
                .filter { it.isNotBlank() }.joinToString("\n\n"))
            .setPositiveButton("OK", null)
            .show()
    }
}
