package com.emmanuel.epubfold

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

fun roundRect(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/**
 * Control segmentado al estilo iOS: cápsula gris con el segmento seleccionado
 * resaltado en una "píldora" clara.
 */
class SegmentedControl(context: Context) : LinearLayout(context) {

    var onSelected: ((Int) -> Unit)? = null
    private val segments = ArrayList<TextView>()
    var selected = 0
        private set

    init {
        orientation = HORIZONTAL
        val pad = context.dpi(2)
        setPadding(pad, pad, pad, pad)
        background = roundRect(context.color(R.color.seg_bg), context.dp(9f))
    }

    fun setOptions(options: List<String>, selectedIndex: Int) {
        removeAllViews()
        segments.clear()
        options.forEachIndexed { i, label ->
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(context.color(R.color.label))
                val v = context.dpi(6)
                // Con muchas opciones o poco ancho, menos relleno y letra que se encoge
                val h = if (options.size >= 4) context.dpi(4) else context.dpi(10)
                setPadding(h, v, h, v)
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 9, 13, 1, TypedValue.COMPLEX_UNIT_SP
                )
                setOnClickListener { select(i, notify = true) }
            }
            addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            segments += tv
        }
        select(selectedIndex, notify = false)
    }

    fun select(index: Int, notify: Boolean) {
        selected = index
        segments.forEachIndexed { i, tv ->
            val on = i == index
            tv.background = if (on) roundRect(context.color(R.color.seg_selected), context.dp(7f)) else null
            tv.elevation = if (on) context.dp(1f) else 0f
            tv.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
        if (notify) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onSelected?.invoke(index)
        }
    }
}

/** Ancho disponible en dp (cambia al plegar, desplegar o usar pantalla dividida). */
fun Context.widthDp(): Int = resources.configuration.screenWidthDp

/**
 * En pantallas anchas (Fold abierto, tablet) limita el ancho de [content] y lo centra,
 * para que las listas y formularios no se estiren de borde a borde.
 */
fun Context.capWidth(content: View, maxDp: Int = 640) {
    val lp = content.layoutParams
    val max = dpi(maxDp)
    val avail = (widthDp() * resources.displayMetrics.density).toInt()
    if (lp is android.widget.FrameLayout.LayoutParams) {
        lp.width = if (avail > max + dpi(32)) max else android.view.ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.CENTER_HORIZONTAL
        content.layoutParams = lp
    }
}

/** Separador fino de 1px, como los de las listas agrupadas de iOS. */
fun hairline(context: Context, insetStart: Int = 0): View = View(context).apply {
    setBackgroundColor(context.color(R.color.separator))
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
        marginStart = insetStart
    }
}
