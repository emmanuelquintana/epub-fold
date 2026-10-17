package com.emmanuel.epubfold

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Traductores de ML Kit reutilizables (crear uno cuesta, y el modelo se descarga una vez). */
object Translators {
    val TARGETS = listOf("es" to "ES", "en" to "EN", "fr" to "FR", "pt" to "PT", "de" to "DE", "it" to "IT")

    private val cache = HashMap<String, Translator>()

    suspend fun detect(text: String, fallback: String): String {
        val d = runCatching { LanguageIdentification.getClient().identifyLanguage(text.take(600)).await() }.getOrNull()
        return (d?.takeIf { it != "und" } ?: fallback.take(2)).ifEmpty { "en" }
    }

    /** Traduce [text] de [src] a [dst]; descarga el idioma la primera vez (~30 MB). */
    suspend fun translate(text: String, src: String, dst: String): String {
        val s = TranslateLanguage.fromLanguageTag(src) ?: TranslateLanguage.ENGLISH
        val d = TranslateLanguage.fromLanguageTag(dst) ?: TranslateLanguage.SPANISH
        if (s == d) return text
        val t = synchronized(cache) {
            cache.getOrPut("$s>$d") {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(d).build())
            }
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return t.translate(text).await()
    }
}

/**
 * Traducción en paralelo: muestra la página actual traducida, párrafo a párrafo,
 * sin conexión. Va en una mitad del Fold mientras se lee el original en la otra.
 */
class TranslationPanel(
    context: Context,
    private val scope: LifecycleCoroutineScope,
    private val prefs: ReaderPrefs,
    private val bookLanguage: String,
) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val output = TextView(context)
    private val status = TextView(context)
    private val seg = SegmentedControl(context)
    private var job: Job? = null
    private var lastText = ""
    private var lastTarget = ""
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }

    init {
        orientation = VERTICAL
        setPadding(context.dpi(16), 0, context.dpi(16), 0)
        val current = Translators.TARGETS.indexOfFirst { it.first == prefs.translateTarget }.coerceAtLeast(0)
        seg.setOptions(Translators.TARGETS.map { it.second }, current)
        seg.onSelected = { i ->
            prefs.translateTarget = Translators.TARGETS[i].first
            translateNow(lastText, force = true)
        }
        addView(seg, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, context.dpi(8), 0, context.dpi(4))
        }
        addView(status)
        val scroll = ScrollView(context)
        output.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setLineSpacing(0f, 1.3f)
            gravity = Gravity.START
            setTextIsSelectable(true)
            setPadding(0, context.dpi(6), 0, context.dpi(40))
            typeface = prefs.font.typeface(context)
        }
        scroll.addView(output)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setColors(text: Int, secondary: Int) {
        output.setTextColor(text)
        status.setTextColor(secondary)
    }

    /** Llamar al cambiar de página: espera un momento por si se pasan varias seguidas. */
    fun showPage(text: String) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ translateNow(text, force = false) }, 350)
    }

    private fun translateNow(text: String, force: Boolean) {
        val target = prefs.translateTarget
        if (!force && text == lastText && target == lastTarget) return
        lastText = text
        lastTarget = target
        job?.cancel()
        val paragraphs = text.split('\n').map { it.replace('￼', ' ').trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) {
            output.text = ""
            status.text = "Esta página no tiene texto."
            return
        }
        status.text = "Traduciendo… (la primera vez se descarga el idioma, ~30 MB)"
        job = scope.launch {
            try {
                val src = Translators.detect(text, bookLanguage)
                if (src.take(2) == target) {
                    output.text = ""
                    status.text = "El libro ya está en ese idioma. Elige otro arriba."
                    return@launch
                }
                val sb = StringBuilder()
                paragraphs.forEachIndexed { i, p ->
                    val key = "$src>$target:$p"
                    val t = cache[key] ?: Translators.translate(p, src, target).also { cache[key] = it }
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(t)
                    // Se va mostrando a medida que avanza
                    if (i % 2 == 1 || i == paragraphs.size - 1) output.text = sb.toString()
                }
                status.text = "Traducido de ${src.uppercase()} sin conexión · ML Kit"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                status.text = "No se pudo traducir: ${e.message ?: "sin conexión para descargar el idioma"}"
            }
        }
    }

    fun dispose() {
        handler.removeCallbacksAndMessages(null)
        job?.cancel()
    }
}
