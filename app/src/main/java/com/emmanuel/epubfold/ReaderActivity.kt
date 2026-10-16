package com.emmanuel.epubfold

import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity(), BookView.Listener {

    companion object {
        const val EXTRA_ID = "book_id"
    }

    private lateinit var prefs: ReaderPrefs
    private lateinit var library: Library
    private lateinit var entry: BookEntry
    private lateinit var bookmarks: Bookmarks

    private lateinit var bookView: BookView
    private lateinit var loading: ProgressBar
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var bottomCard: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var chapterView: TextView
    private lateinit var pageInfo: TextView
    private lateinit var timeInfo: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnNight: ImageButton
    private lateinit var insetsController: WindowInsetsControllerCompat

    private var book: EpubBook? = null
    private var position = ReadingPosition(0, 0)
    private var viewSize: Pair<Int, Int>? = null
    private var bookPosture = false
    private var paginateJob: Job? = null
    private var searchJob: Job? = null
    private var overlayVisible = false
    private var lastPageTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ReaderPrefs(this)
        library = Library(this)
        val e = intent.getStringExtra(EXTRA_ID)?.let { library.get(it) }
        if (e == null) {
            finish()
            return
        }
        entry = e
        bookmarks = Bookmarks(this, e.id)
        position = ReadingPosition(e.chapter, e.charOffset)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_reader)
        bookView = findViewById(R.id.book)
        loading = findViewById(R.id.loading)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        bottomCard = findViewById(R.id.bottomCard)
        titleView = findViewById(R.id.bookTitle)
        chapterView = findViewById(R.id.chapterTitle)
        pageInfo = findViewById(R.id.pageInfo)
        timeInfo = findViewById(R.id.timeInfo)
        seek = findViewById(R.id.seek)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnNight = findViewById(R.id.btnNight)

        insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.updatePadding(top = bars.top + dpi(4), left = bars.left + dpi(10), right = bars.right + dpi(10))
            bottomBar.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        // En el Fold abierto el panel no ocupa todo el ancho
        val maxCard = dpi(560)
        bottomBar.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val lp = bottomCard.layoutParams
            val want = if (r - l > maxCard + dpi(24)) maxCard else LinearLayout.LayoutParams.MATCH_PARENT
            if (lp.width != want) {
                lp.width = want
                (lp as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
                bottomCard.post { bottomCard.layoutParams = lp }
            }
        }

        titleView.text = entry.title
        bookView.listener = this
        bookView.chapterTitle = { ch -> book?.chapterTitle(ch) ?: "" }
        bookView.isBookmarked = { idx -> bookmarkFor(idx) != null }
        bookView.animation = prefs.animation
        bookView.theme = prefs.theme
        applyOverlayTheme()
        applyBrightness()
        applyKeepScreenOn()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnNight.setOnClickListener {
            applyReaderTheme(if (prefs.theme.isDark) prefs.lastLight else prefs.lastDark)
        }
        findViewById<View>(R.id.toolToc).setOnClickListener { showContents() }
        findViewById<View>(R.id.toolSearch).setOnClickListener { showSearch() }
        findViewById<View>(R.id.toolThemes).setOnClickListener { showSettings() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) updateInfo(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                bookView.goTo(sb.progress)
            }
        })

        lifecycleScope.launch {
            try {
                val b = withContext(Dispatchers.IO) { EpubBook(File(entry.path)) }
                book = b
                titleView.text = b.title
                relayout()
            } catch (ex: Exception) {
                Toast.makeText(this@ReaderActivity, "No se pudo abrir: ${ex.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        // Postura "libro" del Fold (medio abierto en vertical): forzamos doble página
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@ReaderActivity)
                    .windowLayoutInfo(this@ReaderActivity)
                    .collect { info ->
                        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                        val posture = fold != null &&
                            fold.state == FoldingFeature.State.HALF_OPENED &&
                            fold.orientation == FoldingFeature.Orientation.VERTICAL
                        if (posture != bookPosture) {
                            bookPosture = posture
                            relayout()
                        }
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lastPageTime = SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        book?.close()
    }

    // ---------------------------------------------------------------- BookView.Listener

    override fun onSizeReady(w: Int, h: Int) {
        if (viewSize == Pair(w, h)) return
        viewSize = Pair(w, h)
        relayout()
    }

    override fun onPageChanged(page: Int) {
        bookView.paginator?.let { position = it.positionOf(page) }
        trackReadingSpeed()
        seek.progress = page
        updateInfo(page)
        updateBookmarkIcon()
        savePosition()
    }

    override fun onCenterTap() {
        setOverlay(!overlayVisible)
    }

    // ---------------------------------------------------------------- paginación

    private fun relayout() {
        val b = book ?: return
        val (w, h) = viewSize ?: return
        bookView.paginator?.let { position = it.positionOf(bookView.currentPage) }
        paginateJob?.cancel()

        val density = resources.displayMetrics.density
        val spread = when (prefs.layoutMode) {
            LayoutMode.SINGLE -> false
            LayoutMode.DOUBLE -> true
            LayoutMode.AUTO -> bookPosture || w / density >= 600f
        }
        val m = PageMetrics.of(w, h, spread, density, prefs.marginScale)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            @Suppress("DEPRECATION")
            textSize = prefs.fontSizeSp * resources.displayMetrics.scaledDensity
            typeface = if (prefs.serif) Typeface.SERIF else Typeface.SANS_SERIF
            color = prefs.theme.text
            linkColor = prefs.theme.text
        }
        val lineSpacing = prefs.lineSpacing
        val justify = prefs.justify
        val target = position
        loading.visibility = View.VISIBLE

        paginateJob = lifecycleScope.launch {
            val p = withContext(Dispatchers.Default) {
                val pg = Paginator(b, paint, m.contentW, m.contentH, lineSpacing, density, justify)
                pg.paginate { !isActive }
                pg
            }
            loading.visibility = View.GONE
            bookView.setContent(p, m, spread, p.pageFor(target))
            seek.max = max(0, p.pageCount - 1)
            seek.progress = bookView.currentPage
            updateInfo(bookView.currentPage)
            updateBookmarkIcon()
        }
    }

    private fun updateInfo(page: Int) {
        val p = bookView.paginator ?: return
        val b = book ?: return
        val pg = p.pages.getOrNull(page) ?: return
        chapterView.text = b.chapterTitle(pg.chapter)
        pageInfo.text = if (bookView.spread && page + 1 < p.pageCount) {
            "Páginas ${page + 1}–${page + 2} de ${p.pageCount}"
        } else {
            "Página ${page + 1} de ${p.pageCount}"
        }
        val step = if (bookView.spread) 2 else 1
        val left = p.pagesLeftInChapter(page + step - 1).coerceAtLeast(0)
        val minutes = (left * prefs.secondsPerPage / 60f).roundToInt()
        val pct = if (p.pageCount > 1) page * 100 / (p.pageCount - 1) else 100
        timeInfo.text = when {
            left == 0 -> "Última página del capítulo · $pct %"
            minutes < 1 -> "Quedan $left págs. en el capítulo · menos de 1 min"
            else -> "Quedan $left págs. en el capítulo · ≈ $minutes min"
        }
    }

    /** Media móvil del tiempo por página, ignorando saltos y pausas largas. */
    private fun trackReadingSpeed() {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastPageTime) / 1000f
        lastPageTime = now
        val perPage = dt / (if (bookView.spread) 2 else 1)
        if (perPage in 4f..300f) {
            prefs.secondsPerPage = prefs.secondsPerPage * 0.85f + perPage * 0.15f
        }
    }

    private fun savePosition() {
        if (!::entry.isInitialized) return
        val p = bookView.paginator
        val pos = p?.positionOf(bookView.currentPage) ?: position
        val progress = when {
            p == null -> entry.progress
            p.pageCount <= 1 -> 1f
            else -> ((bookView.currentPage + (if (bookView.spread) 1 else 0)).toFloat() / (p.pageCount - 1)).coerceAtMost(1f)
        }
        val id = entry.id
        lifecycleScope.launch(Dispatchers.IO) {
            library.updatePosition(id, pos.chapter, pos.charOffset, progress)
        }
    }

    // ---------------------------------------------------------------- marcadores

    private fun bookmarkFor(page: Int): Bookmark? {
        val p = bookView.paginator ?: return null
        val pg = p.pages.getOrNull(page) ?: return null
        val range = p.charRange(page)
        return bookmarks.find(pg.chapter, range.first, range.last + 1)
    }

    private fun currentBookmark(): Bookmark? {
        val page = bookView.currentPage
        return bookmarkFor(page) ?: if (bookView.spread) bookmarkFor(page + 1) else null
    }

    private fun toggleBookmark() {
        val p = bookView.paginator ?: return
        val existing = currentBookmark()
        btnBookmark.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (existing != null) {
            bookmarks.remove(existing)
        } else {
            val page = bookView.currentPage
            val pg = p.pages.getOrNull(page) ?: return
            val text = p.textOf(pg.chapter)
            val end = (pg.startChar + 140).coerceAtMost(text.length)
            val snippet = text.subSequence(pg.startChar.coerceAtMost(end), end).toString()
                .replace('￼', ' ').replace(Regex("\\s+"), " ").trim()
            bookmarks.add(Bookmark(pg.chapter, pg.startChar, snippet, System.currentTimeMillis()))
        }
        updateBookmarkIcon()
        bookView.invalidate()
    }

    private fun updateBookmarkIcon() {
        val on = currentBookmark() != null
        btnBookmark.setImageResource(if (on) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        btnBookmark.imageTintList = ColorStateList.valueOf(if (on) accentFor(prefs.theme) else prefs.theme.text)
    }

    // ---------------------------------------------------------------- UI

    private fun setOverlay(show: Boolean) {
        overlayVisible = show
        listOf(topBar, bottomBar).forEachIndexed { i, v ->
            v.animate().cancel()
            val dy = dp(if (i == 0) -16f else 24f)
            if (show) {
                v.alpha = 0f
                v.translationY = dy
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).translationY(0f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            } else {
                v.animate().alpha(0f).translationY(dy).setDuration(180)
                    .withEndAction { v.visibility = View.GONE }.start()
            }
        }
        if (show) insetsController.show(WindowInsetsCompat.Type.systemBars())
        else insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun accentFor(t: ReaderTheme) = if (t.isDark) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()

    private fun applyReaderTheme(t: ReaderTheme) {
        prefs.theme = t
        bookView.theme = t
        applyOverlayTheme()
        AppCompatDelegate.setDefaultNightMode(
            if (t.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun applyOverlayTheme() {
        val t = prefs.theme
        val accent = accentFor(t)
        bookView.accentColor = accent
        topBar.setBackgroundColor(ColorUtils.setAlphaComponent(t.paper, 0xF2))
        bottomCard.background = roundRect(
            ColorUtils.blendARGB(t.paper, t.text, if (t.isDark) 0.10f else 0.05f), dp(22f)
        )
        titleView.setTextColor(t.text)
        chapterView.setTextColor(t.secondary)
        pageInfo.setTextColor(t.text)
        timeInfo.setTextColor(t.secondary)
        val tint = ColorStateList.valueOf(t.text)
        findViewById<ImageButton>(R.id.btnBack).imageTintList = tint
        btnNight.imageTintList = tint
        btnNight.setImageResource(if (t.isDark) R.drawable.ic_sun else R.drawable.ic_moon)
        val circle = ColorUtils.blendARGB(t.paper, t.text, 0.08f)
        listOf(R.id.btnBack, R.id.btnBookmark, R.id.btnNight).forEach {
            findViewById<View>(it).background = roundRect(circle, dp(19f))
        }
        listOf(R.id.iconToc, R.id.iconSearch, R.id.iconThemes).forEach {
            findViewById<ImageView>(it).imageTintList = tint
        }
        listOf(R.id.labelToc, R.id.labelSearch, R.id.labelThemes).forEach {
            findViewById<TextView>(it).setTextColor(t.text)
        }
        seek.progressTintList = ColorStateList.valueOf(accent)
        seek.thumbTintList = ColorStateList.valueOf(accent)
        seek.progressBackgroundTintList = ColorStateList.valueOf(t.secondary)
        loading.indeterminateTintList = ColorStateList.valueOf(t.secondary)
        window.decorView.setBackgroundColor(t.paper)
        insetsController.isAppearanceLightStatusBars = !t.isDark
        insetsController.isAppearanceLightNavigationBars = !t.isDark
        updateBookmarkIcon()
    }

    private fun applyBrightness() {
        val b = prefs.brightness
        window.attributes = window.attributes.apply {
            screenBrightness = if (b < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b
        }
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showSettings() {
        SettingsSheet(
            this, prefs,
            onTheme = { applyReaderTheme(it) },
            onRelayout = { relayout() },
            onAnimation = { bookView.animation = prefs.animation },
            onBrightness = { applyBrightness() },
            onKeepScreenOn = { applyKeepScreenOn() },
        ).show()
    }

    // ---------------------------------------------------------------- contenido y marcadores

    private fun showContents() {
        val b = book ?: return
        val p = bookView.paginator ?: return
        val sheet = ListSheet(this, b.title)
        val seg = SegmentedControl(this)
        sheet.headerBox.addView(seg)
        val curChapter = p.pages.getOrNull(bookView.currentPage)?.chapter ?: 0

        fun showToc() {
            sheet.setStatus(null)
            // Capítulo actual: la última entrada del índice que no lo sobrepasa
            val currentIdx = b.toc.indexOfLast { it.spineIndex <= curChapter }
            sheet.adapter.rows = b.toc.mapIndexed { i, e ->
                val page = p.chapterFirstPage.getOrElse(e.spineIndex) { 0 }
                SheetRow(e.title, trailing = (page + 1).toString(), highlighted = i == currentIdx) {
                    bookView.goTo(page)
                    sheet.dismiss()
                    setOverlay(false)
                }
            }
        }

        fun showMarks() {
            if (bookmarks.items.isEmpty()) {
                sheet.setStatus("Aún no hay marcadores. Toca el icono de marcador en la barra superior para guardar la página actual.")
            } else {
                sheet.setStatus("Mantén pulsado un marcador para borrarlo.")
            }
            sheet.adapter.rows = bookmarks.items.map { m ->
                val page = p.pageFor(ReadingPosition(m.chapter, m.charOffset))
                SheetRow(
                    title = m.snippet.ifEmpty { "Página ${page + 1}" },
                    caption = b.chapterTitle(m.chapter),
                    trailing = (page + 1).toString(),
                    onLongClick = {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("¿Borrar marcador?")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Borrar") { _, _ ->
                                bookmarks.remove(m)
                                showMarks()
                                updateBookmarkIcon()
                                bookView.invalidate()
                            }.show()
                    },
                ) {
                    bookView.goTo(page)
                    sheet.dismiss()
                    setOverlay(false)
                }
            }
        }

        seg.setOptions(listOf("Contenido", "Marcadores"), 0)
        seg.onSelected = { if (it == 0) showToc() else showMarks() }
        showToc()
        sheet.show()
    }

    // ---------------------------------------------------------------- búsqueda

    private fun fold(c: Char): Char {
        if (c.code < 128) return c.lowercaseChar()
        val n = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        return (if (n.isNotEmpty()) n[0] else c).lowercaseChar()
    }

    private fun showSearch() {
        val b = book ?: return
        val p = bookView.paginator ?: return
        val sheet = ListSheet(this, "Buscar en el libro")
        val field = EditText(this).apply {
            hint = "Palabra o frase"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = getDrawable(R.drawable.bg_search_field)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dpi(8)
            compoundDrawableTintList = ColorStateList.valueOf(color(R.color.label_secondary))
            setTextColor(color(R.color.label))
            setHintTextColor(color(R.color.label_secondary))
            setPadding(dpi(10), dpi(9), dpi(10), dpi(9))
        }
        sheet.headerBox.addView(field)

        fun runSearch(query: String) {
            searchJob?.cancel()
            val q = query.trim()
            if (q.length < 2) {
                sheet.adapter.rows = emptyList()
                sheet.setStatus(null)
                return
            }
            sheet.setStatus("Buscando…")
            searchJob = lifecycleScope.launch {
                delay(250)
                val needle = String(q.map { fold(it) }.toCharArray())
                val results = withContext(Dispatchers.Default) {
                    val out = ArrayList<Triple<Int, Int, CharSequence>>()
                    for (ch in b.spine.indices) {
                        ensureActive()
                        val text = p.textOf(ch).toString()
                        val hay = String(CharArray(text.length) { fold(text[it]) })
                        var idx = hay.indexOf(needle)
                        while (idx >= 0 && out.size < 300) {
                            val s = (idx - 45).coerceAtLeast(0)
                            val e = (idx + needle.length + 70).coerceAtMost(text.length)
                            val raw = (if (s > 0) "…" else "") + text.substring(s, e)
                                .replace('\n', ' ').replace('￼', ' ') + (if (e < text.length) "…" else "")
                            val span = SpannableString(raw)
                            val hs = idx - s + (if (s > 0) 1 else 0)
                            span.setSpan(StyleSpan(Typeface.BOLD), hs, (hs + needle.length).coerceAtMost(raw.length),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            out += Triple(ch, idx, span)
                            idx = hay.indexOf(needle, idx + needle.length)
                        }
                        if (out.size >= 300) break
                    }
                    out
                }
                sheet.setStatus(when (results.size) {
                    0 -> "Sin resultados"
                    1 -> "1 resultado"
                    300 -> "Más de 300 resultados"
                    else -> "${results.size} resultados"
                })
                sheet.adapter.rows = results.map { (ch, off, snip) ->
                    val page = p.pageFor(ReadingPosition(ch, off))
                    SheetRow(snip, caption = b.chapterTitle(ch), trailing = (page + 1).toString()) {
                        bookView.goTo(page)
                        bookView.highlight = Triple(ch, off, off + needle.length)
                        sheet.dismiss()
                        setOverlay(false)
                    }
                }
            }
        }

        field.doAfterTextChanged { runSearch(it?.toString() ?: "") }
        field.setOnEditorActionListener { v, _, _ ->
            runSearch(v.text.toString())
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        sheet.dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.show()
        field.requestFocus()
        field.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
        }, 200)
    }

    // ---------------------------------------------------------------- teclas

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && !overlayVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { bookView.turn(true); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { bookView.turn(false); return true }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> { bookView.turn(true); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> { bookView.turn(false); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
