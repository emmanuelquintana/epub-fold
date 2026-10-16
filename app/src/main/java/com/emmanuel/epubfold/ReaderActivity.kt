package com.emmanuel.epubfold

import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextPaint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : AppCompatActivity(), BookView.Listener {

    companion object {
        const val EXTRA_ID = "book_id"
    }

    private lateinit var prefs: ReaderPrefs
    private lateinit var library: Library
    private lateinit var entry: BookEntry

    private lateinit var bookView: BookView
    private lateinit var loading: ProgressBar
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var pageInfo: TextView
    private lateinit var seek: SeekBar
    private lateinit var insetsController: WindowInsetsControllerCompat

    private var book: EpubBook? = null
    private var position = ReadingPosition(0, 0)
    private var viewSize: Pair<Int, Int>? = null
    private var bookPosture = false
    private var paginateJob: Job? = null
    private var overlayVisible = false

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
        position = ReadingPosition(e.chapter, e.charOffset)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_reader)
        bookView = findViewById(R.id.book)
        loading = findViewById(R.id.loading)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        titleView = findViewById(R.id.bookTitle)
        pageInfo = findViewById(R.id.pageInfo)
        seek = findViewById(R.id.seek)

        insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.updatePadding(top = bars.top, left = bars.left + dp(4), right = bars.right + dp(4))
            bottomBar.updatePadding(bottom = bars.bottom + dp(10), left = bars.left + dp(16), right = bars.right + dp(16))
            insets
        }

        titleView.text = entry.title
        bookView.theme = prefs.theme
        bookView.listener = this
        bookView.chapterTitle = { ch -> book?.chapterTitle(ch) ?: "" }
        applyOverlayTheme()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnToc).setOnClickListener { showToc() }
        findViewById<ImageButton>(R.id.btnTheme).setOnClickListener {
            val all = ReaderTheme.entries
            applyReaderTheme(all[(prefs.theme.ordinal + 1) % all.size])
        }
        findViewById<ImageButton>(R.id.btnText).setOnClickListener { showSettings() }

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
        seek.progress = page
        updateInfo(page)
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
        val m = PageMetrics.of(w, h, spread, density)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            @Suppress("DEPRECATION")
            textSize = prefs.fontSizeSp * resources.displayMetrics.scaledDensity
            typeface = if (prefs.serif) Typeface.SERIF else Typeface.SANS_SERIF
            color = prefs.theme.text
            linkColor = prefs.theme.text
        }
        val lineSpacing = prefs.lineSpacing
        val target = position
        loading.visibility = View.VISIBLE

        paginateJob = lifecycleScope.launch {
            val p = withContext(Dispatchers.Default) {
                val pg = Paginator(b, paint, m.contentW, m.contentH, lineSpacing, density)
                pg.paginate { !isActive }
                pg
            }
            loading.visibility = View.GONE
            bookView.setContent(p, m, spread, p.pageFor(target))
            seek.max = maxOf(0, p.pageCount - 1)
            seek.progress = bookView.currentPage
            updateInfo(bookView.currentPage)
        }
    }

    private fun updateInfo(page: Int) {
        val p = bookView.paginator ?: return
        val b = book ?: return
        val pg = p.pages.getOrNull(page) ?: return
        val pct = if (p.pageCount > 1) page * 100 / (p.pageCount - 1) else 100
        pageInfo.text = "${b.chapterTitle(pg.chapter)}  ·  Página ${page + 1} de ${p.pageCount}  ·  $pct%"
    }

    private fun savePosition() {
        if (!::entry.isInitialized) return
        val p = bookView.paginator
        val pos = p?.positionOf(bookView.currentPage) ?: position
        val progress = if (p != null && p.pageCount > 1) bookView.currentPage.toFloat() / (p.pageCount - 1) else entry.progress
        val id = entry.id
        lifecycleScope.launch(Dispatchers.IO) {
            library.updatePosition(id, pos.chapter, pos.charOffset, progress)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun setOverlay(show: Boolean) {
        overlayVisible = show
        listOf(topBar, bottomBar).forEach { v ->
            v.animate().cancel()
            if (show) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(180).start()
            } else {
                v.animate().alpha(0f).setDuration(160).withEndAction { v.visibility = View.GONE }.start()
            }
        }
        if (show) insetsController.show(WindowInsetsCompat.Type.systemBars())
        else insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

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
        val bg = ColorUtils.setAlphaComponent(t.paper, 0xF4)
        topBar.setBackgroundColor(bg)
        bottomBar.setBackgroundColor(bg)
        titleView.setTextColor(t.text)
        pageInfo.setTextColor(t.secondary)
        val tint = ColorStateList.valueOf(t.text)
        listOf(R.id.btnBack, R.id.btnToc, R.id.btnTheme, R.id.btnText).forEach {
            findViewById<ImageButton>(it).imageTintList = tint
        }
        seek.progressTintList = tint
        seek.thumbTintList = tint
        seek.progressBackgroundTintList = ColorStateList.valueOf(t.secondary)
        loading.indeterminateTintList = ColorStateList.valueOf(t.secondary)
        window.decorView.setBackgroundColor(t.paper)
        insetsController.isAppearanceLightStatusBars = !t.isDark
        insetsController.isAppearanceLightNavigationBars = !t.isDark
    }

    private fun showToc() {
        val b = book ?: return
        val p = bookView.paginator ?: return
        if (b.toc.isEmpty()) return
        val titles = b.toc.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Índice")
            .setItems(titles) { _, which ->
                val ch = b.toc[which].spineIndex
                bookView.goTo(p.chapterFirstPage[ch])
                setOverlay(false)
            }
            .show()
    }

    private fun showSettings() {
        val dialog = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        dialog.setContentView(v)

        val fontValue = v.findViewById<TextView>(R.id.fontValue)
        fun showFont() { fontValue.text = prefs.fontSizeSp.toInt().toString() }
        showFont()
        v.findViewById<MaterialButton>(R.id.fontSmaller).setOnClickListener {
            if (prefs.fontSizeSp > 12f) { prefs.fontSizeSp -= 1f; showFont(); relayout() }
        }
        v.findViewById<MaterialButton>(R.id.fontBigger).setOnClickListener {
            if (prefs.fontSizeSp < 36f) { prefs.fontSizeSp += 1f; showFont(); relayout() }
        }

        val themeGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        themeGroup.check(when (prefs.theme) {
            ReaderTheme.LIGHT -> R.id.themeLight
            ReaderTheme.SEPIA -> R.id.themeSepia
            ReaderTheme.DARK -> R.id.themeDark
        })
        themeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            applyReaderTheme(when (id) {
                R.id.themeSepia -> ReaderTheme.SEPIA
                R.id.themeDark -> ReaderTheme.DARK
                else -> ReaderTheme.LIGHT
            })
        }

        val fontGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.fontGroup)
        fontGroup.check(if (prefs.serif) R.id.fontSerif else R.id.fontSans)
        fontGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val serif = id == R.id.fontSerif
            if (serif != prefs.serif) { prefs.serif = serif; relayout() }
        }

        val spacingGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.spacingGroup)
        spacingGroup.check(when {
            prefs.lineSpacing < 1.2f -> R.id.spacingTight
            prefs.lineSpacing > 1.4f -> R.id.spacingWide
            else -> R.id.spacingNormal
        })
        spacingGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.lineSpacing = when (id) {
                R.id.spacingTight -> 1.12f
                R.id.spacingWide -> 1.55f
                else -> 1.3f
            }
            relayout()
        }

        val layoutGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.layoutGroup)
        layoutGroup.check(when (prefs.layoutMode) {
            LayoutMode.AUTO -> R.id.layoutAuto
            LayoutMode.SINGLE -> R.id.layoutSingle
            LayoutMode.DOUBLE -> R.id.layoutDouble
        })
        layoutGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.layoutMode = when (id) {
                R.id.layoutSingle -> LayoutMode.SINGLE
                R.id.layoutDouble -> LayoutMode.DOUBLE
                else -> LayoutMode.AUTO
            }
            relayout()
        }

        val volume = v.findViewById<MaterialSwitch>(R.id.volumeKeys)
        volume.isChecked = prefs.volumeKeys
        volume.setOnCheckedChangeListener { _, checked -> prefs.volumeKeys = checked }

        dialog.show()
    }

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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
