package com.emmanuel.epubfold

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import kotlin.math.min
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity(), BookView.Listener {

    companion object {
        const val EXTRA_ID = "book_id"
        private val RATES = floatArrayOf(0.8f, 1f, 1.25f, 1.5f, 2f)
    }

    private lateinit var prefs: ReaderPrefs
    private lateinit var library: Library
    private lateinit var entry: BookEntry
    private lateinit var notes: Annotations
    private lateinit var stats: ReadingStats

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
    private lateinit var btnPanel: ImageButton
    private lateinit var sidePanel: LinearLayout
    private lateinit var sideDivider: View
    private lateinit var sideAdapter: RowAdapter
    private lateinit var sideTabs: SegmentedControl
    private lateinit var sideStatus: TextView
    private lateinit var player: LinearLayout
    private lateinit var playerToggle: ImageButton
    private lateinit var playerRate: TextView
    private lateinit var insetsController: WindowInsetsControllerCompat
    private lateinit var selectionMenu: SelectionMenu
    private lateinit var readAloud: ReadAloud

    private var book: EpubBook? = null
    private var position = ReadingPosition(0, 0)
    private var viewSize: Pair<Int, Int>? = null
    private var bookPosture = false
    private var paginateJob: Job? = null
    private var searchJob: Job? = null
    private var overlayVisible = false
    private var lastPageTime = 0L
    private var lastActivity = 0L
    private var appliedTheme = ReaderTheme.ORIGINAL
    private var lastEffective: ReaderTheme? = null
    private var colors = ThemeColors(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF888888.toInt(), false, false)
    private var finishedShown = false
    private val handler = Handler(Looper.getMainLooper())

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(notesMarkdown().toByteArray()) }
            toast("Notas guardadas")
        }.onFailure { toast("No se pudo guardar: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ReaderPrefs(this)
        library = Library(this)
        stats = ReadingStats(this)
        val e = intent.getStringExtra(EXTRA_ID)?.let { library.get(it) }
        if (e == null) {
            finish()
            return
        }
        entry = e
        notes = Annotations(this, e.id)
        position = ReadingPosition(e.chapter, e.charOffset)
        finishedShown = e.finishedAt > 0L

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
        btnPanel = findViewById(R.id.btnPanel)
        sidePanel = findViewById(R.id.sidePanel)
        sideDivider = findViewById(R.id.sideDivider)
        sideStatus = findViewById(R.id.sideStatus)
        player = findViewById(R.id.player)
        playerToggle = findViewById(R.id.playerToggle)
        playerRate = findViewById(R.id.playerRate)
        selectionMenu = SelectionMenu(this)

        insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.updatePadding(top = bars.top + dpi(4), left = bars.left + dpi(10), right = bars.right + dpi(10))
            bottomBar.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            sidePanel.updatePadding(top = bars.top, left = bars.left)
            (player.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dpi(20)
            insets
        }

        // En el Fold abierto el panel inferior no ocupa todo el ancho
        val maxCard = dpi(600)
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
        findViewById<TextView>(R.id.sideTitle).text = entry.title
        bookView.listener = this
        bookView.chapterTitle = { ch -> book?.chapterTitle(ch) ?: "" }
        bookView.isBookmarked = { idx -> bookmarkFor(idx) != null }
        bookView.highlightsFor = { ch -> notes.highlightsIn(ch) }
        bookView.animation = prefs.animation

        readAloud = ReadAloud(
            this,
            textOf = { bookView.paginator?.textOf(it) ?: "" },
            chapterCount = { book?.spine?.size ?: 0 },
            onSentence = { ch, s, e -> onSpeechSentence(ch, s, e) },
            onStateChanged = { updatePlayer() },
            onError = { toast(it) },
        )
        readAloud.rate = prefs.ttsRate

        setupSidePanel()
        lastEffective = prefs.effectiveTheme()
        applyColors(lastEffective!!)
        applyBrightness()
        applyKeepScreenOn()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnNight.setOnClickListener {
            val t = if (colors.isDark) prefs.lastLight else prefs.lastDark
            prefs.theme = t
            applyColors(t)
        }
        btnPanel.setOnClickListener {
            prefs.sidePanel = !prefs.sidePanel
            updatePanelVisibility()
        }
        findViewById<View>(R.id.toolToc).setOnClickListener { showContents() }
        findViewById<View>(R.id.toolSearch).setOnClickListener { showSearch() }
        findViewById<View>(R.id.toolListen).setOnClickListener { startListening(null) }
        findViewById<View>(R.id.toolThemes).setOnClickListener { showSettings() }
        playerToggle.setOnClickListener {
            if (readAloud.playing) readAloud.pause()
            else readAloud.play(this, readAloud.chapter, readAloud.offset)
        }
        playerRate.setOnClickListener {
            val i = RATES.indexOfFirst { it >= readAloud.rate - 0.01f }
            val next = RATES[(i + 1) % RATES.size]
            readAloud.rate = next
            prefs.ttsRate = next
            updatePlayer()
            if (readAloud.playing) readAloud.play(this, readAloud.chapter, readAloud.offset)
        }
        findViewById<View>(R.id.playerClose).setOnClickListener {
            readAloud.stop()
            player.visibility = View.GONE
        }

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
                readAloud.setLanguage(b.language)
                relayout()
            } catch (ex: Exception) {
                toast("No se pudo abrir: ${ex.message}")
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

    private val autoTick = object : Runnable {
        override fun run() {
            checkAutoTheme()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::selectionMenu.isInitialized) return
        lastPageTime = SystemClock.elapsedRealtime()
        lastActivity = SystemClock.elapsedRealtime()
        checkAutoTheme()
        handler.postDelayed(autoTick, 60_000)
    }

    override fun onPause() {
        super.onPause()
        if (!::selectionMenu.isInitialized) return
        handler.removeCallbacks(autoTick)
        markActivity()
        lastActivity = 0L
        savePosition()
        selectionMenu.dismiss()
        CloudSync.schedulePush(applicationContext, entry.id)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::readAloud.isInitialized) readAloud.shutdown()
        book?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::selectionMenu.isInitialized) return
        checkAutoTheme()
        updatePanelVisibility()
        selectionMenu.dismiss()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- BookView.Listener

    override fun onSizeReady(w: Int, h: Int) {
        if (viewSize == Pair(w, h)) return
        viewSize = Pair(w, h)
        relayout()
    }

    override fun onPageChanged(page: Int) {
        bookView.paginator?.let { position = it.positionOf(page) }
        trackReadingSpeed()
        markActivity()
        seek.progress = page
        updateInfo(page)
        updateBookmarkIcon()
        savePosition()
        if (::sideAdapter.isInitialized && sidePanel.visibility == View.VISIBLE && sideTabs.selected == 0) refreshSide()
    }

    override fun onCenterTap() {
        setOverlay(!overlayVisible)
    }

    override fun onSelectionChanged(sel: BookView.TextSelection?, anchor: RectF?, settled: Boolean) {
        if (sel == null || anchor == null || !settled) {
            selectionMenu.dismiss()
            return
        }
        if (overlayVisible) setOverlay(false)
        val text = selectedText(sel)
        val isWord = !text.trim().contains(' ') && text.trim().length <= 40
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "Nota" to {
            val h = notes.addHighlight(sel.chapter, sel.start, sel.end, 0, text)
            finishSelection()
            showNoteEditor(this, text, "") { n -> notes.updateHighlight(h.copy(note = n)); afterNotesChanged() }
        }
        actions += "Copiar" to {
            copy(text)
            finishSelection()
        }
        if (isWord) actions += "Definir" to {
            finishSelection()
            lookup(text, false)
        }
        actions += "Traducir" to {
            finishSelection()
            lookup(text, true)
        }
        actions += "Escuchar" to {
            finishSelection()
            startListening(ReadingPosition(sel.chapter, sel.start))
        }
        actions += "Compartir" to {
            finishSelection()
            shareText("“${text.trim()}”\n\n— ${entry.title}${if (entry.author.isNotEmpty()) ", ${entry.author}" else ""}")
        }
        selectionMenu.show(bookView, anchor, null, onColor = { c ->
            notes.addHighlight(sel.chapter, sel.start, sel.end, c, text)
            finishSelection()
            afterNotesChanged()
        }, actions = actions)
    }

    override fun onTextTap(chapter: Int, offset: Int): Boolean {
        val h = notes.highlightAt(chapter, offset) ?: return false
        val anchor = bookView.rangeAnchor(h.chapter, h.start, h.end) ?: return false
        if (overlayVisible) setOverlay(false)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += (if (h.note.isBlank()) "Añadir nota" else "Editar nota") to {
            selectionMenu.dismiss()
            showNoteEditor(this, h.text, h.note) { n -> notes.updateHighlight(h.copy(note = n)); afterNotesChanged() }
        }
        actions += "Copiar" to { copy(h.text); selectionMenu.dismiss() }
        actions += "Compartir" to {
            selectionMenu.dismiss()
            val noteText = if (h.note.isNotBlank()) "\n\nNota: ${h.note}" else ""
            shareText("“${h.text.trim()}”$noteText\n\n— ${entry.title}")
        }
        actions += "Borrar" to {
            selectionMenu.dismiss()
            notes.removeHighlight(h)
            afterNotesChanged()
        }
        selectionMenu.show(bookView, anchor, h.color, onColor = { c ->
            notes.updateHighlight(h.copy(color = c))
            selectionMenu.dismiss()
            afterNotesChanged()
        }, actions = actions)
        return true
    }

    private fun selectedText(sel: BookView.TextSelection): String {
        val t = bookView.paginator?.textOf(sel.chapter) ?: return ""
        val s = sel.start.coerceIn(0, t.length)
        val e = sel.end.coerceIn(s, t.length)
        return t.subSequence(s, e).toString().replace('￼', ' ')
    }

    private fun finishSelection() {
        selectionMenu.dismiss()
        bookView.clearSelection()
    }

    private fun afterNotesChanged() {
        bookView.invalidate()
        if (::sideAdapter.isInitialized && sidePanel.visibility == View.VISIBLE) refreshSide()
    }

    private fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(entry.title, text))
        if (android.os.Build.VERSION.SDK_INT < 33) toast("Copiado")
    }

    private fun shareText(text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, "Compartir"))
    }

    private fun lookup(text: String, translate: Boolean) {
        LookupSheet(this, lifecycleScope, prefs, book?.language ?: "").show(text, translate)
    }

    // ---------------------------------------------------------------- paginación

    private fun relayout() {
        val b = book ?: return
        val (w, h) = viewSize ?: return
        bookView.paginator?.let { position = it.positionOf(bookView.currentPage) }
        paginateJob?.cancel()
        selectionMenu.dismiss()

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
            typeface = prefs.font.typeface(this@ReaderActivity)
            color = colors.text
            linkColor = colors.text
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
            if (::sideAdapter.isInitialized && sidePanel.visibility == View.VISIBLE) refreshSide()
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
        if (readAloud.playing) return
        val perPage = dt / (if (bookView.spread) 2 else 1)
        if (perPage in 4f..300f) {
            prefs.secondsPerPage = prefs.secondsPerPage * 0.85f + perPage * 0.15f
        }
    }

    /** Suma el tiempo activo de lectura a las estadísticas (máx. 3 min sin interacción). */
    private fun markActivity() {
        val now = SystemClock.elapsedRealtime()
        if (lastActivity > 0L) {
            val dt = ((now - lastActivity) / 1000L).toInt()
            stats.addSeconds(min(dt, 180))
        }
        lastActivity = now
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
        if (progress >= 0.999f && !finishedShown && p != null) {
            finishedShown = true
            showFinished()
        }
        val id = entry.id
        lifecycleScope.launch(Dispatchers.IO) {
            library.updatePosition(id, pos.chapter, pos.charOffset, progress)
        }
    }

    private fun showFinished() {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val done = library.all().count {
            it.finishedAt > 0L && java.util.Calendar.getInstance().apply { timeInMillis = it.finishedAt }
                .get(java.util.Calendar.YEAR) == year
        } + 1
        MaterialAlertDialogBuilder(this)
            .setTitle("¡Terminaste «${entry.title}»!")
            .setMessage("Llevas $done de ${prefs.yearlyGoalBooks} libros este año. ¡Sigue así!")
            .setPositiveButton("Genial", null)
            .show()
    }

    // ---------------------------------------------------------------- marcadores

    private fun bookmarkFor(page: Int): Bookmark? {
        val p = bookView.paginator ?: return null
        val pg = p.pages.getOrNull(page) ?: return null
        val range = p.charRange(page)
        return notes.findBookmark(pg.chapter, range.first, range.last + 1)
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
            notes.removeBookmark(existing)
        } else {
            val page = bookView.currentPage
            val pg = p.pages.getOrNull(page) ?: return
            val text = p.textOf(pg.chapter)
            val end = (pg.startChar + 140).coerceAtMost(text.length)
            val snippet = text.subSequence(pg.startChar.coerceAtMost(end), end).toString()
                .replace('￼', ' ').replace(Regex("\\s+"), " ").trim()
            notes.addBookmark(Bookmark(pg.chapter, pg.startChar, snippet, System.currentTimeMillis()))
        }
        updateBookmarkIcon()
        afterNotesChanged()
    }

    private fun updateBookmarkIcon() {
        val on = currentBookmark() != null
        btnBookmark.setImageResource(if (on) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        btnBookmark.imageTintList = ColorStateList.valueOf(if (on) accent() else colors.text)
    }

    // ---------------------------------------------------------------- lectura en voz alta

    private fun startListening(from: ReadingPosition?) {
        val p = bookView.paginator ?: return
        val pos = from ?: p.positionOf(bookView.currentPage)
        setOverlay(false)
        player.visibility = View.VISIBLE
        player.alpha = 0f
        player.animate().alpha(1f).setDuration(200).start()
        readAloud.play(this, pos.chapter, pos.charOffset)
    }

    private fun onSpeechSentence(ch: Int, s: Int, e: Int) {
        if (ch < 0) {
            bookView.speakRange = null
            updatePlayer()
            return
        }
        bookView.speakRange = Triple(ch, s, e)
        markActivity()
        val p = bookView.paginator ?: return
        val target = p.pageFor(ReadingPosition(ch, s))
        val step = if (bookView.spread) 2 else 1
        val cur = bookView.currentPage
        when {
            target in cur until cur + step -> {}
            target in cur + step until cur + 2 * step -> bookView.turn(true)
            else -> bookView.goTo(target)
        }
    }

    private fun updatePlayer() {
        playerToggle.setImageResource(if (readAloud.playing) R.drawable.ic_pause else R.drawable.ic_play)
        val r = readAloud.rate
        playerRate.text = if (r == r.toInt().toFloat()) "${r.toInt()}×" else "${r}×".replace('.', ',')
    }

    // ---------------------------------------------------------------- temas

    private fun accent() = if (colors.isDark) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()

    private fun checkAutoTheme() {
        if (prefs.autoTheme == AutoTheme.OFF) return
        val eff = prefs.effectiveTheme()
        if (eff != lastEffective) {
            lastEffective = eff
            applyColors(eff)
        }
    }

    private fun applyColors(t: ReaderTheme) {
        appliedTheme = t
        colors = prefs.colorsFor(t)
        bookView.accentColor = accent()
        bookView.theme = colors
        applyOverlayTheme()
        AppCompatDelegate.setDefaultNightMode(
            if (colors.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun applyOverlayTheme() {
        val t = colors
        val accent = accent()
        topBar.setBackgroundColor(ColorUtils.setAlphaComponent(t.paper, 0xF2))
        val card = ColorUtils.blendARGB(t.paper, t.text, if (t.isDark) 0.10f else 0.05f)
        bottomCard.background = roundRect(card, dp(22f))
        player.background = roundRect(card, dp(26f))
        playerToggle.imageTintList = ColorStateList.valueOf(t.text)
        findViewById<ImageButton>(R.id.playerClose).imageTintList = ColorStateList.valueOf(t.secondary)
        playerRate.setTextColor(accent)
        playerRate.background = roundRect(ColorUtils.blendARGB(card, t.text, 0.08f), dp(14f))
        titleView.setTextColor(t.text)
        chapterView.setTextColor(t.secondary)
        pageInfo.setTextColor(t.text)
        timeInfo.setTextColor(t.secondary)
        val tint = ColorStateList.valueOf(t.text)
        findViewById<ImageButton>(R.id.btnBack).imageTintList = tint
        btnNight.imageTintList = tint
        btnPanel.imageTintList = tint
        btnNight.setImageResource(if (t.isDark) R.drawable.ic_sun else R.drawable.ic_moon)
        val circle = ColorUtils.blendARGB(t.paper, t.text, 0.08f)
        listOf(R.id.btnBack, R.id.btnBookmark, R.id.btnNight, R.id.btnPanel).forEach {
            findViewById<View>(it).background = roundRect(circle, dp(19f))
        }
        listOf(R.id.iconToc, R.id.iconSearch, R.id.iconListen, R.id.iconThemes).forEach {
            findViewById<ImageView>(it).imageTintList = tint
        }
        listOf(R.id.labelToc, R.id.labelSearch, R.id.labelListen, R.id.labelThemes).forEach {
            findViewById<TextView>(it).setTextColor(t.text)
        }
        seek.progressTintList = ColorStateList.valueOf(accent)
        seek.thumbTintList = ColorStateList.valueOf(accent)
        seek.progressBackgroundTintList = ColorStateList.valueOf(t.secondary)
        loading.indeterminateTintList = ColorStateList.valueOf(t.secondary)
        sidePanel.setBackgroundColor(ColorUtils.blendARGB(t.paper, t.text, if (t.isDark) 0.06f else 0.03f))
        sideDivider.setBackgroundColor(ColorUtils.blendARGB(t.paper, t.text, 0.18f))
        findViewById<TextView>(R.id.sideTitle).setTextColor(t.text)
        sideStatus.setTextColor(t.secondary)
        if (::sideAdapter.isInitialized) {
            sideAdapter.textColor = t.text
            sideAdapter.secondaryColor = t.secondary
            sideAdapter.accentColor = accent
            sideAdapter.notifyDataSetChanged()
        }
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
            onTheme = { t ->
                prefs.theme = t
                applyColors(t)
            },
            onRelayout = { relayout() },
            onAnimation = { bookView.animation = prefs.animation },
            onBrightness = { applyBrightness() },
            onKeepScreenOn = { applyKeepScreenOn() },
            onAutoTheme = {
                lastEffective = null
                if (prefs.autoTheme == AutoTheme.OFF) applyColors(prefs.theme) else checkAutoTheme()
            },
        ).show()
    }

    // ---------------------------------------------------------------- UI

    private fun setOverlay(show: Boolean) {
        overlayVisible = show
        if (show) {
            selectionMenu.dismiss()
            bookView.clearSelection()
        }
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

    // ---------------------------------------------------------------- panel lateral (Fold)

    private fun isWide() = resources.configuration.screenWidthDp >= 600

    private fun setupSidePanel() {
        sideAdapter = RowAdapter()
        val list = findViewById<RecyclerView>(R.id.sideList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = sideAdapter
        sideTabs = SegmentedControl(this)
        findViewById<FrameLayout>(R.id.sideTabs).addView(sideTabs)
        sideTabs.setOptions(listOf("Contenido", "Marcadores", "Notas"), 0)
        sideTabs.onSelected = { refreshSide() }
        updatePanelVisibility()
    }

    private fun updatePanelVisibility() {
        val wide = isWide()
        btnPanel.visibility = if (wide) View.VISIBLE else View.GONE
        val show = wide && prefs.sidePanel
        val vis = if (show) View.VISIBLE else View.GONE
        if (sidePanel.visibility != vis) {
            sidePanel.visibility = vis
            sideDivider.visibility = vis
            if (show) refreshSide()
        }
    }

    private fun refreshSide() {
        val rows = buildRows(sideTabs.selected, { msg ->
            sideStatus.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
            sideStatus.text = msg
        }, onNavigate = {}, onChanged = {})
        sideAdapter.rows = rows
    }

    // ---------------------------------------------------------------- contenido, marcadores y notas

    /** Filas para la pestaña [tab]: 0 índice, 1 marcadores, 2 notas. */
    private fun buildRows(
        tab: Int,
        setStatus: (String?) -> Unit,
        onNavigate: () -> Unit,
        onChanged: () -> Unit,
    ): List<SheetRow> {
        val b = book ?: return emptyList()
        val p = bookView.paginator ?: return emptyList()
        val curChapter = p.pages.getOrNull(bookView.currentPage)?.chapter ?: 0
        fun go(page: Int) {
            bookView.goTo(page)
            onNavigate()
            if (overlayVisible) setOverlay(false)
        }
        return when (tab) {
            0 -> {
                setStatus(null)
                val currentIdx = b.toc.indexOfLast { it.spineIndex <= curChapter }
                b.toc.mapIndexed { i, e ->
                    val page = p.chapterFirstPage.getOrElse(e.spineIndex) { 0 }
                    SheetRow(e.title, trailing = (page + 1).toString(), highlighted = i == currentIdx) { go(page) }
                }
            }
            1 -> {
                setStatus(
                    if (notes.bookmarks.isEmpty()) "Aún no hay marcadores. Toca el icono de marcador arriba para guardar la página."
                    else "Mantén pulsado un marcador para borrarlo."
                )
                notes.bookmarks.map { m ->
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
                                    notes.removeBookmark(m)
                                    updateBookmarkIcon()
                                    afterNotesChanged()
                                    onChanged()
                                }.show()
                        },
                    ) { go(page) }
                }
            }
            else -> {
                setStatus(
                    if (notes.highlights.isEmpty()) "Mantén pulsado el texto para subrayar en colores o añadir una nota."
                    else "${notes.highlights.size} subrayados · ${notes.highlights.count { it.note.isNotBlank() }} con nota"
                )
                val rows = ArrayList<SheetRow>()
                if (notes.highlights.isNotEmpty() || notes.bookmarks.isNotEmpty()) {
                    rows += SheetRow("Exportar notas y subrayados…", highlighted = true) { exportNotes() }
                }
                notes.highlights.sortedWith(compareBy({ it.chapter }, { it.start })).forEach { h ->
                    val page = p.pageFor(ReadingPosition(h.chapter, h.start))
                    val title = SpannableString("● ${h.text.trim().take(300)}").apply {
                        setSpan(android.text.style.ForegroundColorSpan(HIGHLIGHT_COLORS.getOrElse(h.color) { HIGHLIGHT_COLORS[0] }),
                            0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val caption = b.chapterTitle(h.chapter) + if (h.note.isNotBlank()) " · Nota: ${h.note.trim()}" else ""
                    rows += SheetRow(title, caption = caption, trailing = (page + 1).toString(), onLongClick = {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Subrayado")
                            .setItems(arrayOf(if (h.note.isBlank()) "Añadir nota" else "Editar nota", "Borrar")) { _, which ->
                                if (which == 0) {
                                    showNoteEditor(this, h.text, h.note) { n ->
                                        notes.updateHighlight(h.copy(note = n)); afterNotesChanged(); onChanged()
                                    }
                                } else {
                                    notes.removeHighlight(h); afterNotesChanged(); onChanged()
                                }
                            }.show()
                    }) {
                        go(page)
                        bookView.highlight = Triple(h.chapter, h.start, h.end)
                    }
                }
                rows
            }
        }
    }

    private fun notesMarkdown(): String =
        notes.toMarkdown(entry.title, entry.author) { book?.chapterTitle(it) ?: "" }

    private fun exportNotes() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Exportar notas")
            .setItems(arrayOf("Compartir (texto)", "Guardar archivo Markdown (.md)")) { _, which ->
                if (which == 0) shareText(notesMarkdown())
                else exportFile.launch("${entry.title.take(60)} - notas.md")
            }.show()
    }

    private fun showContents() {
        val b = book ?: return
        val sheet = ListSheet(this, b.title)
        val seg = SegmentedControl(this)
        sheet.headerBox.addView(seg)
        fun show(tab: Int) {
            sheet.adapter.rows = buildRows(tab, { sheet.setStatus(it) },
                onNavigate = { sheet.dismiss() },
                onChanged = { show(seg.selected) })
        }
        seg.setOptions(listOf("Contenido", "Marcadores", "Notas"), 0)
        seg.onSelected = { show(it) }
        show(0)
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
        if (prefs.volumeKeys && !overlayVisible && !readAloud.playing) {
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
