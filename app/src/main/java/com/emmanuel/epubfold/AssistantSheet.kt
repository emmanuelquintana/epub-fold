package com.emmanuel.epubfold

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hoja «Asistente»: resumen hasta aquí, preguntar al libro y personajes. */
class AssistantSheet(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val entry: BookEntry,
    private val book: EpubBook,
    private val paginator: Paginator,
    private val chapter: Int,
    private val offset: Int,
    private val page: Int,
) {
    private val ctx = activity
    private val dialog = BottomSheetDialog(activity)
    private lateinit var content: LinearLayout
    private var job: Job? = null
    private val history = ArrayList<Pair<String, String>>()
    private val bookText by lazy { Assistant.textUpTo(book, paginator, chapter, offset) }

    fun show() {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), ctx.dpi(6), ctx.dpi(16), ctx.dpi(24))
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(ctx).apply {
            text = "Asistente"
            setTextAppearance(R.style.Text_Headline)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(ctx).apply {
            text = "Ajustes"
            setTextColor(ctx.color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(ctx.dpi(8), ctx.dpi(8), ctx.dpi(4), ctx.dpi(8))
            setOnClickListener { showKeyDialog(ctx) { render(0) } }
        })
        col.addView(head)
        col.addView(TextView(ctx).apply {
            text = "Sólo usa lo que ya leíste (hasta la página ${page + 1}), así que no hay spoilers."
            setTextAppearance(R.style.Text_Footnote)
            setPadding(0, ctx.dpi(2), 0, ctx.dpi(10))
        })
        val seg = SegmentedControl(ctx)
        col.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dpi(14), 0, 0)
        }
        col.addView(content)
        seg.setOptions(listOf("Qué pasó antes", "Preguntar", "Personajes"), 0)
        seg.onSelected = { render(it) }

        val scroll = NestedScrollView(ctx).apply { addView(col) }
        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = ctx.dpi(720)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        currentTab = 0
        render(0)
    }

    private var currentTab = 0

    private fun render(tab: Int) {
        currentTab = tab
        job?.cancel()
        content.removeAllViews()
        if (Assistant.apiKey(ctx) == null) {
            content.addView(paragraph(
                "El asistente usa Claude, la IA de Anthropic, con tu propia clave de API (se paga por uso; " +
                    "un resumen suele costar unos céntimos). La clave se guarda sólo en este teléfono y sólo se " +
                    "envía el texto que ya leíste de este libro."
            ))
            content.addView(pill("Añadir clave de API") { showKeyDialog(ctx) { render(tab) } })
            return
        }
        when (tab) {
            0 -> renderSummary()
            1 -> renderAsk()
            else -> renderCharacters()
        }
    }

    // ---------------------------------------------------------------- resumen

    private fun renderSummary() {
        val cached = Assistant.cached(ctx, entry.id, "summary")
        if (cached != null) {
            content.addView(note(if (cached.first == page) "Resumen de esta página" else "Resumen hecho en la página ${cached.first + 1}"))
            content.addView(body(cached.second))
        } else {
            content.addView(paragraph("Un repaso de la historia hasta tu página, para retomar el libro sin perderte."))
        }
        content.addView(pill(if (cached == null) "Resumir hasta aquí" else "Actualizar resumen") {
            run("Resumiendo…") {
                val (text, truncated) = bookText
                Assistant.summary(ctx, entry.title, entry.author, text, truncated).also {
                    Assistant.saveCached(ctx, entry.id, "summary", page, it)
                }
            }.let { }
        })
    }

    // ---------------------------------------------------------------- preguntar

    private fun renderAsk() {
        if (history.isEmpty()) {
            content.addView(paragraph("Pregunta lo que quieras: «¿quién era Teresa?», «¿por qué se fue de la ciudad?», «explícame esta parte»."))
        }
        history.forEach { (q, a) ->
            content.addView(TextView(ctx).apply {
                text = q
                setTextAppearance(R.style.Text_Headline)
                setPadding(0, ctx.dpi(8), 0, ctx.dpi(4))
            })
            content.addView(body(a))
        }
        val field = EditText(ctx).apply {
            hint = "Escribe tu pregunta"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
            background = roundRect(ctx.color(R.color.fill), ctx.dp(12f))
            setPadding(ctx.dpi(12), ctx.dpi(10), ctx.dpi(12), ctx.dpi(10))
        }
        content.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(12)
        })
        fun send() {
            val q = field.text.toString().trim()
            if (q.isEmpty()) return
            val before = ArrayList(history)
            run("Pensando…") {
                val (text, truncated) = bookText
                Assistant.ask(ctx, entry.title, entry.author, text, truncated, before.takeLast(6), q).also {
                    history += q to it
                }
            }.invokeOnCompletion { if (it == null) dialog.window?.decorView?.post { if (currentTab == 1) render(1) } }
        }
        field.setOnEditorActionListener { _, _, _ -> send(); true }
        content.addView(pill("Preguntar") { send() })
    }

    // ---------------------------------------------------------------- personajes

    private fun renderCharacters() {
        val cached = Assistant.cached(ctx, entry.id, "characters")
        val list = cached?.let { Assistant.charactersFromJson(it.second) }.orEmpty()
        if (list.isEmpty()) {
            content.addView(paragraph("La lista de personajes que han aparecido hasta tu página, con quién es cada uno."))
        } else {
            content.addView(note(if (cached!!.first == page) "Hasta esta página" else "Hasta la página ${cached.first + 1}"))
            list.forEach { c ->
                content.addView(TextView(ctx).apply {
                    text = c.name + if (c.role.isNotBlank()) "  ·  ${c.role}" else ""
                    setTextAppearance(R.style.Text_Headline)
                    setPadding(0, ctx.dpi(10), 0, ctx.dpi(2))
                })
                content.addView(TextView(ctx).apply {
                    text = c.description
                    setTextAppearance(R.style.Text_Body)
                })
            }
        }
        content.addView(pill(if (list.isEmpty()) "Crear lista de personajes" else "Actualizar hasta aquí") {
            run("Buscando personajes…") {
                val (text, truncated) = bookText
                val found = Assistant.characters(ctx, entry.title, entry.author, text, truncated)
                Assistant.saveCached(ctx, entry.id, "characters", page, Assistant.charactersToJson(found))
                ""
            }.invokeOnCompletion { if (it == null) dialog.window?.decorView?.post { if (currentTab == 2) render(2) } }
        })
    }

    // ---------------------------------------------------------------- ejecución

    /** Lanza una consulta mostrando el avance; el resultado de texto se añade al final si no está vacío. */
    private fun run(status: String, block: () -> String): Job {
        job?.cancel()
        val spinner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dpi(12), 0, 0)
            addView(ProgressBar(ctx), LinearLayout.LayoutParams(ctx.dpi(24), ctx.dpi(24)))
            addView(TextView(ctx).apply {
                text = "$status (puede tardar unos segundos)"
                setTextAppearance(R.style.Text_Footnote)
                setPadding(ctx.dpi(10), 0, 0, 0)
            })
        }
        content.addView(spinner)
        val tab = currentTab
        return scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            content.removeView(spinner)
            r.onSuccess { text ->
                if (tab == 0 && currentTab == 0) render(0)
                else if (text.isNotEmpty() && tab != 1 && tab != 2) content.addView(body(text))
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                CrashReports.log(ctx, e, "assistant")
                content.addView(note(e.message ?: "No se pudo completar"))
            }
        }.also { job = it }
    }

    // ---------------------------------------------------------------- piezas

    private fun paragraph(t: String) = TextView(ctx).apply {
        text = t
        setTextAppearance(R.style.Text_Subhead)
        setPadding(0, 0, 0, ctx.dpi(10))
    }

    private fun note(t: String) = TextView(ctx).apply {
        text = t
        setTextAppearance(R.style.Text_Footnote)
        setPadding(0, ctx.dpi(6), 0, ctx.dpi(6))
    }

    private fun body(t: String) = TextView(ctx).apply {
        text = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
        setTextAppearance(R.style.Text_Body)
        typeface = Typeface.SERIF
        setLineSpacing(0f, 1.25f)
        setTextIsSelectable(true)
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = roundRect(ctx.color(R.color.accent), ctx.dp(14f))
        setPadding(ctx.dpi(12), ctx.dpi(12), ctx.dpi(12), ctx.dpi(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(12)
        }
        setOnClickListener { onClick() }
    }

    companion object {
        /** Clave de API y modelo. */
        fun showKeyDialog(activity: Activity, onSaved: () -> Unit) {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dpi(22), activity.dpi(8), activity.dpi(22), 0)
            }
            box.addView(TextView(activity).apply {
                text = "Crea una clave en la consola de Anthropic (Settings → API keys) y pégala aquí. " +
                    "Se guarda sólo en este teléfono; no entra en copias de seguridad ni en tu cuenta."
                setTextAppearance(R.style.Text_Subhead)
            })
            box.addView(TextView(activity).apply {
                text = "Abrir la consola de Anthropic"
                setTextColor(activity.color(R.color.accent))
                setPadding(0, activity.dpi(8), 0, activity.dpi(8))
                setOnClickListener {
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.claude.com/settings/keys"))) }
                }
            })
            val field = EditText(activity).apply {
                hint = "sk-ant-…"
                setText(Assistant.apiKey(activity).orEmpty())
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine()
                background = roundRect(activity.color(R.color.fill), activity.dp(10f))
                setPadding(activity.dpi(12), activity.dpi(10), activity.dpi(12), activity.dpi(10))
            }
            box.addView(field)
            box.addView(TextView(activity).apply {
                text = "Modelo"
                setTextAppearance(R.style.Text_Caption)
                setPadding(0, activity.dpi(14), 0, activity.dpi(6))
            })
            val seg = SegmentedControl(activity)
            val idx = Assistant.MODELS.indexOfFirst { it.first == Assistant.model(activity) }.coerceAtLeast(0)
            seg.setOptions(listOf("Opus 5.5", "Sonnet 5.5", "Haiku 5.5"), idx)
            box.addView(seg)
            val hint = TextView(activity).apply {
                text = Assistant.MODELS[idx].second
                setTextAppearance(R.style.Text_Footnote)
                setPadding(0, activity.dpi(6), 0, 0)
            }
            seg.onSelected = { hint.text = Assistant.MODELS[it].second }
            box.addView(hint)
            MaterialAlertDialogBuilder(activity)
                .setTitle("Asistente con Claude")
                .setView(box)
                .setNeutralButton("Borrar clave") { _, _ -> Assistant.setApiKey(activity, null); onSaved() }
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar") { _, _ ->
                    Assistant.setApiKey(activity, field.text.toString())
                    Assistant.setModel(activity, Assistant.MODELS[seg.selected].first)
                    onSaved()
                }
                .show()
        }
    }
}
