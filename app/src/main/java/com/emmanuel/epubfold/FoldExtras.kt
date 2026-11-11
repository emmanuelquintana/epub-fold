package com.emmanuel.epubfold

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/** Arrastrar y soltar entre apps (pantalla dividida del Fold). */
object DragDrop {

    /** Sombra de arrastre: la frase en una tarjeta redondeada. */
    private class TextShadow(private val view: View, text: String, private val bg: Int, ink: Int) : View.DragShadowBuilder(view) {
        private val d = view.resources.displayMetrics.density
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 15 * view.resources.displayMetrics.scaledDensity
            typeface = Typeface.SERIF
        }
        private val layout: StaticLayout = StaticLayout.Builder
            .obtain(text.take(220), 0, text.take(220).length, paint, (240 * d).toInt())
            .setMaxLines(5).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        private val pad = 12 * d
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }

        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            size.set((layout.width + 2 * pad).toInt(), (layout.height + 2 * pad).toInt())
            touch.set(size.x / 2, size.y / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            canvas.drawRoundRect(RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()), 12 * d, 12 * d, box)
            canvas.translate(pad, pad)
            layout.draw(canvas)
        }
    }

    fun dragText(view: View, text: String, label: String, bg: Int, ink: Int) {
        if (text.isBlank()) return
        view.startDragAndDrop(
            ClipData.newPlainText(label, text),
            TextShadow(view, text, (bg and 0x00FFFFFF) or 0xF0000000.toInt(), ink),
            null,
            View.DRAG_FLAG_GLOBAL,
        )
    }

    /** Arrastra una imagen (p. ej. una página de cómic) como archivo compartido. */
    fun dragImage(activity: Activity, view: View, bmp: Bitmap, name: String) {
        val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val uri: Uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
        val clip = ClipData(ClipDescription(name, arrayOf("image/jpeg")), ClipData.Item(uri))
        val w = (160 * activity.resources.displayMetrics.density).toInt()
        val h = (w / (bmp.width.toFloat() / bmp.height)).toInt().coerceAtMost(w * 2)
        val thumb = ImageView(activity).apply {
            setImageBitmap(Bitmap.createScaledBitmap(bmp, w, h, true))
            layout(0, 0, w, h)
        }
        view.startDragAndDrop(
            clip,
            object : View.DragShadowBuilder(thumb) {
                override fun onProvideShadowMetrics(size: Point, touch: Point) {
                    size.set(w, h); touch.set(w / 2, h / 2)
                }
                override fun onDrawShadow(canvas: Canvas) = thumb.draw(canvas)
            },
            null,
            View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ,
        )
    }
}

/**
 * «Control remoto»: pantalla grande y sencilla para la lectura en voz alta con el
 * teléfono doblado (pantalla exterior): portada, progreso, pasar página y pausa.
 */
class RemoteControlPanel(
    private val activity: Activity,
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
    private val onToggle: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val ctx = activity
    val view = FrameLayout(ctx).apply {
        setBackgroundColor(0xFF0E0E10.toInt())
        isClickable = true
        visibility = View.GONE
    }
    private val cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val title = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 2
    }
    private val chapter = TextView(ctx).apply {
        setTextColor(0xFFB0B0B5.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        maxLines = 2
    }
    private val progress = LinearProgressIndicator(ctx).apply {
        max = 1000
        trackThickness = ctx.dpi(5)
        trackCornerRadius = ctx.dpi(3)
        trackColor = 0x33FFFFFF
    }
    private val page = TextView(ctx).apply {
        setTextColor(0xFFB0B0B5.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
    }
    private lateinit var play: TextView
    private var accent = 0xFFFF9F0A.toInt()

    init {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dpi(24), ctx.dpi(48), ctx.dpi(24), ctx.dpi(32))
        }
        col.addView(androidx.cardview.widget.CardView(ctx).apply {
            radius = ctx.dp(8f)
            cardElevation = ctx.dp(10f)
            addView(cover)
        }, LinearLayout.LayoutParams(ctx.dpi(150), ctx.dpi(225)))
        col.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dpi(24) })
        col.addView(chapter, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dpi(6) })
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(progress, LinearLayout.LayoutParams(-1, -2))
        col.addView(page, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dpi(6) })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun big(label: String, sizeSp: Float, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            background = roundRect(0x22FFFFFF, ctx.dp(40f))
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
        row.addView(big("‹", 34f, onPrev), LinearLayout.LayoutParams(ctx.dpi(76), ctx.dpi(76)))
        play = big("❚❚", 28f, onToggle)
        row.addView(play, LinearLayout.LayoutParams(ctx.dpi(96), ctx.dpi(96)).apply {
            marginStart = ctx.dpi(22); marginEnd = ctx.dpi(22)
        })
        row.addView(big("›", 34f, onNext), LinearLayout.LayoutParams(ctx.dpi(76), ctx.dpi(76)))
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dpi(28) })
        col.addView(TextView(ctx).apply {
            text = "Volver al libro"
            setTextColor(0xFFB0B0B5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, ctx.dpi(24), 0, ctx.dpi(8))
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(-1, -2))
        view.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    val isShowing get() = view.visibility == View.VISIBLE

    fun show() {
        if (isShowing) return
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(220).start()
    }

    fun hide() {
        if (!isShowing) return
        view.animate().alpha(0f).setDuration(180).withEndAction { view.visibility = View.GONE }.start()
    }

    fun update(coverBmp: Bitmap?, bookTitle: String, chapterTitle: String, pageNum: Int, total: Int, playing: Boolean, accentColor: Int) {
        accent = accentColor
        if (coverBmp != null) cover.setImageBitmap(coverBmp) else cover.setBackgroundResource(R.drawable.cover_placeholder)
        title.text = bookTitle
        chapter.text = chapterTitle
        progress.setIndicatorColor(accent)
        progress.progress = if (total > 1) (pageNum * 1000 / (total - 1)) else 0
        page.text = "Página ${pageNum + 1} de $total"
        play.text = if (playing) "❚❚" else "▶"
        play.background = roundRect(if (playing) accent else 0x22FFFFFF, ctx.dp(48f))
    }
}
