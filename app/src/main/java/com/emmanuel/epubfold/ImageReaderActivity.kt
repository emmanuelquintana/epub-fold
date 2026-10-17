package com.emmanuel.epubfold

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lector de PDF y cómics (CBZ/CBR): páginas como imágenes, doble página con el
 * Fold abierto, zoom con dos dedos o doble toque y lectura de derecha a izquierda (manga).
 */
class ImageReaderActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var entry: BookEntry
    private lateinit var stats: ReadingStats
    private lateinit var sp: android.content.SharedPreferences
    private var book: ImageBook? = null

    private lateinit var pageView: SpreadView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var info: TextView
    private lateinit var btnRtl: ImageButton
    private lateinit var btnSpread: ImageButton
    private lateinit var loading: ProgressBar
    private lateinit var insets: WindowInsetsControllerCompat

    /** Grupos de páginas que se ven a la vez (1 o 2). */
    private var slots: List<IntArray> = emptyList()
    private var slot = 0
    private var startPage = 0
    private var overlay = false
    private var renderJob: Job? = null
    private var lastActivity = 0L
    private var cacheSize = 0 to 0
    private val cache = object : LruCache<Int, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    private var rtl: Boolean
        get() = sp.getBoolean("rtl_${entry.id}", sp.getBoolean("rtlDefault", false))
        set(v) = sp.edit().putBoolean("rtl_${entry.id}", v).putBoolean("rtlDefault", v).apply()

    /** 0 automático (doble con el Fold abierto), 1 una página, 2 doble. */
    private var spreadMode: Int
        get() = sp.getInt("imageSpread", 0)
        set(v) = sp.edit().putInt("imageSpread", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        stats = ReadingStats(this)
        sp = getSharedPreferences("reader", MODE_PRIVATE)
        val e = intent.getStringExtra(ReaderActivity.EXTRA_ID)?.let { library.get(it) }
        if (e == null) {
            finish()
            return
        }
        entry = e
        startPage = e.chapter
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (ReaderPrefs(this).keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.isAppearanceLightStatusBars = false

        lifecycleScope.launch {
            val b = runCatching { withContext(Dispatchers.IO) { Formats.open(entry.format, File(entry.path)) } }
            b.onSuccess {
                book = it
                loading.visibility = View.GONE
                relayout()
            }.onFailure {
                Toast.makeText(this@ImageReaderActivity, "No se pudo abrir: ${it.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::entry.isInitialized) lastActivity = SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        super.onPause()
        if (!::entry.isInitialized) return
        markActivity()
        lastActivity = 0L
        savePosition()
        CloudSync.schedulePush(applicationContext, entry.id)
        Widgets.updateAll(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderJob?.cancel()
        // Cerramos en segundo plano por si aún se está dibujando una página
        book?.let { b -> Thread { runCatching { b.close() } }.start() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pageView.post { relayout() }
    }

    // ---------------------------------------------------------------- interfaz

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        pageView = SpreadView(this)
        root.addView(pageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loading = ProgressBar(this)
        root.addView(loading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6121214.toInt())
            setPadding(dpi(10), dpi(6), dpi(10), dpi(6))
            visibility = View.GONE
        }
        topBar.addView(iconButton(R.drawable.ic_back, "Atrás") { finish() })
        topBar.addView(TextView(this).apply {
            text = entry.title
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dpi(10), 0, dpi(10), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnSpread = iconButton(R.drawable.ic_spread, "Una o dos páginas") { chooseSpread() }
        topBar.addView(btnSpread)
        btnRtl = iconButton(R.drawable.ic_rtl, "Leer de derecha a izquierda (manga)") {
            rtl = !rtl
            Toast.makeText(this, if (rtl) "Derecha a izquierda (manga)" else "Izquierda a derecha", Toast.LENGTH_SHORT).show()
            updateButtons()
            showSlot(slot)
        }
        topBar.addView(btnRtl)
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6121214.toInt())
            setPadding(dpi(16), dpi(8), dpi(16), dpi(12))
            visibility = View.GONE
        }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    if (fromUser) info.text = "Página ${v + 1} de ${book?.pageCount ?: 0}"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    showSlot(slotFor(sb.progress))
                }
            })
        }
        bottomBar.addView(seek)
        info = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        bottomBar.addView(info)
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, i ->
            val b = i.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.updatePadding(top = b.top + dpi(6), left = b.left + dpi(10), right = b.right + dpi(10))
            bottomBar.updatePadding(bottom = b.bottom + dpi(12), left = b.left + dpi(16), right = b.right + dpi(16))
            i
        }
        setContentView(root)
        updateButtons()

        pageView.onTurn = { forward -> turn(forward) }
        pageView.onCenterTap = { setOverlay(!overlay) }
        pageView.onSize = { relayout() }
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        contentDescription = desc
        imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        background = roundRect(0x33FFFFFF, dp(19f))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dpi(8), dpi(8), dpi(8), dpi(8))
        layoutParams = LinearLayout.LayoutParams(dpi(38), dpi(38)).apply { marginStart = dpi(8) }
        setOnClickListener { onClick() }
    }

    private fun updateButtons() {
        btnRtl.imageTintList = ColorStateList.valueOf(if (rtl) ACCENT else 0xFFFFFFFF.toInt())
        btnSpread.imageTintList = ColorStateList.valueOf(if (spreadMode == 2) ACCENT else 0xFFFFFFFF.toInt())
    }

    private fun chooseSpread() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Páginas")
            .setSingleChoiceItems(arrayOf("Automático (doble con el Fold abierto)", "Una página", "Doble página"), spreadMode) { d, which ->
                spreadMode = which
                updateButtons()
                relayout()
                d.dismiss()
            }
            .show()
    }

    private fun setOverlay(show: Boolean) {
        overlay = show
        listOf(topBar, bottomBar).forEach { it.visibility = if (show) View.VISIBLE else View.GONE }
        if (show) insets.show(WindowInsetsCompat.Type.systemBars()) else insets.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- páginas

    private fun wantsSpread(): Boolean = when (spreadMode) {
        1 -> false
        2 -> true
        else -> pageView.width / resources.displayMetrics.density >= 600f && pageView.width > pageView.height * 0.9f
    }

    /** Recalcula los grupos de páginas (al abrir, plegar/desplegar o cambiar el modo). */
    private fun relayout() {
        val b = book ?: return
        if (pageView.width == 0) return
        val first = slots.getOrNull(slot)?.first() ?: startPage
        val n = b.pageCount
        val list = ArrayList<IntArray>()
        if (wantsSpread()) {
            // La portada va sola, como en un libro impreso
            list += intArrayOf(0)
            var i = 1
            while (i < n) {
                list += if (i + 1 < n) intArrayOf(i, i + 1) else intArrayOf(i)
                i += 2
            }
        } else {
            for (i in 0 until n) list += intArrayOf(i)
        }
        slots = list
        if (cacheSize != pageView.width to pageView.height) {
            cache.evictAll()
            cacheSize = pageView.width to pageView.height
        }
        seek.max = max(0, n - 1)
        showSlot(slotFor(first))
    }

    private fun slotFor(page: Int) = slots.indexOfFirst { page in it }.coerceAtLeast(0)

    private fun turn(forward: Boolean) {
        // En manga avanzar es ir hacia la izquierda
        val next = slot + if (forward) 1 else -1
        if (next !in slots.indices) {
            if (forward) Toast.makeText(this, "Fin del libro", Toast.LENGTH_SHORT).show()
            return
        }
        markActivity()
        showSlot(next)
    }

    private fun showSlot(index: Int) {
        val b = book ?: return
        if (slots.isEmpty()) return
        slot = index.coerceIn(0, slots.size - 1)
        val pages = slots[slot]
        val ordered = if (rtl) pages.reversedArray() else pages
        pageView.rtl = rtl
        seek.progress = pages.first()
        info.text = if (pages.size == 2) "Páginas ${pages[0] + 1}–${pages[1] + 1} de ${b.pageCount}"
        else "Página ${pages[0] + 1} de ${b.pageCount}"
        savePosition()

        val w = pageView.width / pages.size
        val h = pageView.height
        val cached = ordered.map { cache.get(it) }
        pageView.setPages(ordered.map { b.aspectSafe(it) }, cached)
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val bitmaps = ordered.map { p ->
                cache.get(p) ?: withContext(Dispatchers.IO) { runCatching { b.render(p, w * 5 / 4, h * 5 / 4) }.getOrNull() }
                    ?.also { cache.put(p, it) }
            }
            pageView.setPages(ordered.map { b.aspectSafe(it) }, bitmaps)
            // Precarga las páginas vecinas
            listOf(slot + 1, slot - 1).filter { it in slots.indices }.flatMap { slots[it].toList() }.forEach { p ->
                if (cache.get(p) == null) {
                    withContext(Dispatchers.IO) { runCatching { b.render(p, w * 5 / 4, h * 5 / 4) }.getOrNull() }?.let { cache.put(p, it) }
                }
            }
        }
    }

    private fun ImageBook.aspectSafe(i: Int) = runCatching { aspect(i) }.getOrDefault(0.7f)

    private fun savePosition() {
        val b = book ?: return
        val pages = slots.getOrNull(slot) ?: return
        val last = pages.last()
        val progress = if (b.pageCount <= 1) 1f else last.toFloat() / (b.pageCount - 1)
        val id = entry.id
        val first = pages.first()
        lifecycleScope.launch(Dispatchers.IO) { library.updatePosition(id, first, 0, progress) }
    }

    private fun markActivity() {
        val now = SystemClock.elapsedRealtime()
        if (lastActivity > 0L) stats.addSeconds(min(((now - lastActivity) / 1000L).toInt(), 180))
        lastActivity = now
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val volume = ReaderPrefs(this).volumeKeys && !overlay
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volume) { turn(true); return true }
            KeyEvent.KEYCODE_VOLUME_UP -> if (volume) { turn(false); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { turn(!rtl); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { turn(rtl); return true }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> { turn(true); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { turn(false); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val BG = 0xFF151517.toInt()
        private const val ACCENT = 0xFFFF9F0A.toInt()
    }
}

/**
 * Muestra una o dos páginas centradas. Pellizcar o doble toque para ampliar,
 * arrastrar para moverse; sin zoom, deslizar o tocar los bordes pasa página.
 */
class SpreadView(context: Context) : View(context) {

    var onTurn: ((forward: Boolean) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null
    var onSize: (() -> Unit)? = null
    var rtl = false

    private var aspects: List<Float> = emptyList()
    private var bitmaps: List<Bitmap?> = emptyList()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholder = Paint().apply { color = 0xFF2A2A2E.toInt() }
    private val matrix = Matrix()
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    fun setPages(aspects: List<Float>, bitmaps: List<Bitmap?>) {
        val changed = aspects != this.aspects || bitmaps.size != this.bitmaps.size
        this.aspects = aspects
        this.bitmaps = bitmaps
        if (changed) resetZoom()
        invalidate()
    }

    private fun resetZoom() {
        scale = 1f; tx = 0f; ty = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
        onSize?.invoke()
    }

    /** Rectángulos de cada página ajustados a la vista (sin zoom). */
    private fun layoutRects(): List<RectF> {
        if (aspects.isEmpty() || width == 0) return emptyList()
        val totalAspect = aspects.sum()
        var h = height.toFloat()
        var w = h * totalAspect
        if (w > width) {
            w = width.toFloat()
            h = w / totalAspect
        }
        var x = (width - w) / 2
        val y = (height - h) / 2
        return aspects.map { a ->
            val pw = h * a
            RectF(x, y, x + pw, y + h).also { x += pw }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        layoutRects().forEachIndexed { i, r ->
            val bmp = bitmaps.getOrNull(i)
            if (bmp == null) canvas.drawRect(r, placeholder)
            else canvas.drawBitmap(bmp, null, r, paint)
        }
        canvas.restore()
    }

    private fun clampPan() {
        if (scale <= 1.01f) {
            resetZoom(); return
        }
        val minX = width - width * scale
        val minY = height - height * scale
        tx = tx.coerceIn(minX, 0f)
        ty = ty.coerceIn(minY, 0f)
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(1f, 5f)
            val f = ns / scale
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            scale = ns
            clampPan()
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val third = width / 3f
            when {
                e.x < third -> onTurn?.invoke(rtl)
                e.x > width - third -> onTurn?.invoke(!rtl)
                else -> onCenterTap?.invoke()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) resetZoom() else {
                scale = 2.5f
                tx = e.x - e.x * scale
                ty = e.y - e.y * scale
                clampPan()
            }
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= 1.01f) return false
            tx -= dx
            ty -= dy
            clampPan()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scale > 1.01f || e1 == null) return false
            val dx = e2.x - e1.x
            if (abs(dx) > abs(e2.y - e1.y) && abs(dx) > width * 0.08f) {
                // Deslizar hacia la izquierda avanza (al revés en manga)
                onTurn?.invoke((dx < 0) != rtl)
                return true
            }
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
