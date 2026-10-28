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
        /** Abrir en un subrayado concreto (capítulo e intervalo de caracteres). */
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
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
    private lateinit var playerTimer: TextView
    private lateinit var ruler: ReadingRulerView
    private var notebookView: NotebookView? = null
    private var pendingHighlight: Triple<Int, Int, Int>? = null
    /** Momento (elapsedRealtime) en que el temporizador detiene la lectura; 0 = sin temporizador. */
    private var sleepAt = 0L
    private lateinit var scrollView: ScrollBookView
    /** Desplazamiento continuo activo (se decide en cada relayout). */
    private var scrollMode = false
    private var translationPanel: TranslationPanel? = null
    private lateinit var handsFree: HandsFree
    private var flexPanel: LinearLayout? = null
    private var flexInfo: TextView? = null
    private var flexPlay: TextView? = null
    private var flexMode = false
    /** Tras plegar o desplegar, resaltar un momento la línea donde ibas. */
    private var flashOnLayout = false
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
        if (intent.hasExtra(EXTRA_CHAPTER)) {
            val ch = intent.getIntExtra(EXTRA_CHAPTER, 0)
            val s = intent.getIntExtra(EXTRA_START, 0)
            position = ReadingPosition(ch, s)
            pendingHighlight = Triple(ch, s, intent.getIntExtra(EXTRA_END, s))
        }

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
        playerTimer = findViewById(R.id.playerTimer)
        selectionMenu = SelectionMenu(this)
        ruler = ReadingRulerView(this)
        // Encima del libro y debajo de las barras
        findViewById<FrameLayout>(R.id.root).addView(ruler, 1, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ruler.visibility = if (prefs.ruler) View.VISIBLE else View.GONE
        scrollView = ScrollBookView(this)
        findViewById<FrameLayout>(R.id.bookFrame).addView(scrollView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        scrollView.visibility = View.GONE
        scrollView.listener = this
        scrollView.highlightsFor = { ch -> notes.highlightsIn(ch) }
        scrollView.onPositionChanged = { pos -> onScrollPosition(pos) }
        handsFree = HandsFree(this) { forward -> if (!overlayVisible) turnPage(forward) }

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
            onStateChanged = {
                updatePlayer()
                ReadAloudService.update(this)
            },
            onError = { toast(it) },
        )
        readAloud.rate = prefs.ttsRate
        readAloud.voiceName = prefs.ttsVoice
        readAloud.onChapterEndStop = {
            sleepAt = 0L
            updateTimerLabel()
            toast("Temporizador: fin del capítulo")
        }
        ReadAloudService.controller = speechController

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
        findViewById<View>(R.id.playerClose).setOnClickListener { stopListening() }
        playerTimer.setOnClickListener { chooseSleepTimer() }
        findViewById<View>(R.id.playerMore).setOnClickListener { anchor ->
            android.widget.PopupMenu(this, anchor).apply {
                menu.add(0, 1, 0, "Voz…")
                menu.add(0, 2, 1, "Guardar capítulo como audio…")
                menu.add(0, 3, 2, "Temporizador…")
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> chooseVoice()
                        2 -> exportChapterAudio()
                        3 -> chooseSleepTimer()
                    }
                    true
                }
                show()
            }
        }
        updateTimerLabel()

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) updateInfo(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                navigate(sb.progress)
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
                        val flex = fold != null && fold.state == FoldingFeature.State.HALF_OPENED &&
                            fold.orientation == FoldingFeature.Orientation.HORIZONTAL
                        setFlexMode(if (flex) fold!!.bounds else null)
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
        startHandsFree()
    }

    override fun onPause() {
        super.onPause()
        if (!::selectionMenu.isInitialized) return
        handler.removeCallbacks(autoTick)
        handsFree.stop()
        markActivity()
        lastActivity = 0L
        savePosition()
        selectionMenu.dismiss()
        notebookView?.flush()
        CloudSync.schedulePush(applicationContext, entry.id)
        handler.postDelayed({ Widgets.updateAll(applicationContext) }, 500)
        Integrations.autoSync(applicationContext)
        Clubs.pushProgress(applicationContext, entry.id)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(sleepTick)
        if (ReadAloudService.controller === speechController) {
            ReadAloudService.controller = null
            ReadAloudService.stop(this)
        }
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
        flashOnLayout = viewSize != null
        viewSize = Pair(w, h)
        relayout()
    }

    override fun onPageChanged(page: Int) {
        bookView.paginator?.let { position = it.positionOf(page) }
        if (ruler.visibility == View.VISIBLE) ruler.resetToTop()
        trackReadingSpeed()
        markActivity()
        seek.progress = page
        updateInfo(page)
        updateBookmarkIcon()
        savePosition()
        if (::sideAdapter.isInitialized && sidePanel.visibility == View.VISIBLE && sideTabs.selected == 0) refreshSide()
        onPageShown()
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
            lookup(text, false, sel)
        }
        actions += "Traducir" to {
            finishSelection()
            lookup(text, true, sel)
        }
        actions += "Escuchar" to {
            finishSelection()
            startListening(ReadingPosition(sel.chapter, sel.start))
        }
        actions += "Compartir" to {
            finishSelection()
            shareText("“${text.trim()}”\n\n— ${entry.title}${if (entry.author.isNotEmpty()) ", ${entry.author}" else ""}")
        }
        actions += "Imagen" to {
            finishSelection()
            QuoteCard.share(this, text, "", entry)
        }
        selectionMenu.show(surface(), anchor, null, onColor = { c ->
            notes.addHighlight(sel.chapter, sel.start, sel.end, c, text)
            finishSelection()
            afterNotesChanged()
        }, actions = actions)
    }

    override fun onTextTap(chapter: Int, offset: Int): Boolean {
        val h = notes.highlightAt(chapter, offset) ?: return false
        val anchor = anchorFor(h.chapter, h.start, h.end) ?: return false
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
        actions += "Imagen" to {
            selectionMenu.dismiss()
            QuoteCard.share(this, h.text, h.note, entry)
        }
        actions += "Borrar" to {
            selectionMenu.dismiss()
            notes.removeHighlight(h)
            afterNotesChanged()
        }
        selectionMenu.show(surface(), anchor, h.color, onColor = { c ->
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
        clearSelections()
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

    private fun lookup(text: String, translate: Boolean, sel: BookView.TextSelection? = null) {
        val word = text.trim()
        val isWord = !word.contains(' ') && word.length in 2..40
        if (isWord) {
            // Se guarda en el vocabulario con la frase donde aparece
            val context = sel?.let { s ->
                bookView.paginator?.textOf(s.chapter)?.let { Vocabulary.sentenceAround(it, s.start, s.end) }
            } ?: ""
            Vocabulary(this).add(word, context, entry.title)
        }
        LookupSheet(this, lifecycleScope, prefs, book?.language ?: "") { meaning ->
            if (isWord && meaning.isNotBlank()) Vocabulary(this).setMeaning(word, meaning)
        }.show(text, translate)
    }

    // ---------------------------------------------------------------- páginas o desplazamiento continuo

    private fun surface(): View = if (scrollMode) scrollView else bookView

    private fun isSpread() = !scrollMode && bookView.spread

    private fun currentPage(): Int {
        if (!scrollMode) return bookView.currentPage
        val p = bookView.paginator ?: return 0
        return p.pageFor(scrollView.position() ?: position)
    }

    private fun navigate(page: Int) {
        if (scrollMode) bookView.paginator?.let { scrollView.scrollTo(it.positionOf(page)) } else bookView.goTo(page)
    }

    private fun turnPage(forward: Boolean) {
        if (scrollMode) scrollView.turn(forward) else bookView.turn(forward)
    }

    private fun showHighlight(t: Triple<Int, Int, Int>?) {
        bookView.highlight = t
        scrollView.highlight = t
        if (scrollMode && t != null) bookView.paginator?.let { scrollView.scrollTo(ReadingPosition(t.first, t.second)) }
    }

    private fun setSpeakRange(t: Triple<Int, Int, Int>?) {
        bookView.speakRange = t
        scrollView.speakRange = if (scrollMode) t else null
    }

    private fun clearSelections() {
        bookView.clearSelection()
        scrollView.clearSelection()
    }

    private fun anchorFor(ch: Int, s: Int, e: Int) =
        if (scrollMode) scrollView.rangeAnchor(ch, s, e) else bookView.rangeAnchor(ch, s, e)

    private fun onScrollPosition(pos: ReadingPosition) {
        val p = bookView.paginator ?: return
        position = pos
        val page = p.pageFor(pos)
        markActivity()
        seek.progress = page
        updateInfo(page)
        updateBookmarkIcon()
        savePosition()
        onPageShown()
    }

    /** Se llama cada vez que cambia lo que se ve (página o desplazamiento). */
    private fun onPageShown() {
        if (::sideTabs.isInitialized && sidePanel.visibility == View.VISIBLE && sideTabs.selected == 4) {
            translationPanel?.showPage(currentPageText())
        }
        updateFlexPanel()
    }

    /** Texto de la página (o de las dos, en doble página) que se está viendo. */
    private fun currentPageText(): String {
        val p = bookView.paginator ?: return ""
        val page = currentPage()
        val pages = if (isSpread()) listOf(page, page + 1) else listOf(page)
        return pages.mapNotNull { i ->
            val pg = p.pages.getOrNull(i) ?: return@mapNotNull null
            val t = p.textOf(pg.chapter)
            val r = p.charRange(i)
            val s = r.first.coerceIn(0, t.length)
            t.subSequence(s, (r.last + 1).coerceIn(s, t.length)).toString()
        }.joinToString("\n")
    }

    /** Al plegar o desplegar: resalta un momento la línea por la que ibas. */
    private fun flashLine(pos: ReadingPosition) {
        val l = bookView.paginator?.layouts?.getOrNull(pos.chapter) ?: return
        val off = pos.charOffset.coerceIn(0, l.text.length)
        val line = l.getLineForOffset(off)
        val t = Triple(pos.chapter, l.getLineStart(line), l.getLineEnd(line))
        bookView.highlight = t
        scrollView.highlight = t
        handler.postDelayed({
            if (bookView.highlight == t) {
                bookView.highlight = null
                scrollView.highlight = null
            }
        }, 1800)
    }

    private fun startHandsFree() {
        if (!::handsFree.isInitialized) return
        if (!handsFree.start(prefs.handsFree)) {
            toast(if (prefs.handsFree == HandsFreeMode.WAVE) "Este teléfono no tiene sensor de proximidad" else "Este teléfono no tiene acelerómetro")
        }
    }

    private fun showTranslationSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val panel = TranslationPanel(this, lifecycleScope, prefs, book?.language ?: "")
        panel.setPadding(dpi(16), dpi(16), dpi(16), 0)
        panel.setColors(color(R.color.label), color(R.color.label_secondary))
        dialog.setContentView(panel, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8f).toInt()))
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = dpi(680)
        dialog.setOnDismissListener { panel.dispose() }
        dialog.show()
        panel.showPage(currentPageText())
    }

    // ---------------------------------------------------------------- voces y audio

    private fun chooseVoice() {
        val tts = readAloud.engine()
        if (tts == null) {
            readAloud.ensureStarted(this)
            toast("Cargando las voces… vuelve a pulsar en un momento")
            return
        }
        val voices = Voices.forLanguage(tts, book?.language ?: "")
        if (voices.isEmpty()) {
            toast("No hay voces instaladas para este idioma. Instálalas en Ajustes › Texto a voz.")
            return
        }
        val labels = (listOf("Automática (la del sistema)") + voices.mapIndexed { i, v -> Voices.label(v, i) }).toTypedArray()
        val current = voices.indexOfFirst { it.name == prefs.ttsVoice } + 1
        MaterialAlertDialogBuilder(this)
            .setTitle("Voz")
            .setSingleChoiceItems(labels, current) { _, which ->
                prefs.ttsVoice = if (which == 0) null else voices[which - 1].name
                readAloud.voiceName = prefs.ttsVoice
                if (readAloud.playing) readAloud.play(this, readAloud.chapter, readAloud.offset)
            }
            .setPositiveButton("Listo", null)
            .setNeutralButton("Instalar más voces") { _, _ ->
                runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            }
            .show()
    }

    private fun exportChapterAudio() {
        val p = bookView.paginator ?: return
        val b = book ?: return
        val ch = if (player.visibility == View.VISIBLE) readAloud.chapter else p.positionOf(currentPage()).chapter
        val title = b.chapterTitle(ch)
        val exporter = AudioExport(this)
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply { max = 100 }
        val box = FrameLayout(this).apply {
            setPadding(dpi(24), dpi(8), dpi(24), 0)
            addView(bar)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Guardando «${title.take(60)}» como audio")
            .setMessage("Con la voz y velocidad elegidas. Puede tardar unos minutos.")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ -> exporter.cancelled = true }
            .show()
        lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    exporter.export(p.textOf(ch), "${entry.title.take(50)} - ${title.take(50)}", b.language,
                        prefs.ttsVoice, prefs.ttsRate) { f -> runOnUiThread { bar.setProgressCompat((f * 100).toInt(), true) } }
                }
            }
            dialog.dismiss()
            r.onSuccess { toast("Guardado en $it") }
                .onFailure { if (!exporter.cancelled) toast("No se pudo guardar el audio: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- modo Flex (Fold medio plegado)

    /** Con el teléfono medio plegado sobre la mesa: el libro arriba y los controles abajo. */
    private fun setFlexMode(fold: android.graphics.Rect?) {
        val content = findViewById<View>(R.id.content)
        val root = findViewById<FrameLayout>(R.id.root)
        val lp = content.layoutParams as FrameLayout.LayoutParams
        if (fold == null) {
            if (!flexMode) return
            flexMode = false
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            content.layoutParams = lp
            flexPanel?.visibility = View.GONE
            return
        }
        if (root.height == 0) {
            root.post { setFlexMode(fold) }
            return
        }
        flexMode = true
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        lp.height = (fold.top - loc[1]).coerceAtLeast(dpi(200))
        content.layoutParams = lp
        val panel = flexPanel ?: buildFlexPanel().also { flexPanel = it; root.addView(it) }
        panel.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, (root.height - (fold.bottom - loc[1])).coerceAtLeast(dpi(120)), Gravity.BOTTOM
        )
        panel.visibility = View.VISIBLE
        setOverlay(false)
        styleFlexPanel()
        updateFlexPanel()
    }

    private fun buildFlexPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpi(24), dpi(16), dpi(24), dpi(16))
            elevation = dp(6f)
        }
        flexInfo = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            maxLines = 2
        }
        panel.addView(flexInfo)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dpi(16), 0, 0)
        }
        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 17f
            setPadding(dpi(18), dpi(14), dpi(18), dpi(14))
            setOnClickListener { onClick() }
        }
        row.addView(button("‹ Anterior") { turnPage(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        flexPlay = button("▶ Escuchar") {
            if (readAloud.playing) readAloud.pause()
            else if (player.visibility == View.VISIBLE) readAloud.play(this, readAloud.chapter, readAloud.offset)
            else startListening(null)
        }
        row.addView(flexPlay, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(10); marginEnd = dpi(10) })
        row.addView(button("Siguiente ›") { turnPage(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))
        return panel
    }

    private fun styleFlexPanel() {
        val panel = flexPanel ?: return
        val t = colors
        val card = ColorUtils.blendARGB(t.paper, t.text, if (t.isDark) 0.08f else 0.04f)
        panel.setBackgroundColor(card)
        flexInfo?.setTextColor(t.secondary)
        val btn = ColorUtils.blendARGB(card, t.text, 0.08f)
        (panel.getChildAt(1) as? LinearLayout)?.let { row ->
            for (i in 0 until row.childCount) (row.getChildAt(i) as? TextView)?.apply {
                setTextColor(if (i == 1) 0xFFFFFFFF.toInt() else t.text)
                background = roundRect(if (i == 1) accent() else btn, dp(16f))
            }
        }
    }

    private fun updateFlexPanel() {
        if (!flexMode) return
        val p = bookView.paginator ?: return
        val page = currentPage()
        val ch = p.pages.getOrNull(page)?.chapter ?: 0
        flexInfo?.text = "${book?.chapterTitle(ch) ?: ""}\nPágina ${page + 1} de ${p.pageCount}"
        updatePlayer()
    }

    // ---------------------------------------------------------------- paginación

    private fun relayout() {
        val b = book ?: return
        val (w, h) = viewSize ?: return
        // En modo continuo la posición ya es la línea exacta
        if (!scrollMode) bookView.paginator?.let { position = it.positionOf(currentPage()) }
        paginateJob?.cancel()
        selectionMenu.dismiss()

        val density = resources.displayMetrics.density
        scrollMode = prefs.scrollMode
        val spread = !scrollMode && when (prefs.layoutMode) {
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
            scrollView.visibility = if (scrollMode) View.VISIBLE else View.GONE
            if (scrollMode) scrollView.setContent(p, m, target)
            pendingHighlight?.let { showHighlight(it) }
            pendingHighlight = null
            if (flashOnLayout) {
                flashOnLayout = false
                flashLine(target)
            }
            updateFlexPanel()
            ruler.bandHeight = paint.fontSpacing * lineSpacing * 3.2f
            seek.max = max(0, p.pageCount - 1)
            seek.progress = currentPage()
            updateInfo(currentPage())
            updateBookmarkIcon()
            if (::sideAdapter.isInitialized && sidePanel.visibility == View.VISIBLE) refreshSide()
        }
    }

    private fun updateInfo(page: Int) {
        val p = bookView.paginator ?: return
        val b = book ?: return
        val pg = p.pages.getOrNull(page) ?: return
        chapterView.text = b.chapterTitle(pg.chapter)
        pageInfo.text = if (isSpread() && page + 1 < p.pageCount) {
            "Páginas ${page + 1}–${page + 2} de ${p.pageCount}"
        } else {
            "Página ${page + 1} de ${p.pageCount}"
        }
        val step = if (isSpread()) 2 else 1
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
        val perPage = dt / (if (isSpread()) 2 else 1)
        if (perPage in 4f..300f) {
            prefs.secondsPerPage = prefs.secondsPerPage * 0.85f + perPage * 0.15f
        }
    }

    /** Suma el tiempo activo de lectura a las estadísticas (máx. 3 min sin interacción). */
    private fun markActivity() {
        val now = SystemClock.elapsedRealtime()
        if (lastActivity > 0L) {
            val dt = ((now - lastActivity) / 1000L).toInt()
            stats.addSeconds(min(dt, 180), entry.key)
        }
        lastActivity = now
    }

    private fun savePosition() {
        if (!::entry.isInitialized) return
        val p = bookView.paginator
        val pos = if (scrollMode) position else p?.positionOf(currentPage()) ?: position
        val progress = when {
            p == null -> entry.progress
            p.pageCount <= 1 -> 1f
            else -> ((currentPage() + (if (isSpread()) 1 else 0)).toFloat() / (p.pageCount - 1)).coerceAtMost(1f)
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
        val done = library.everything().count {
            it.finishedAt > 0L && java.util.Calendar.getInstance().apply { timeInMillis = it.finishedAt }
                .get(java.util.Calendar.YEAR) == year
        } + 1
        val next = library.nextInSeries(entry)
        MaterialAlertDialogBuilder(this)
            .setTitle("¡Terminaste «${entry.title}»!")
            .setMessage(
                "Llevas $done de ${prefs.yearlyGoalBooks} libros este año. ¡Sigue así!" +
                    (next?.let { "\n\nSiguiente de la serie: «${it.title}»" } ?: "")
            )
            .setPositiveButton("Genial", null)
            .apply {
                if (next != null) setNeutralButton("Leer el siguiente") { _, _ ->
                    startActivity(LibraryActivity.readerIntent(this@ReaderActivity, next))
                    finish()
                } else setNeutralButton("Qué leer después") { _, _ ->
                    startActivity(Intent(this@ReaderActivity, DiscoverActivity::class.java))
                }
            }
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
        val page = currentPage()
        return bookmarkFor(page) ?: if (isSpread()) bookmarkFor(page + 1) else null
    }

    private fun toggleBookmark() {
        val p = bookView.paginator ?: return
        val existing = currentBookmark()
        btnBookmark.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (existing != null) {
            notes.removeBookmark(existing)
        } else {
            val page = currentPage()
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

    /** Lo que la notificación y la pantalla de bloqueo controlan. */
    private val speechController = object : ReadAloudService.Controller {
        override fun isPlaying() = readAloud.playing
        override fun play() = readAloud.play(this@ReaderActivity, readAloud.chapter, readAloud.offset)
        override fun pause() = readAloud.pause()
        override fun skip() = readAloud.skipSentence(this@ReaderActivity)
        override fun stop() = stopListening()
        override fun title() = entry.title
        override fun subtitle() = book?.chapterTitle(readAloud.chapter) ?: entry.author
        override fun cover(): android.graphics.Bitmap? = entry.coverPath?.let {
            runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull()
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ReadAloudService.update(this) }

    private fun stopListening() {
        readAloud.stop()
        player.visibility = View.GONE
        sleepAt = 0L
        readAloud.stopAtChapterEnd = false
        handler.removeCallbacks(sleepTick)
        updateTimerLabel()
        ReadAloudService.stop(this)
    }

    private val sleepTick = object : Runnable {
        override fun run() {
            if (sleepAt == 0L) return
            if (SystemClock.elapsedRealtime() >= sleepAt) {
                sleepAt = 0L
                if (readAloud.playing) readAloud.pause()
                toast("Temporizador: lectura detenida")
            } else {
                handler.postDelayed(this, 15_000)
            }
            updateTimerLabel()
        }
    }

    private fun chooseSleepTimer() {
        val minutes = intArrayOf(0, 5, 15, 30, 45, 60)
        val labels = arrayOf("Sin temporizador", "5 minutos", "15 minutos", "30 minutos", "45 minutos", "1 hora", "Al final del capítulo")
        MaterialAlertDialogBuilder(this)
            .setTitle("Detener la lectura")
            .setItems(labels) { _, which ->
                handler.removeCallbacks(sleepTick)
                readAloud.stopAtChapterEnd = which == labels.size - 1
                sleepAt = if (which in 1 until minutes.size) SystemClock.elapsedRealtime() + minutes[which] * 60_000L else 0L
                if (sleepAt > 0L) handler.postDelayed(sleepTick, 15_000)
                updateTimerLabel()
            }
            .show()
    }

    private fun updateTimerLabel() {
        if (!::playerTimer.isInitialized) return
        playerTimer.text = when {
            readAloud.stopAtChapterEnd -> "Fin cap."
            sleepAt > 0L -> "${((sleepAt - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1)} min"
            else -> ""
        }
        playerTimer.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_timer, 0, 0, 0)
        val active = readAloud.stopAtChapterEnd || sleepAt > 0L
        val c = if (active) accent() else colors.secondary
        playerTimer.setTextColor(c)
        playerTimer.compoundDrawableTintList = ColorStateList.valueOf(c)
    }

    private fun showSpeedReader() {
        val p = bookView.paginator ?: return
        val b = book ?: return
        val pos = p.positionOf(currentPage())
        if (readAloud.playing) readAloud.pause()
        SpeedReader(
            this, prefs, colors, accent(), prefs.font.typeface(this),
            textOf = { p.textOf(it) }, chapterCount = b.spine.size,
        ) { ch, off ->
            navigate(p.pageFor(ReadingPosition(ch, off)))
        }.show(pos.chapter, pos.charOffset)
    }

    private fun applyRuler() {
        ruler.visibility = if (prefs.ruler) View.VISIBLE else View.GONE
    }

    private fun startListening(from: ReadingPosition?) {
        val p = bookView.paginator ?: return
        val pos = from ?: p.positionOf(currentPage())
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        setOverlay(false)
        player.visibility = View.VISIBLE
        player.alpha = 0f
        player.animate().alpha(1f).setDuration(200).start()
        readAloud.play(this, pos.chapter, pos.charOffset)
    }

    private fun onSpeechSentence(ch: Int, s: Int, e: Int) {
        if (ch < 0) {
            setSpeakRange(null)
            updatePlayer()
            return
        }
        setSpeakRange(Triple(ch, s, e))
        markActivity()
        // En modo continuo la vista se desplaza sola hasta la frase
        if (scrollMode) return
        val p = bookView.paginator ?: return
        val target = p.pageFor(ReadingPosition(ch, s))
        val step = if (isSpread()) 2 else 1
        val cur = currentPage()
        when {
            target in cur until cur + step -> {}
            target in cur + step until cur + 2 * step -> turnPage(true)
            else -> navigate(target)
        }
    }

    private fun updatePlayer() {
        playerToggle.setImageResource(if (readAloud.playing) R.drawable.ic_pause else R.drawable.ic_play)
        flexPlay?.text = if (readAloud.playing) "❚❚ Pausa" else "▶ Escuchar"
        val r = readAloud.rate
        playerRate.text = if (r == r.toInt().toFloat()) "${r.toInt()}×" else "${r}×".replace('.', ',')
    }

    // ---------------------------------------------------------------- temas

    private fun accent() = AppAccent.color(this, colors.isDark)

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
        if (::scrollView.isInitialized) {
            scrollView.accentColor = accent()
            scrollView.theme = colors
        }
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
        updateTimerLabel()
        ruler.setColors(accent, t.isDark)
        notebookView?.setColors(t.paper, t.text, t.secondary, accent)
        translationPanel?.setColors(t.text, t.secondary)
        styleFlexPanel()
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
            onRuler = { applyRuler() },
            onSpeedRead = { showSpeedReader() },
            onScrollMode = { relayout() },
            onHandsFree = { startHandsFree() },
            onTranslatePage = { showTranslationSheet() },
        ).show()
    }

    // ---------------------------------------------------------------- UI

    private fun setOverlay(show: Boolean) {
        overlayVisible = show
        if (show) {
            selectionMenu.dismiss()
            clearSelections()
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
        sideAdapter.dragEnabled = true
        sideTabs.setOptions(listOf("Índice", "Marcas", "Notas", "Cuaderno", "Traducir"), 0)
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
        val tab = sideTabs.selected
        val extraTab = tab >= 3
        val box = findViewById<FrameLayout>(R.id.sideNotebook)
        box.visibility = if (extraTab) View.VISIBLE else View.GONE
        findViewById<View>(R.id.sideList).visibility = if (extraTab) View.GONE else View.VISIBLE
        if (extraTab) {
            sideStatus.visibility = View.GONE
            if (tab == 3 && notebookView == null) {
                notebookView = NotebookView(this, entry.id, notes).also {
                    box.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    it.setColors(colors.paper, colors.text, colors.secondary, accent())
                }
            }
            if (tab == 4 && translationPanel == null) {
                translationPanel = TranslationPanel(this, lifecycleScope, prefs, book?.language ?: "").also {
                    box.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    it.setColors(colors.text, colors.secondary)
                }
            }
            notebookView?.visibility = if (tab == 3) View.VISIBLE else View.GONE
            translationPanel?.visibility = if (tab == 4) View.VISIBLE else View.GONE
            if (tab == 4) translationPanel?.showPage(currentPageText())
            return
        }
        notebookView?.flush()
        val rows = buildRows(sideTabs.selected, { msg ->
            sideStatus.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
            sideStatus.text = msg
        }, onNavigate = {}, onChanged = {})
        sideAdapter.rows = rows
        if (sideTabs.selected == 2 && notes.highlights.isNotEmpty()) {
            sideStatus.text = "${sideStatus.text} Mantén pulsado uno para arrastrarlo a otra app."
        }
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
        val curChapter = p.pages.getOrNull(currentPage())?.chapter ?: 0
        fun go(page: Int) {
            navigate(page)
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
                if (!isWide() || !prefs.sidePanel) {
                    rows += SheetRow("Cuaderno del libro…", caption = notes.notebook.trim().take(120).ifEmpty { "Notas largas y escritura a mano" },
                        highlighted = true) { showNotebookSheet() }
                }
                if (notes.highlights.isNotEmpty() || notes.bookmarks.isNotEmpty() || notes.notebook.isNotBlank()) {
                    rows += SheetRow("Exportar notas y subrayados…", highlighted = true) { exportNotes() }
                }
                notes.highlights.sortedWith(compareBy({ it.chapter }, { it.start })).forEach { h ->
                    val page = p.pageFor(ReadingPosition(h.chapter, h.start))
                    val title = SpannableString("● ${h.text.trim().take(300)}").apply {
                        setSpan(android.text.style.ForegroundColorSpan(HIGHLIGHT_COLORS.getOrElse(h.color) { HIGHLIGHT_COLORS[0] }),
                            0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val caption = b.chapterTitle(h.chapter) + if (h.note.isNotBlank()) " · Nota: ${h.note.trim()}" else ""
                    val drag = "“${h.text.trim()}”" + (if (h.note.isNotBlank()) "\n\n${h.note.trim()}" else "") +
                        "\n— ${entry.title}" + (if (entry.author.isNotEmpty()) ", ${entry.author}" else "")
                    rows += SheetRow(title, caption = caption, trailing = (page + 1).toString(), dragText = drag, onLongClick = {
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
                        showHighlight(Triple(h.chapter, h.start, h.end))
                    }
                }
                rows
            }
        }
    }

    /** Cuaderno en una hoja a pantalla casi completa (teléfono cerrado). */
    private fun showNotebookSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = NotebookView(this, entry.id, notes)
        val bg = ColorUtils.blendARGB(colors.paper, colors.text, if (colors.isDark) 0.06f else 0.03f)
        view.setBackgroundColor(bg)
        view.setPadding(0, dpi(16), 0, 0)
        view.setColors(bg, colors.text, colors.secondary, accent())
        dialog.setContentView(view, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.88f).toInt()))
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = dpi(760)
        // Arrastrar dentro del cuaderno no debe cerrar la hoja
        dialog.behavior.isDraggable = false
        dialog.setOnDismissListener { view.flush(); afterNotesChanged() }
        dialog.show()
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
                        navigate(page)
                        showHighlight(Triple(ch, off, off + needle.length))
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
                KeyEvent.KEYCODE_VOLUME_DOWN -> { turnPage(true); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { turnPage(false); return true }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> { turnPage(true); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> { turnPage(false); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
