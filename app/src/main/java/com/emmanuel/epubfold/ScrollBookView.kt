package com.emmanuel.epubfold

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Modo de desplazamiento continuo: los capítulos uno tras otro, como una página web.
 * Reutiliza los StaticLayout del [Paginator] (mismo texto, fuente y subrayados).
 */
@SuppressLint("ViewConstructor")
class ScrollBookView(context: Context) : RecyclerView(context) {

    var listener: BookView.Listener? = null
    /** Posición de lectura (línea superior visible). */
    var onPositionChanged: ((ReadingPosition) -> Unit)? = null
    var highlightsFor: (Int) -> List<Highlight> = { emptyList() }

    var theme: ThemeColors = ThemeColors(0xFFFFFFFF.toInt(), 0xFF1C1C1E.toInt(), 0xFF8E8E93.toInt(), false, false)
        set(value) {
            field = value
            setBackgroundColor(value.paper)
            invalidateItems()
        }
    var accentColor = 0xFFFF9500.toInt()

    var highlight: Triple<Int, Int, Int>? = null
        set(value) { field = value; invalidateItems() }
    var speakRange: Triple<Int, Int, Int>? = null
        set(value) {
            field = value
            invalidateItems()
            value?.let { ensureVisible(it.first, it.second) }
        }
    var selection: BookView.TextSelection? = null
        private set

    private var paginator: Paginator? = null
    private var metrics: PageMetrics? = null
    private val gap get() = (28 * resources.displayMetrics.density).toInt()
    private val lm = LinearLayoutManager(context)
    private var lastReported: ReadingPosition? = null

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        layoutManager = lm
        clipToPadding = false
        itemAnimator = null
        adapter = ChapterAdapter()
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // El menú de la selección quedaría flotando en otro sitio: se cierra
                if (dy != 0 && selection != null) {
                    clearSelection()
                    listener?.onSelectionChanged(null, null, true)
                }
                reportPosition()
            }
        })
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (selection != null) {
                    clearSelection()
                    listener?.onSelectionChanged(null, null, true)
                    return true
                }
                hit(e.x, e.y)?.let { (ch, off) -> if (listener?.onTextTap(ch, off) == true) return true }
                listener?.onCenterTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val (ch, off) = hit(e.x, e.y) ?: return
                val text = paginator?.textOf(ch) ?: return
                var s = off.coerceIn(0, text.length)
                var en = s
                while (s > 0 && text[s - 1].isLetterOrDigit()) s--
                while (en < text.length && text[en].isLetterOrDigit()) en++
                if (en <= s) return
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                val sel = BookView.TextSelection(ch, s, en)
                selection = sel
                invalidateItems()
                listener?.onSelectionChanged(sel, rangeAnchor(ch, s, en), true)
            }
        })
        addOnItemTouchListener(object : SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
    }

    fun setContent(p: Paginator, m: PageMetrics, pos: ReadingPosition) {
        paginator = p
        metrics = m
        setPadding(0, m.marginTop.toInt(), 0, m.marginBottom.toInt() + height / 3)
        @Suppress("NotifyDataSetChanged")
        adapter?.notifyDataSetChanged()
        post { scrollTo(pos) }
    }

    fun clearSelection() {
        selection = null
        invalidateItems()
    }

    private fun invalidateItems() {
        for (i in 0 until childCount) getChildAt(i).invalidate()
    }

    /** Capítulo y carácter bajo el punto (x, y) de la vista. */
    private fun hit(x: Float, y: Float): Pair<Int, Int>? {
        val child = findChildViewUnder(x, y) as? ChapterView ?: return null
        val layout = paginator?.layouts?.getOrNull(child.chapter) ?: return null
        val m = metrics ?: return null
        val ly = (y - child.top - gap).toInt()
        if (ly < 0) return null
        val line = layout.getLineForVertical(ly)
        return child.chapter to layout.justifiedOffsetForX(line, x - m.marginH, paginator?.justify == true)
    }

    fun rangeAnchor(ch: Int, s: Int, e: Int): RectF? {
        val layout = paginator?.layouts?.getOrNull(ch) ?: return null
        val m = metrics ?: return null
        val child = (0 until childCount).map { getChildAt(it) }.firstOrNull { (it as? ChapterView)?.chapter == ch } ?: return null
        val l0 = layout.getLineForOffset(s)
        val l1 = layout.getLineForOffset(e)
        val top = child.top + gap + layout.getLineTop(l0).toFloat()
        val bottom = child.top + gap + layout.getLineBottom(l1).toFloat()
        return RectF(m.marginH, top, m.marginH + m.contentW, bottom)
    }

    fun position(): ReadingPosition? {
        val layout = paginator ?: return null
        val y = paddingTop.toFloat() + 1f
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? ChapterView ?: continue
            if (child.bottom <= y) continue
            val l = layout.layouts.getOrNull(child.chapter) ?: return ReadingPosition(child.chapter, 0)
            val ly = (y - child.top - gap).toInt().coerceAtLeast(0)
            val line = l.getLineForVertical(ly)
            return ReadingPosition(child.chapter, l.getLineStart(line))
        }
        return null
    }

    private fun reportPosition() {
        val p = position() ?: return
        if (p != lastReported) {
            lastReported = p
            onPositionChanged?.invoke(p)
        }
    }

    fun scrollTo(pos: ReadingPosition) {
        val l = paginator?.layouts?.getOrNull(pos.chapter)
        val lineTop = l?.let { it.getLineTop(it.getLineForOffset(pos.charOffset.coerceIn(0, it.text.length))) } ?: 0
        lm.scrollToPositionWithOffset(pos.chapter, -(gap + lineTop))
        post { reportPosition() }
    }

    /** Avanza o retrocede casi una pantalla (teclas de volumen, gestos). */
    fun turn(forward: Boolean) {
        val d = ((height - paddingTop - paddingBottom + height / 3) * 0.85f).toInt()
        smoothScrollBy(0, if (forward) d else -d)
    }

    private fun ensureVisible(ch: Int, offset: Int) {
        val r = rangeAnchor(ch, offset, offset)
        if (r == null) {
            scrollTo(ReadingPosition(ch, offset))
            return
        }
        val visTop = paddingTop
        val visBottom = height - height / 4
        if (r.top < visTop || r.bottom > visBottom) smoothScrollBy(0, (r.top - visTop - height * 0.25f).toInt())
    }

    // ---------------------------------------------------------------- capítulos

    private inner class ChapterAdapter : Adapter<ViewHolder>() {
        override fun getItemCount() = paginator?.layouts?.size ?: 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            object : ViewHolder(ChapterView(parent.context)) {}

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val v = holder.itemView as ChapterView
            v.chapter = position
            val h = (paginator?.layouts?.getOrNull(position)?.height ?: 0) + gap
            v.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            v.invalidate()
        }
    }

    private inner class ChapterView(context: Context) : View(context) {
        var chapter = 0

        override fun onDraw(c: Canvas) {
            val p = paginator ?: return
            val m = metrics ?: return
            val layout = p.layouts.getOrNull(chapter) ?: return
            p.paint.color = theme.text
            c.save()
            c.translate(m.marginH, gap.toFloat())
            // Sólo lo visible: los capítulos pueden medir decenas de miles de píxeles
            val parentTop = -top - gap
            c.clipRect(0f, parentTop.toFloat(), m.contentW.toFloat(), (parentTop + this@ScrollBookView.height).toFloat())
            val len = layout.text.length
            for (h in highlightsFor(chapter)) {
                val s0 = h.start.coerceIn(0, len)
                val s1 = h.end.coerceIn(0, len)
                if (s1 <= s0) continue
                path.rewind()
                layout.justifiedSelectionPath(s0, s1, path, paginator?.justify == true)
                val col = HIGHLIGHT_COLORS.getOrElse(h.color) { HIGHLIGHT_COLORS[0] }
                markPaint.color = (col and 0x00FFFFFF) or (if (theme.isDark) 0x5C000000 else 0x66000000)
                c.drawPath(path, markPaint)
            }
            fun range(t: Triple<Int, Int, Int>?, color: Int) {
                if (t == null || t.first != chapter) return
                val s0 = t.second.coerceIn(0, len)
                val s1 = t.third.coerceIn(0, len)
                if (s1 <= s0) return
                path.rewind()
                layout.justifiedSelectionPath(s0, s1, path, paginator?.justify == true)
                markPaint.color = color
                c.drawPath(path, markPaint)
            }
            range(speakRange, (accentColor and 0x00FFFFFF) or (if (theme.isDark) 0x40000000 else 0x30000000))
            range(highlight, (accentColor and 0x00FFFFFF) or 0x55000000)
            selection?.let { s -> range(Triple(s.chapter, s.start, s.end), (accentColor and 0x00FFFFFF) or 0x4D000000) }
            layout.draw(c)
            c.restore()
        }
    }
}
