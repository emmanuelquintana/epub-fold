package com.emmanuel.epubfold

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Medidas de una página (en una o dos columnas). */
data class PageMetrics(
    val pageW: Float,
    val height: Float,
    val marginH: Float,
    val marginTop: Float,
    val marginBottom: Float,
) {
    val contentW: Int get() = (pageW - 2 * marginH).toInt()
    val contentH: Int get() = (height - marginTop - marginBottom).toInt()

    companion object {
        fun of(w: Int, h: Int, spread: Boolean, density: Float, marginScale: Float = 1f): PageMetrics {
            val pageW = if (spread) w / 2f else w.toFloat()
            val marginH = max(14 * density, max(22 * density, pageW * 0.075f) * marginScale)
            return PageMetrics(pageW, h.toFloat(), marginH, 56 * density, 46 * density)
        }
    }
}

/**
 * Vista que dibuja el libro (una página o doble página en el Fold abierto) y anima el
 * paso de página como un libro de papel: la esquina se levanta siguiendo el dedo, el
 * reverso de la hoja se refleja sobre la línea de doblez y se proyectan sombras.
 */
class BookView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onSizeReady(w: Int, h: Int)
        fun onPageChanged(page: Int)
        fun onCenterTap()
    }

    var listener: Listener? = null
    var chapterTitle: (Int) -> String = { "" }

    /** Indica si la página [index] tiene marcador (se dibuja una cinta). */
    var isBookmarked: (Int) -> Boolean = { false }

    /** Resaltado temporal de un resultado de búsqueda: capítulo, inicio, fin. */
    var highlight: Triple<Int, Int, Int>? = null
        set(value) {
            field = value
            invalidate()
        }

    var animation: PageAnimation = PageAnimation.CURL

    var accentColor: Int = 0xFFFF9500.toInt()
        set(value) {
            field = value
            ribbonPaint.color = value
            invalidate()
        }

    var paginator: Paginator? = null
        private set
    var metrics: PageMetrics? = null
        private set
    var spread = false
        private set
    var currentPage = 0
        private set

    var theme: ReaderTheme = ReaderTheme.ORIGINAL
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f

    private val paperPaint = Paint()
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        letterSpacing = 0.04f
    }
    private val spinePaintLeft = Paint()
    private val spinePaintRight = Paint()
    private val spinePaintSingle = Paint()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val creasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9500.toInt() }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slideShadowPaint = Paint()
    private val ribbonPath = Path()
    private val highlightPath = Path()

    // ---- estado del giro de página ----
    private class Curl(
        val liftLeft: Float,
        val liftRight: Float,
        val side: Int,            // 0 una página, 1 izquierda, 2 derecha
        val cornerX: Float,
        val cornerY: Float,
        val spineX: Float,        // borde cosido de la hoja que se levanta
        val front: Int,           // hoja que se levanta
        val under: Int,           // lo que aparece debajo
        val back: Int,            // reverso (doble página); -1 = papel
        val backLeft: Float,
        val backSide: Int,
        val staticPage: Int,      // la otra mitad en doble página; -1 = ninguna
        val staticLeft: Float,
        val staticSide: Int,
        val newPage: Int,
        val completeToFlipped: Boolean,
    ) {
        val flippedX get() = 2 * spineX - cornerX
        val paperBack get() = back == NO_BACK
    }

    private var curl: Curl? = null
    private val t = PointF()
    private val start = PointF()
    private var dragFactor = 1f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragging = false
    private var movedFar = false
    private var gestureConsumed = false
    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    private val half = Path()
    private val rectPath = Path()
    private val lifted = Path()
    private val flap = Path()
    private val reflect = Matrix()
    private val reflectValues = FloatArray(9)

    init {
        applyTheme()
        isHapticFeedbackEnabled = true
    }

    fun setContent(p: Paginator, m: PageMetrics, isSpread: Boolean, page: Int) {
        animator?.cancel()
        animator = null
        curl = null
        paginator = p
        metrics = m
        spread = isSpread
        headerPaint.textSize = 12 * resources.displayMetrics.scaledDensity
        buildSpineShaders()
        currentPage = align(page)
        invalidate()
    }

    fun clearContent() {
        animator?.cancel()
        curl = null
        paginator = null
        invalidate()
    }

    fun goTo(page: Int) {
        animator?.cancel()
        curl = null
        currentPage = align(page)
        invalidate()
        listener?.onPageChanged(currentPage)
    }

    private fun align(page: Int): Int {
        val count = paginator?.pageCount ?: 0
        val p = page.coerceIn(0, max(0, count - 1))
        return if (spread) p - p % 2 else p
    }

    private val step get() = if (spread) 2 else 1
    private fun canForward() = currentPage + step < (paginator?.pageCount ?: 0)
    private fun canBackward() = currentPage > 0

    private fun applyTheme() {
        paperPaint.color = theme.paper
        headerPaint.color = theme.secondary
        paginator?.paint?.color = theme.text
        paginator?.paint?.linkColor = theme.text
        flapPaint.color = theme.paper
        highlightPaint.color = if (theme.isDark) 0x66FFB340 else 0x66FFD60A
        flapPaint.setShadowLayer(14 * density, 0f, 0f, if (theme.isDark) 0xAA000000.toInt() else 0x55000000)
        buildSpineShaders()
    }

    private fun buildSpineShaders() {
        val m = metrics ?: return
        val sw = m.pageW * 0.09f
        val dark = if (theme.isDark) 0x50000000 else 0x30000000
        val mid = if (theme.isDark) 0x18000000 else 0x0E000000
        spinePaintLeft.shader = LinearGradient(
            m.pageW - sw, 0f, m.pageW, 0f,
            intArrayOf(Color.TRANSPARENT, mid, dark), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        spinePaintRight.shader = LinearGradient(
            m.pageW, 0f, m.pageW + sw, 0f,
            intArrayOf(dark, mid, Color.TRANSPARENT), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP
        )
        spinePaintSingle.shader = LinearGradient(
            0f, 0f, sw * 0.5f, 0f,
            intArrayOf(mid, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) listener?.onSizeReady(w, h)
    }

    // ------------------------------------------------------------------ dibujo

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.paper)
        val m = metrics ?: return
        if (paginator == null) return
        val k = curl
        if (k == null) {
            if (spread) {
                drawPage(canvas, currentPage, 0f, 1, m)
                drawPage(canvas, currentPage + 1, m.pageW, 2, m)
            } else {
                drawPage(canvas, currentPage, 0f, 0, m)
            }
        } else if (animation == PageAnimation.SLIDE) {
            drawSlide(canvas, k, m)
        } else {
            drawCurl(canvas, k, m)
        }
    }

    /** Dibuja una "vista" completa: una página o la doble página que empieza en [index]. */
    private fun drawSpread(c: Canvas, index: Int, m: PageMetrics) {
        if (spread) {
            drawPage(c, index, 0f, 1, m)
            drawPage(c, index + 1, m.pageW, 2, m)
        } else {
            drawPage(c, index, 0f, 0, m)
        }
    }

    /** Animación "Deslizar": la hoja actual se desliza y deja ver la siguiente, como en iOS. */
    private fun drawSlide(c: Canvas, k: Curl, m: PageMetrics) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = progress(k)
        val done = if (k.completeToFlipped) p else 1f - p
        val forward = k.newPage > currentPage
        val moving: Int
        val x: Float
        if (forward) {
            drawSpread(c, k.newPage, m)
            moving = currentPage
            x = -done * w
        } else {
            drawSpread(c, currentPage, m)
            moving = k.newPage
            x = -(1f - done) * w
        }
        val sw = 22 * density
        slideShadowPaint.shader = LinearGradient(
            x + w, 0f, x + w + sw, 0f,
            intArrayOf(if (theme.isDark) 0x88000000.toInt() else 0x40000000, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(x + w, 0f, x + w + sw, h, slideShadowPaint)
        c.save()
        c.translate(x, 0f)
        c.clipRect(0f, 0f, w, h)
        drawSpread(c, moving, m)
        c.restore()
    }

    private fun drawPage(c: Canvas, index: Int, left: Float, side: Int, m: PageMetrics) {
        val h = height.toFloat()
        c.save()
        c.clipRect(left, 0f, left + m.pageW, h)
        c.drawRect(left, 0f, left + m.pageW, h, paperPaint)
        val p = paginator
        if (p != null && index >= 0 && index < p.pageCount) {
            val page = p.pages[index]
            val cx = left + m.pageW / 2
            val title = TextUtils.ellipsize(chapterTitle(page.chapter), headerPaint, m.contentW.toFloat(), TextUtils.TruncateAt.END)
            c.drawText(title, 0, title.length, cx, m.marginTop * 0.52f, headerPaint)
            c.drawText((index + 1).toString(), cx, h - m.marginBottom * 0.38f, headerPaint)
            val layout = p.layouts[page.chapter]
            if (layout != null && page.endLine > page.startLine) {
                val top = layout.getLineTop(page.startLine).toFloat()
                val bottom = layout.getLineBottom(page.endLine - 1).toFloat()
                c.save()
                c.translate(left + m.marginH, m.marginTop - top)
                c.clipRect(0f, top, m.contentW.toFloat(), bottom)
                val hl = highlight
                if (hl != null && hl.first == page.chapter) {
                    val len = layout.text.length
                    val s0 = hl.second.coerceIn(0, len)
                    val s1 = hl.third.coerceIn(0, len)
                    if (s1 > s0) {
                        highlightPath.rewind()
                        layout.getSelectionPath(s0, s1, highlightPath)
                        c.drawPath(highlightPath, highlightPaint)
                    }
                }
                layout.draw(c)
                c.restore()
            }
        }
        if (p != null && index >= 0 && index < p.pageCount && isBookmarked(index)) {
            val rw = 18 * density
            val rh = 34 * density
            val rx = left + m.pageW - m.marginH * 0.55f - rw
            ribbonPath.rewind()
            ribbonPath.moveTo(rx, 0f)
            ribbonPath.lineTo(rx + rw, 0f)
            ribbonPath.lineTo(rx + rw, rh)
            ribbonPath.lineTo(rx + rw / 2, rh - rw * 0.42f)
            ribbonPath.lineTo(rx, rh)
            ribbonPath.close()
            c.drawPath(ribbonPath, ribbonPaint)
        }
        // sombra del lomo
        when (side) {
            1 -> c.drawRect(left, 0f, left + m.pageW, h, spinePaintLeft)
            2 -> c.drawRect(left, 0f, left + m.pageW, h, spinePaintRight)
            else -> c.drawRect(left, 0f, left + m.pageW, h, spinePaintSingle)
        }
        c.restore()
    }

    private fun drawCurl(c: Canvas, k: Curl, m: PageMetrics) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (k.staticPage != NO_PAGE) drawPage(c, k.staticPage, k.staticLeft, k.staticSide, m)

        val dx = k.cornerX - t.x
        val dy = k.cornerY - t.y
        val len = hypot(dx, dy)
        if (len < 0.5f) {
            drawPage(c, k.front, k.liftLeft, k.side, m)
            return
        }
        // n: normal de la línea de doblez apuntando hacia la esquina levantada
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
        c.clipOutPath(lifted)
        drawPage(c, k.front, k.liftLeft, k.side, m)
        c.restore()

        // 2) página que se descubre debajo + sombra de la hoja levantada
        c.save()
        c.clipPath(lifted)
        drawPage(c, k.under, k.liftLeft, k.side, m)
        val sw = min(len * 0.4f, 70 * density) + 1f
        shadowPaint.shader = LinearGradient(
            mx, my, mx + nx * sw, my + ny * sw,
            intArrayOf(if (theme.isDark) 0x99000000.toInt() else 0x66000000, 0x22000000, Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(lifted, shadowPaint)
        c.restore()

        // 3) reverso de la hoja: reflexión de la zona levantada sobre la línea de doblez
        val a = 1 - 2 * nx * nx
        val b = -2 * nx * ny
        val d = 1 - 2 * ny * ny
        reflectValues[0] = a; reflectValues[1] = b; reflectValues[2] = mx - (a * mx + b * my)
        reflectValues[3] = b; reflectValues[4] = d; reflectValues[5] = my - (b * mx + d * my)
        reflectValues[6] = 0f; reflectValues[7] = 0f; reflectValues[8] = 1f
        reflect.setValues(reflectValues)
        flap.rewind()
        lifted.transform(reflect, flap)

        c.drawPath(flap, flapPaint) // relleno + sombra proyectada
        c.save()
        c.clipPath(flap)
        c.save()
        c.concat(reflect)
        if (k.paperBack) {
            // Una sola página: el reverso deja entrever el texto al trasluz
            drawPage(c, k.front, k.liftLeft, k.side, m)
            c.drawColor((theme.paper and 0x00FFFFFF) or (if (theme.isDark) 0xE6000000.toInt() else 0xDD000000.toInt()))
        } else {
            // Doble página: el reverso es la página siguiente/anterior real
            c.scale(-1f, 1f, k.spineX, 0f)
            drawPage(c, k.back, k.backLeft, k.backSide, m)
        }
        c.restore()
        // curvatura: oscuro junto al pliegue, brillo en el centro, leve sombra al borde
        val fw = len / 2 + 1f
        creasePaint.shader = LinearGradient(
            mx, my, mx - nx * fw, my - ny * fw,
            intArrayOf(
                if (theme.isDark) 0x88000000.toInt() else 0x55000000,
                0x14000000,
                if (theme.isDark) 0x0AFFFFFF else 0x18FFFFFF,
                0x1E000000
            ),
            floatArrayOf(0f, 0.22f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPaint(creasePaint)
        c.restore()
    }

    // ------------------------------------------------------------------ geometría

    private fun createCurl(forward: Boolean, cornerY: Float): Curl? {
        val w = width.toFloat()
        val m = metrics ?: return null
        val cur = currentPage
        return if (!spread) {
            if (forward) Curl(0f, w, 0, w, cornerY, 0f, cur, cur + 1, NO_BACK, 0f, 0,
                NO_PAGE, 0f, 0, cur + 1, true)
            else Curl(0f, w, 0, w, cornerY, 0f, cur - 1, cur, NO_BACK, 0f, 0,
                NO_PAGE, 0f, 0, cur - 1, false)
        } else {
            val pw = m.pageW
            if (forward) Curl(pw, w, 2, w, cornerY, pw, cur + 1, cur + 3, cur + 2, 0f, 1,
                cur, 0f, 1, cur + 2, true)
            else Curl(0f, pw, 1, 0f, cornerY, pw, cur, cur - 2, cur - 1, pw, 2,
                cur + 1, pw, 2, cur - 2, true)
        }
    }

    /** Impide que la hoja se "despegue" del lomo o se estire más allá de su tamaño. */
    private fun constrain(k: Curl, p: PointF) {
        val pageW = k.liftRight - k.liftLeft
        val h = height.toFloat()
        if (k.cornerX > k.spineX) p.x = min(p.x, k.cornerX) else p.x = max(p.x, k.cornerX)
        clampToCircle(p, k.spineX, k.cornerY, pageW - 0.5f)
        val otherY = if (k.cornerY > 0f) 0f else h
        clampToCircle(p, k.spineX, otherY, hypot(pageW, h) - 0.5f)
    }

    private fun clampToCircle(p: PointF, cx: Float, cy: Float, r: Float) {
        val d = hypot(p.x - cx, p.y - cy)
        if (d > r && d > 0f) {
            p.x = cx + (p.x - cx) * r / d
            p.y = cy + (p.y - cy) * r / d
        }
    }

    private fun progress(k: Curl): Float =
        ((k.cornerX - t.x) / (k.cornerX - k.flippedX)).coerceIn(0f, 1f)

    // ------------------------------------------------------------------ gestos

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (paginator == null) {
            if (e.actionMasked == MotionEvent.ACTION_UP) listener?.onCenterTap()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.end()
                downX = e.x; downY = e.y; downTime = e.eventTime
                dragging = false
                movedFar = false
                gestureConsumed = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX
                if (abs(dx) > touchSlop || abs(e.y - downY) > touchSlop) movedFar = true
                if (!dragging && !gestureConsumed && abs(dx) > touchSlop) {
                    val forward = dx < 0
                    if ((forward && canForward()) || (!forward && canBackward())) {
                        val cornerY = if (downY > height / 2f) height.toFloat() else 0f
                        val k = createCurl(forward, cornerY)
                        if (k != null && animation == PageAnimation.NONE) {
                            jumpTo(k.newPage)
                            gestureConsumed = true
                            movedFar = true
                            dragging = false
                            return true
                        } else if (k != null) {
                            curl = k
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            if (k.completeToFlipped) start.set(k.cornerX, k.cornerY)
                            else start.set(k.flippedX, k.cornerY)
                            val travel = abs(k.flippedX - k.cornerX)
                            dragFactor = if (animation == PageAnimation.SLIDE) travel / width
                            else max(1.2f, travel / (width * 0.8f))
                            downX = e.x
                        }
                    }
                }
                if (dragging) {
                    val k = curl ?: return true
                    val dy = if (animation == PageAnimation.SLIDE) 0f else (e.y - downY) * 0.8f
                    t.set(start.x + (e.x - downX) * dragFactor, start.y + dy)
                    constrain(k, t)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(e)
                if (dragging) {
                    val k = curl
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
                        animateTo(k, toFlipped, bump = false)
                    }
                } else if (e.actionMasked == MotionEvent.ACTION_UP && !movedFar && e.eventTime - downTime < 500) {
                    val w = width
                    when {
                        e.x < w * 0.22f -> turn(false)
                        e.x > w * 0.78f -> turn(true)
                        else -> listener?.onCenterTap()
                    }
                }
                dragging = false
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    /** Pasa página con animación (toques en los bordes y teclas de volumen). */
    fun turn(forward: Boolean) {
        animator?.end()
        if (paginator == null) return
        if (forward && !canForward()) return
        if (!forward && !canBackward()) return
        val k = createCurl(forward, height.toFloat()) ?: return
        if (animation == PageAnimation.NONE) {
            jumpTo(k.newPage)
            return
        }
        curl = k
        if (k.completeToFlipped) t.set(k.cornerX, k.cornerY) else t.set(k.flippedX, k.cornerY)
        animateTo(k, k.completeToFlipped, bump = animation == PageAnimation.CURL)
    }

    /** Cambio de página inmediato provocado por el usuario (sin animación). */
    private fun jumpTo(page: Int) {
        curl = null
        currentPage = align(page)
        highlight = null
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        listener?.onPageChanged(currentPage)
        invalidate()
    }

    private fun animateTo(k: Curl, toFlipped: Boolean, bump: Boolean) {
        val fromX = t.x
        val fromY = t.y
        val toX = if (toFlipped) k.flippedX else k.cornerX
        val toY = k.cornerY
        val travel = abs(k.flippedX - k.cornerX).coerceAtLeast(1f)
        val dist = abs(toX - fromX) / travel
        val lift = (if (k.cornerY > 0) -1f else 1f) * height * 0.16f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when {
                bump -> 620L
                animation == PageAnimation.SLIDE -> (140 + 260 * dist).toLong()
                else -> (180 + 420 * dist).toLong()
            }
            interpolator = DecelerateInterpolator(if (bump) 1.2f else 1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                t.set(fromX + (toX - fromX) * f, fromY + (toY - fromY) * f)
                if (bump) t.y += lift * sin(PI * f).toFloat()
                constrain(k, t)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                    if (curl !== k) return
                    curl = null
                    if (!cancelled && toFlipped == k.completeToFlipped) {
                        currentPage = align(k.newPage)
                        highlight = null
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        listener?.onPageChanged(currentPage)
                    }
                    invalidate()
                }
            })
            start()
        }
    }

    companion object {
        private const val NO_PAGE = -100
        private const val NO_BACK = -200
    }
}
