package com.emmanuel.epubfold

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Hoja "Definir / Traducir": definición de Wikcionario y traducción sin conexión
 * con ML Kit. También permite abrir otras apps de diccionario instaladas.
 */
class LookupSheet(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val prefs: ReaderPrefs,
    private val bookLanguage: String,
) {
    private val ctx = activity

    fun show(text: String, translateFirst: Boolean) {
        val query = text.trim().replace(Regex("\\s+"), " ")
        if (query.isEmpty()) return
        val isWord = !query.contains(' ') && query.length <= 40

        val dialog = BottomSheetDialog(ctx)
        val scroll = NestedScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(20), ctx.dpi(10), ctx.dpi(20), ctx.dpi(32))
        }
        scroll.addView(col)

        col.addView(TextView(ctx).apply {
            this.text = query
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isWord) 28f else 18f)
            setTextColor(ctx.color(R.color.label))
            maxLines = 6
        })

        val seg = SegmentedControl(ctx)
        val tabs = if (isWord) listOf("Diccionario", "Traducción") else listOf("Traducción")
        col.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(14)
        })

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(14)
        })

        // Acciones extra
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(pill("Otras apps") { openWithApps(query) })
        actions.addView(pill("Buscar en la web") {
            val q = URLEncoder.encode(if (isWord) "definición $query" else query, "UTF-8")
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))) }
        })
        col.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(22)
        })

        var job: Job? = null
        fun showTab(i: Int) {
            job?.cancel()
            content.removeAllViews()
            job = if (tabs[i] == "Diccionario") showDefinition(query, content) else showTranslation(query, content)
        }
        val initial = if (translateFirst || !isWord) tabs.indexOf("Traducción") else 0
        seg.setOptions(tabs, initial)
        seg.onSelected = { showTab(it) }
        showTab(initial)

        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(ctx.color(R.color.accent))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = roundRect(ctx.color(R.color.fill), ctx.dp(100f))
        setPadding(ctx.dpi(16), ctx.dpi(8), ctx.dpi(16), ctx.dpi(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dpi(5); marginEnd = ctx.dpi(5)
        }
        setOnClickListener { onClick() }
    }

    private fun body(text: CharSequence) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(R.style.Text_Body)
        setLineSpacing(0f, 1.25f)
        setTextIsSelectable(true)
    }

    private fun note(text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(R.style.Text_Footnote)
    }

    // ---------------------------------------------------------------- diccionario

    private fun showDefinition(word: String, content: LinearLayout): Job {
        val status = note("Buscando en Wikcionario…")
        content.addView(status)
        val lang = bookLanguage.take(2).lowercase().ifEmpty { "es" }
        return scope.launch {
            val result = withContext(Dispatchers.IO) {
                fetchWiktionary(lang, word) ?: fetchWiktionary(lang, word.lowercase())
                    ?: if (lang != "es") fetchWiktionary("es", word.lowercase()) else null
            }
            content.removeAllViews()
            if (result == null) {
                content.addView(note("No se encontró una definición (o no hay conexión). Prueba «Otras apps» o la traducción."))
            } else {
                content.addView(body(format(result)))
                content.addView(note("Fuente: Wikcionario (CC BY-SA)").apply { setPadding(0, ctx.dpi(12), 0, 0) })
            }
        }
    }

    private fun fetchWiktionary(lang: String, word: String): String? = runCatching {
        val url = URL(
            "https://$lang.wiktionary.org/w/api.php?action=query&format=json&prop=extracts" +
                "&explaintext=1&redirects=1&titles=" + URLEncoder.encode(word, "UTF-8")
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "EpubFold/1.0 (Android e-reader)")
        val json = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val pages = JSONObject(json).getJSONObject("query").getJSONObject("pages")
        val key = pages.keys().next()
        if (key == "-1") return@runCatching null
        pages.getJSONObject(key).optString("extract").trim().ifEmpty { null }
    }.getOrNull()

    /** Da formato a las cabeceras "== Sección ==" y recorta el texto. */
    private fun format(extract: String): CharSequence {
        val sb = SpannableStringBuilder()
        var count = 0
        for (raw in extract.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (count > 3500) break
            if (line.startsWith("=")) {
                val title = line.trim('=', ' ')
                if (title.isEmpty() || title.startsWith("Referencias") || title.startsWith("Véase") ||
                    title.startsWith("Traducciones") || title.startsWith("Información adicional")
                ) continue
                if (sb.isNotEmpty()) sb.append("\n")
                val st = sb.length
                sb.append(title).append("\n")
                sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.92f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.append(line).append("\n")
            }
            count += line.length
        }
        return sb
    }

    // ---------------------------------------------------------------- traducción

    private val targets = listOf("es" to "ES", "en" to "EN", "fr" to "FR", "pt" to "PT", "de" to "DE", "it" to "IT")

    private fun showTranslation(text: String, content: LinearLayout): Job {
        val seg = SegmentedControl(ctx)
        val current = targets.indexOfFirst { it.first == prefs.translateTarget }.coerceAtLeast(0)
        seg.setOptions(targets.map { it.second }, current)
        content.addView(TextView(ctx).apply {
            this.text = "Traducir a"
            setTextAppearance(R.style.Text_Footnote)
            setPadding(0, 0, 0, ctx.dpi(6))
        })
        content.addView(seg)
        val out = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(out, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(14)
        })
        val holder = Job()
        var job: Job? = null
        fun run(target: String) {
            job?.cancel()
            out.removeAllViews()
            val status = note("Preparando traductor sin conexión… (la primera vez descarga el idioma, ~30 MB)")
            out.addView(status)
            job = scope.launch(holder) {
                try {
                    val detected = runCatching {
                        LanguageIdentification.getClient().identifyLanguage(text).await()
                    }.getOrNull()
                    val srcTag = (detected?.takeIf { it != "und" } ?: bookLanguage.take(2)).ifEmpty { "en" }
                    val src = TranslateLanguage.fromLanguageTag(srcTag) ?: TranslateLanguage.ENGLISH
                    val dst = TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.SPANISH
                    if (src == dst) {
                        out.removeAllViews()
                        out.addView(note("El texto ya está en ese idioma. Elige otro destino."))
                        return@launch
                    }
                    val translator = Translation.getClient(
                        TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
                    )
                    try {
                        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                        val result = translator.translate(text).await()
                        out.removeAllViews()
                        out.addView(body(result).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f) })
                        out.addView(note("Traducido de ${srcTag.uppercase()} sin conexión con ML Kit").apply {
                            setPadding(0, ctx.dpi(10), 0, 0)
                        })
                    } finally {
                        translator.close()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    out.removeAllViews()
                    out.addView(note("No se pudo traducir: ${e.message ?: "sin conexión para descargar el idioma"}"))
                }
            }
        }
        seg.onSelected = { i ->
            prefs.translateTarget = targets[i].first
            run(targets[i].first)
        }
        run(targets[current].first)
        return holder
    }

    // ---------------------------------------------------------------- otras apps

    private fun openWithApps(text: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        runCatching { ctx.startActivity(Intent.createChooser(intent, "Abrir con")) }
    }
}
