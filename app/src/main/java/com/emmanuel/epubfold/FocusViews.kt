package com.emmanuel.epubfold

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Regla de lectura: oscurece la página salvo una franja de unas pocas líneas.
 * La franja se arrastra con el dedo; los toques fuera de ella llegan al libro.
 */
class ReadingRulerView(context: Context) : View(context) {

    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(2f) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bandTop = -1f
    private var dragOffset = 0f
    private var dragging = false

    /** Alto de la franja en píxeles (≈ 3 líneas de texto). */
    var bandHeight = context.dp(96f)
        set(value) {
            field = value
            invalidate()
        }

    fun setColors(accent: Int, dark: Boolean) {
        edge.color = accent
        handle.color = accent
        dim.color = if (dark) Color.argb(170, 0, 0, 0) else Color.argb(120, 0, 0, 0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (bandTop < 0f) bandTop = height * 0.38f
        bandTop = bandTop.coerceIn(0f, (height - bandHeight).coerceAtLeast(0f))
        val bottom = bandTop + bandHeight
        canvas.drawRect(0f, 0f, width.toFloat(), bandTop, dim)
        canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), dim)
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, edge)
        // Asa a la derecha para arrastrar
        val hw = context.dp(6f)
        val hh = context.dp(36f)
        val cy = bandTop + bandHeight / 2
        canvas.drawRoundRect(RectF(width - hw * 2, cy - hh / 2, width - hw, cy + hh / 2), hw, hw, handle)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inBand = e.y >= bandTop - context.dp(12f) && e.y <= bandTop + bandHeight + context.dp(12f)
                // Sólo capturamos el gesto si empieza en la franja y cerca del asa o es un arrastre vertical
                if (!inBand || e.x < width * 0.7f) return false
                dragging = true
                dragOffset = e.y - bandTop
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                bandTop = e.y - dragOffset
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                return true
            }
        }
        return false
    }

    /** Vuelve a colocar la franja al principio de la página (al pasar página). */
    fun resetToTop() {
        bandTop = height * 0.08f
        invalidate()
    }

    /** Mueve la franja una altura hacia abajo (o arriba). */
    fun step(down: Boolean) {
        bandTop += if (down) bandHeight * 0.9f else -bandHeight * 0.9f
        invalidate()
    }
}

/**
 * Lienzo para escribir o dibujar a mano (pensado para el S Pen). Los trazos se
 * guardan como vectores normalizados, así se adaptan al plegar y desplegar.
 * Con el botón del S Pen pulsado (o la goma) se borran trazos completos y,
 * en cuanto se detecta un lápiz, los toques con el dedo se ignoran (apoyar la mano).
 */
class SketchView(context: Context) : View(context) {

    class Stroke(val color: Int, val width: Float, val points: FloatArray)

    val strokes = ArrayList<Stroke>()
    var penColor = Color.BLACK
    var onChanged: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var current: ArrayList<Float>? = null
    private var currentWidth = 0f
    private var stylusSeen = false
    private var erasing = false
    /** Gesto con dos dedos: es para desplazar la hoja, no para dibujar. */
    private var scrolling = false

    private fun baseWidth() = context.dp(2.6f)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        strokes.forEach { drawStroke(canvas, it.color, it.width, it.points, w) }
        current?.let { drawStroke(canvas, penColor, currentWidth, it.toFloatArray(), w) }
    }

    private fun drawStroke(canvas: Canvas, color: Int, strokeW: Float, pts: FloatArray, w: Float) {
        if (pts.size < 2) return
        paint.color = color
        paint.strokeWidth = strokeW * w
        val path = Path()
        path.moveTo(pts[0] * w, pts[1] * w)
        var i = 2
        while (i + 1 < pts.size) {
            path.lineTo(pts[i] * w, pts[i + 1] * w)
            i += 2
        }
        if (pts.size == 2) path.lineTo(pts[0] * w + 0.1f, pts[1] * w)
        canvas.drawPath(path, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)
        val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (isStylus) stylusSeen = true
        if (stylusSeen && !isStylus) return true // palma o dedo mientras se usa el lápiz
        val w = width.toFloat().coerceAtLeast(1f)
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !isStylus) {
            current = null
            scrolling = true
            parent?.requestDisallowInterceptTouchEvent(false)
            invalidate()
        }
        if (scrolling) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) scrolling = false
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                erasing = tool == MotionEvent.TOOL_TYPE_ERASER ||
                    (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                if (erasing) {
                    eraseAt(e.x / w, e.y / w)
                } else {
                    val pressure = if (isStylus) e.pressure.coerceIn(0.2f, 1.2f) else 0.7f
                    currentWidth = baseWidth() * (0.5f + pressure) / w
                    current = arrayListOf(e.x / w, e.y / w)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (erasing) {
                    for (h in 0 until e.historySize) eraseAt(e.getHistoricalX(h) / w, e.getHistoricalY(h) / w)
                    eraseAt(e.x / w, e.y / w)
                } else current?.let { c ->
                    for (h in 0 until e.historySize) {
                        c += e.getHistoricalX(h) / w; c += e.getHistoricalY(h) / w
                    }
                    c += e.x / w; c += e.y / w
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { c ->
                    if (c.size >= 2) strokes += Stroke(penColor, currentWidth, c.toFloatArray())
                    current = null
                    onChanged?.invoke()
                }
                if (erasing) onChanged?.invoke()
                erasing = false
                invalidate()
            }
        }
        return true
    }

    private fun eraseAt(x: Float, y: Float) {
        val r = context.dp(14f) / width.coerceAtLeast(1)
        val removed = strokes.removeAll { s ->
            var hit = false
            var i = 0
            while (i + 1 < s.points.size && !hit) {
                if (abs(s.points[i] - x) < r && abs(s.points[i + 1] - y) < r &&
                    hypot(s.points[i] - x, s.points[i + 1] - y) < r
                ) hit = true
                i += 2
            }
            hit
        }
        if (removed) invalidate()
    }

    fun clear() {
        strokes.clear()
        invalidate()
        onChanged?.invoke()
    }

    /** Alto que necesitan los trazos (para que el lienzo crezca al escribir abajo). */
    fun contentBottom(): Float {
        var maxY = 0f
        strokes.forEach { s -> var i = 1; while (i < s.points.size) { maxY = maxOf(maxY, s.points[i]); i += 2 } }
        return maxY * width
    }
}
