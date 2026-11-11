package com.emmanuel.epubfold

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Muestra una o dos páginas de un cómic o PDF.
 *
 * - Sin zoom: arrastrar levanta la hoja como en un libro de papel (o la desliza, según
 *   el ajuste «Pasar página»); tocar los bordes pasa página con la misma animación.
 * - Zoom: pellizcar o doble toque (animado), arrastrar para moverse con inercia. Al
 *   llegar al borde de la página y seguir deslizando, se pasa de página.
 */
class SpreadView(context: Context) : View(context) {

    /** Lo que se dibuja en un grupo de páginas (1 o 2): proporciones e imágenes. */
    class Content(val aspects: List<Float>, val bitmaps: List<Bitmap?>)

    var onCenterTap: (() -> Unit)? = null
    var onSize: (() -> Unit)? = null
    /** Índice del grupo vecino al avanzar/retroceder, o null si no hay. */
    var neighbor: (forward: Boolean) -> Int? = { null }
    /** Contenido de cualquier grupo (para dibujar la página que aparece debajo). */
    var slotContent: (Int) -> Content? = { null }
    /** Se llama al terminar de pasar página, con el grupo nuevo. */
    var onFlipped: ((Int) -> Unit)? = null
    /** Se llama cuando el zoom se asienta (para pedir imágenes más nítidas). */
    var onZoomSettled: ((Float) -> Unit)? = null
    /** Se llama al intentar pasar más allá del principio o del final. */
    var onEdgeReached: ((forward: Boolean) -> Unit)? = null

    var rtl = false
    var animation = PageAnimation.CURL

    /** Lectura viñeta a viñeta: los bordes y deslizar mueven entre viñetas. */
    var panelMode = false
    /** Paso a la viñeta siguiente/anterior; false si no quedan en la página (entonces se pasa página). */
    var onPanelStep: ((forward: Boolean) -> Boolean)? = null

    /** Mantener pulsada una página: arrastrarla a otra app. Recibe el índice de la imagen tocada (0 o 1). */
    var onLongPressPage: ((index: Int) -> Unit)? = null

    /** Contraste, brillo y blanco y negro aplicados al dibujar las páginas. */
    var imageFilter: android.graphics.ColorFilter? = null
        set(value) {
            field = value
            paint.colorFilter = value
            invalidate()
        }
    var bgColor = 0xFF151517.toInt()

    var slotIndex = 0
        private set
    private var aspects: List<Float> = emptyList()
    private var bitmaps: List<Bitmap?> = emptyList()
    private var hiRes: List<Bitmap?>? = null

    val zoom get() = scale

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholder = Paint().apply { color = 0xFF2A2A2E.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BACK_PAPER
        setShadowLayer(16 * resources.displayMetrics.density, 0f, 0f, 0xAA000000.toInt())
    }
    private val creasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slideShadow = Paint()
    private val spinePaint = Paint()

    // ---------------------------------------------------------------- contenido

    /** Cambia el grupo visible. Al cambiar de grupo se quita el zoom. */
    fun setSlot(index: Int, aspects: List<Float>, bitmaps: List<Bitmap?>) {
        if (index != slotIndex || aspects != this.aspects) {
            resetZoom()
            hiRes = null
        }
        slotIndex = index
        this.aspects = aspects
        this.bitmaps = bitmaps
        invalidate()
    }

    /** Versiones más nítidas del grupo actual para el zoom. */
    fun setHiRes(index: Int, list: List<Bitmap?>) {
        if (index != slotIndex) return
        hiRes = list
        invalidate()
    }

    private fun contentOf(slot: Int): Content? =
        if (slot == slotIndex) Content(aspects, hiRes ?: bitmaps) else slotContent(slot)

    /** Rectángulos de cada página ajustados a la vista (sin zoom). */
    private fun rectsFor(asp: List<Float>): List<RectF> {
        if (asp.isEmpty() || width == 0) return emptyList()
        val totalAspect = asp.sum().coerceAtLeast(0.05f)
        var h = height.toFloat()
        var w = h * totalAspect
        if (w > width) {
            w = width.toFloat()
            h = w / totalAspect
        }
        var x = (width - w) / 2
        val y = (height - h) / 2
        return asp.map { a ->
            val pw = h * a
            RectF(x, y, x + pw, y + h).also { x += pw }
        }
    }

    private fun drawSlot(c: Canvas, slot: Int) {
        c.drawColor(bgColor)
        val content = contentOf(slot) ?: return
        val rects = rectsFor(content.aspects)
        rects.forEachIndexed { i, r ->
            val bmp = content.bitmaps.getOrNull(i)
            if (bmp == null || bmp.isRecycled) c.drawRect(r, placeholder)
            else c.drawBitmap(bmp, null, r, paint)
        }
        // Sombra suave del lomo entre dos páginas, como en un libro abierto
        if (rects.size == 2) {
            val sx = rects[0].right
            val sw = rects[0].width() * 0.06f
            spinePaint.shader = LinearGradient(
                sx - sw, 0f, sx + sw, 0f,
                intArrayOf(Color.TRANSPARENT, 0x38000000, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
            )
            c.drawRect(sx - sw, rects[0].top, sx + sw, rects[0].bottom, spinePaint)
        }
    }

    /** Dibuja una zona de un grupo: 0 entero, 1 mitad izquierda, 2 mitad derecha. */
    private fun drawRegion(c: Canvas, r: Region) {
        c.save()
        when (r.part) {
            1 -> c.clipRect(0f, 0f, width / 2f, height.toFloat())
            2 -> c.clipRect(width / 2f, 0f, width.toFloat(), height.toFloat())
        }
        drawSlot(c, r.slot)
        c.restore()
    }

    // ---------------------------------------------------------------- dibujo

    override fun onDraw(canvas: Canvas) {
        val f = flip
        if (f != null) {
            if (animation == PageAnimation.SLIDE) drawSlide(canvas, f) else drawCurl(canvas, f)
            return
        }
        canvas.drawColor(bgColor)
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        drawSlot(canvas, slotIndex)
        canvas.restore()
    }

    // ---------------------------------------------------------------- giro de hoja

    private data class Region(val slot: Int, val part: Int)

    private class Flip(
        val target: Int,
        val liftLeft: Float,
        val liftRight: Float,
        val cornerX: Float,
        val spineX: Float,
        val front: Region,
        val under: Region,
        val back: Region?,          // null = reverso de papel
        val static: Region?,
        val completeToFlipped: Boolean,
        val forward: Boolean,
        var cornerY: Float = 0f,
    ) {
        val flippedX get() = 2 * spineX - cornerX
    }

    private var flip: Flip? = null
    private val t = PointF()
    private val start = PointF()
    private var dragFactor = 1f
    private var flipAnimator: ValueAnimator? = null

    private val half = Path()
    private val rectPath = Path()
    private val lifted = Path()
    private val flapPath = Path()
    private val reflect = Matrix()
    private val reflectValues = FloatArray(9)

    private fun pageCount(slot: Int) = contentOf(slot)?.aspects?.size ?: 1

    private fun createFlip(forward: Boolean, target: Int, cornerY: Float): Flip {
        val w = width.toFloat()
        val from = slotIndex
        // La hoja que se levanta: el borde derecho al avanzar en occidental, el izquierdo en manga
        val liftRightEdge = forward != rtl
        val spread = pageCount(from) == 2 && pageCount(target) == 2
        val f = if (spread) {
            if (liftRightEdge) Flip(target, w / 2, w, w, w / 2, Region(from, 2), Region(target, 2),
                Region(target, 1), Region(from, 1), true, forward)
            else Flip(target, 0f, w / 2, 0f, w / 2, Region(from, 1), Region(target, 1),
                Region(target, 2), Region(from, 2), true, forward)
        } else {
            // Una página: el lomo está en el borde izquierdo (o derecho en manga)
            val cornerX = if (rtl) 0f else w
            val spineX = if (rtl) w else 0f
            if (forward) Flip(target, 0f, w, cornerX, spineX, Region(from, 0), Region(target, 0), null, null, true, true)
            else Flip(target, 0f, w, cornerX, spineX, Region(target, 0), Region(from, 0), null, null, false, false)
        }
        f.cornerY = cornerY
        return f
    }

    private fun progress(k: Flip): Float =
        ((k.cornerX - t.x) / (k.cornerX - k.flippedX)).coerceIn(0f, 1f)

    private fun constrain(k: Flip, p: PointF) {
        val pageW = k.liftRight - k.liftLeft
        val h = height.toFloat()
        if (k.cornerX > k.spineX) p.x = min(p.x, k.cornerX) else p.x = max(p.x, k.cornerX)
        clampToCircle(p, k.spineX, k.cornerY, pageW - 0.5f)
        clampToCircle(p, k.spineX, if (k.cornerY > 0f) 0f else h, hypot(pageW, h) - 0.5f)
    }

    private fun clampToCircle(p: PointF, cx: Float, cy: Float, r: Float) {
        val d = hypot(p.x - cx, p.y - cy)
        if (d > r && d > 0f) {
            p.x = cx + (p.x - cx) * r / d
            p.y = cy + (p.y - cy) * r / d
        }
    }

    private fun drawCurl(c: Canvas, k: Flip) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(bgColor)
        k.static?.let { drawRegion(c, it) }
        val dx = k.cornerX - t.x
        val dy = k.cornerY - t.y
        val len = hypot(dx, dy)
        if (len < 0.5f) {
            drawRegion(c, k.front)
            return
        }
        val nx = dx / len
        val ny = dy / len
        val mx = (t.x + k.cornerX) / 2
        val my = (t.y + k.cornerY) / 2
        val ux = -ny
        val uy = nx
        val big = (w + h) * 4
        half.rewind()
        half.moveTo(mx + ux * big, my + uy * big)
        half.lineTo(mx - ux * big, my - uy * big)
        half.lineTo(mx - ux * big + nx * big, my - uy * big + ny * big)
        half.lineTo(mx + ux * big + nx * big, my + uy * big + ny * big)
        half.close()
        rectPath.rewind()
        rectPath.addRect(k.liftLeft, 0f, k.liftRight, h, Path.Direction.CW)
        lifted.rewind()
        lifted.op(rectPath, half, Path.Op.INTERSECT)

        // 1) parte de la hoja que sigue plana
        c.save()
        c.clipRect(k.liftLeft, 0f, k.liftRight, h)
        c.clipOutPath(lifted)
        drawRegion(c, k.front)
        c.restore()

        // 2) lo que aparece debajo + sombra
        c.save()
        c.clipPath(lifted)
        drawRegion(c, k.under)
        val sw = min(len * 0.4f, 80 * density) + 1f
        shadowPaint.shader = LinearGradient(
            mx, my, mx + nx * sw, my + ny * sw,
            intArrayOf(0x99000000.toInt(), 0x26000000, Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(lifted, shadowPaint)
        c.restore()

        // 3) reverso: reflejo de la zona levantada sobre la línea de doblez
        val a = 1 - 2 * nx * nx
        val b = -2 * nx * ny
        val d = 1 - 2 * ny * ny
        reflectValues[0] = a; reflectValues[1] = b; reflectValues[2] = mx - (a * mx + b * my)
        reflectValues[3] = b; reflectValues[4] = d; reflectValues[5] = my - (b * mx + d * my)
        reflectValues[6] = 0f; reflectValues[7] = 0f; reflectValues[8] = 1f
        reflect.setValues(reflectValues)
        flapPath.rewind()
        lifted.transform(reflect, flapPath)
        c.drawPath(flapPath, flapPaint)
        c.save()
        c.clipPath(flapPath)
        c.save()
        c.concat(reflect)
        val back = k.back
        if (back == null) {
            // Una página: papel con el dibujo al trasluz
            c.save()
            c.clipRect(k.liftLeft, 0f, k.liftRight, h)
            drawRegion(c, k.front)
            c.restore()
            c.drawColor((BACK_PAPER and 0x00FFFFFF) or 0xE0000000.toInt())
        } else {
            // Doble página: el reverso es la página real del otro lado
            c.scale(-1f, 1f, k.spineX, 0f)
            drawRegion(c, back)
        }
        c.restore()
        val fw = len / 2 + 1f
        creasePaint.shader = LinearGradient(
            mx, my, mx - nx * fw, my - ny * fw,
            intArrayOf(0x66000000, 0x14000000, 0x18FFFFFF, 0x24000000),
            floatArrayOf(0f, 0.22f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPaint(creasePaint)
        c.restore()
    }

    /** «Deslizar»: la hoja actual se aparta y deja ver la siguiente. */
    private fun drawSlide(c: Canvas, k: Flip) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = progress(k)
        val done = if (k.completeToFlipped) p else 1f - p
        // En manga la hoja sale hacia la derecha
        val dir = if (rtl) 1f else -1f
        val moving: Int
        val x: Float
        if (k.forward) {
            drawSlot(c, k.target)
            moving = slotIndex
            x = dir * done * w
        } else {
            drawSlot(c, slotIndex)
            moving = k.target
            x = dir * (1f - done) * w
        }
        val sw = 24 * density
        val edge = if (dir < 0) x + w else x
        slideShadow.shader = LinearGradient(
            edge, 0f, edge - dir * sw, 0f,
            intArrayOf(0x88000000.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        c.drawRect(min(edge, edge - dir * sw), 0f, max(edge, edge - dir * sw), h, slideShadow)
        c.save()
        c.translate(x, 0f)
        c.clipRect(0f, 0f, w, h)
        drawSlot(c, moving)
        c.restore()
    }

    /** Pasa página con animación (toques en los bordes, teclas de volumen, manos libres). */
    fun animateTurn(forward: Boolean) {
        flipAnimator?.end()
        if (width == 0) return
        val target = neighbor(forward)
        if (target == null) {
            onEdgeReached?.invoke(forward)
            return
        }
        if (animation == PageAnimation.NONE) {
            finishFlip(target)
            return
        }
        resetZoom()
        val k = createFlip(forward, target, height.toFloat())
        flip = k
        if (k.completeToFlipped) t.set(k.cornerX, k.cornerY) else t.set(k.flippedX, k.cornerY)
        animateFlipTo(k, k.completeToFlipped, bump = animation == PageAnimation.CURL)
    }

    private fun animateFlipTo(k: Flip, toFlipped: Boolean, bump: Boolean) {
        val fromX = t.x
        val fromY = t.y
        val toX = if (toFlipped) k.flippedX else k.cornerX
        val toY = k.cornerY
        val travel = abs(k.flippedX - k.cornerX).coerceAtLeast(1f)
        val dist = abs(toX - fromX) / travel
        val lift = (if (k.cornerY > 0) -1f else 1f) * height * 0.16f
        flipAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when {
                bump -> 640L
                animation == PageAnimation.SLIDE -> (150 + 260 * dist).toLong()
                else -> (190 + 420 * dist).toLong()
            }
            interpolator = DecelerateInterpolator(if (bump) 1.2f else 1.6f)
            addUpdateListener {
                val v = it.animatedValue as Float
                t.set(fromX + (toX - fromX) * v, fromY + (toY - fromY) * v)
                if (bump) t.y += lift * sin(PI * v).toFloat()
                if (animation == PageAnimation.CURL) constrain(k, t)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (flipAnimator === animation) flipAnimator = null
                    if (flip !== k) return
                    flip = null
                    if (!cancelled && toFlipped == k.completeToFlipped) finishFlip(k.target)
                    invalidate()
                }
            })
            start()
        }
    }

    private fun finishFlip(target: Int) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onFlipped?.invoke(target)
    }

    // ---------------------------------------------------------------- zoom

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var zoomAnimator: ValueAnimator? = null
    private val scroller = OverScroller(context)

    private fun resetZoom() {
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
        scale = 1f; tx = 0f; ty = 0f
    }

    /** Límites de desplazamiento para la escala actual; centra si la página cabe. */
    private fun panBounds(s: Float): FloatArray {
        val rects = rectsFor(aspects)
        val r = if (rects.isEmpty()) RectF(0f, 0f, width.toFloat(), height.toFloat())
        else RectF(rects.first().left, rects.first().top, rects.last().right, rects.last().bottom)
        val cw = r.width() * s
        val ch = r.height() * s
        val minX: Float
        val maxX: Float
        if (cw <= width) {
            minX = width / 2f - r.centerX() * s; maxX = minX
        } else {
            minX = width - r.right * s; maxX = -r.left * s
        }
        val minY: Float
        val maxY: Float
        if (ch <= height) {
            minY = height / 2f - r.centerY() * s; maxY = minY
        } else {
            minY = height - r.bottom * s; maxY = -r.top * s
        }
        return floatArrayOf(minX, maxX, minY, maxY)
    }

    private fun clampPan() {
        if (scale <= 1.01f) {
            scale = 1f; tx = 0f; ty = 0f
            return
        }
        val b = panBounds(scale)
        tx = tx.coerceIn(b[0], b[1])
        ty = ty.coerceIn(b[2], b[3])
    }

    private fun animateZoom(toScale: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
        val s0 = scale
        val x0 = tx
        val y0 = ty
        // Punto de la página bajo el dedo; tras el zoom queda en el mismo sitio
        val px = (focusX - tx) / scale
        val py = (focusY - ty) / scale
        var endX = focusX - px * toScale
        var endY = focusY - py * toScale
        if (toScale <= 1.01f) {
            endX = 0f; endY = 0f
        } else {
            val b = panBounds(toScale)
            endX = endX.coerceIn(b[0], b[1])
            endY = endY.coerceIn(b[2], b[3])
        }
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val v = it.animatedValue as Float
                scale = s0 + (toScale - s0) * v
                tx = x0 + (endX - x0) * v
                ty = y0 + (endY - y0) * v
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (toScale <= 1.01f) resetZoom()
                    settleZoom()
                }
            })
            start()
        }
    }

    /**
     * Encuadra la zona [r] de la página (rectángulo normalizado 0..1 de la primera página
     * visible) para que llene la pantalla; null vuelve a la página entera.
     */
    fun focusOn(r: FloatArray?, animate: Boolean = true) {
        if (width == 0) return
        val page = rectsFor(aspects).firstOrNull() ?: return
        if (r == null) {
            if (animate) animateZoomTo(1f, 0f, 0f) else { resetZoom(); invalidate() }
            return
        }
        val pad = 0.012f
        val l = page.left + page.width() * (r[0] - pad).coerceAtLeast(0f)
        val t = page.top + page.height() * (r[1] - pad).coerceAtLeast(0f)
        val rr = page.left + page.width() * (r[2] + pad).coerceAtMost(1f)
        val b = page.top + page.height() * (r[3] + pad).coerceAtMost(1f)
        val s = min(width / (rr - l), height / (b - t)).coerceIn(1f, MAX_ZOOM)
        val endX = width / 2f - (l + rr) / 2f * s
        val endY = height / 2f - (t + b) / 2f * s
        if (animate) animateZoomTo(s, endX, endY) else {
            scale = s; tx = endX; ty = endY
            invalidate()
            settleZoom()
        }
    }

    private fun animateZoomTo(toScale: Float, endX: Float, endY: Float) {
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
        val s0 = scale
        val x0 = tx
        val y0 = ty
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val v = it.animatedValue as Float
                scale = s0 + (toScale - s0) * v
                tx = x0 + (endX - x0) * v
                ty = y0 + (endY - y0) * v
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (toScale <= 1.01f) resetZoom()
                    settleZoom()
                }
            })
            start()
        }
    }

    private val settle = Runnable { onZoomSettled?.invoke(scale) }

    private fun settleZoom() {
        removeCallbacks(settle)
        postDelayed(settle, 180)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            tx = scroller.currX.toFloat()
            ty = scroller.currY.toFloat()
            invalidate()
        }
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            zoomAnimator?.cancel()
            scroller.forceFinished(true)
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(1f, MAX_ZOOM)
            val f = ns / scale
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            scale = ns
            clampPan()
            invalidate()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            settleZoom()
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (multiTouch) return true
            val third = width / 3f
            val zoomed = scale > 1.01f
            if (panelMode) {
                when {
                    e.x < third -> panelStep(rtl)
                    e.x > width - third -> panelStep(!rtl)
                    else -> onCenterTap?.invoke()
                }
                return true
            }
            when {
                // Con zoom, los bordes no pasan página: así se puede tocar sin miedo mientras se lee
                !zoomed && e.x < third -> animateTurn(rtl)
                !zoomed && e.x > width - third -> animateTurn(!rtl)
                else -> onCenterTap?.invoke()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (multiTouch || dragging || flip != null) return
            // Qué página está bajo el dedo (teniendo en cuenta el zoom)
            val px = (e.x - tx) / scale
            val rects = rectsFor(aspects)
            val i = rects.indexOfFirst { px >= it.left && px <= it.right }.coerceAtLeast(0)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPressPage?.invoke(i)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (panelMode) {
                // Ver la página entera un momento; el siguiente paso vuelve a la viñeta
                if (scale > 1.01f) focusOn(null) else onPanelStep?.invoke(true)?.let { if (!it) animateTurn(true) }
                return true
            }
            if (scale > 1.01f) animateZoom(1f, e.x, e.y) else animateZoom(DOUBLE_TAP_ZOOM, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= 1.01f || multiTouch) return false
            tx -= dx
            ty -= dy
            clampPan()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (panelMode && !multiTouch && abs(vx) > abs(vy) * 1.3f && abs(vx) > flingVelocity) {
                // Deslizar hacia la izquierda: siguiente viñeta (al revés en manga)
                panelStep((vx < 0) != rtl)
                return true
            }
            if (scale <= 1.01f || multiTouch) return false
            val b = panBounds(scale)
            // En el borde de la página y deslizando hacia fuera: siguiente o anterior
            val atRight = tx <= b[0] + 1f
            val atLeft = tx >= b[1] - 1f
            if (abs(vx) > abs(vy) * 1.5f && abs(vx) > flingVelocity) {
                if (vx < 0 && atRight && panStartedAtEdge) { animateTurn(!rtl); return true }
                if (vx > 0 && atLeft && panStartedAtEdge) { animateTurn(rtl); return true }
            }
            scroller.fling(
                tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(),
                b[0].toInt(), b[1].toInt(), b[2].toInt(), b[3].toInt(),
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    private fun panelStep(forward: Boolean) {
        val moved = onPanelStep?.invoke(forward) ?: false
        if (!moved) animateTurn(forward)
    }

    // ---------------------------------------------------------------- gestos

    private var multiTouch = false
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var panStartedAtEdge = false
    private var velocity: VelocityTracker? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flipAnimator?.end()
                multiTouch = false
                dragging = false
                downX = e.x
                downY = e.y
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                if (scale > 1.01f) {
                    val b = panBounds(scale)
                    panStartedAtEdge = tx <= b[0] + 1f || tx >= b[1] - 1f
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                // Un segundo dedo cancela el giro de hoja: es un pellizco
                flip?.let { k -> if (dragging) { dragging = false; animateFlipTo(k, !k.completeToFlipped, false) } }
            }
        }
        velocity?.addMovement(e)
        scaler.onTouchEvent(e)

        // Arrastre para pasar la hoja (sólo sin zoom y con un dedo)
        if (scale <= 1.01f && !multiTouch && !scaler.isInProgress) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    if (!dragging && abs(dx) > touchSlop * 1.5f && abs(dx) > abs(e.y - downY)) {
                        // Arrastrar hacia la izquierda avanza (al revés en manga)
                        val forward = (dx < 0) != rtl
                        val target = neighbor(forward)
                        if (target == null) {
                            onEdgeReached?.invoke(forward)
                            downX = Float.NaN
                        } else if (animation == PageAnimation.NONE) {
                            finishFlip(target)
                            downX = Float.NaN
                        } else if (!downX.isNaN()) {
                            val k = createFlip(forward, target, if (downY > height / 2f) height.toFloat() else 0f)
                            flip = k
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            if (k.completeToFlipped) start.set(k.cornerX, k.cornerY) else start.set(k.flippedX, k.cornerY)
                            val travel = abs(k.flippedX - k.cornerX)
                            dragFactor = if (animation == PageAnimation.SLIDE) travel / width
                            else max(1.2f, travel / (width * 0.8f))
                            downX = e.x
                            downY = e.y
                        }
                    }
                    if (dragging) {
                        val k = flip ?: return true
                        val dy = if (animation == PageAnimation.SLIDE) 0f else (e.y - downY) * 0.8f
                        t.set(start.x + (e.x - downX) * dragFactor, start.y + dy)
                        if (animation == PageAnimation.CURL) constrain(k, t)
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        val k = flip
                        if (k != null) {
                            velocity?.computeCurrentVelocity(1000)
                            val vx = velocity?.xVelocity ?: 0f
                            val dir = if (k.flippedX > k.cornerX) 1f else -1f
                            val along = vx * dir
                            val pr = progress(k)
                            val toFlipped = when {
                                along > flingVelocity -> true
                                along < -flingVelocity -> false
                                k.completeToFlipped -> pr > 0.22f
                                else -> pr > 0.78f
                            }
                            animateFlipTo(k, toFlipped, bump = false)
                        }
                        velocity?.recycle()
                        velocity = null
                        return true
                    }
                }
            }
        }
        if (!dragging) gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocity?.recycle()
            velocity = null
            if (scale > 1.01f) settleZoom()
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        flipAnimator?.cancel()
        flip = null
        resetZoom()
        onSize?.invoke()
    }

    companion object {
        private const val MAX_ZOOM = 6f
        private const val DOUBLE_TAP_ZOOM = 2.5f
        /** Color del reverso de una hoja de cómic (papel ligeramente cálido). */
        private const val BACK_PAPER = 0xFFF1EEE6.toInt()
    }
}
