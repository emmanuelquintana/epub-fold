package com.emmanuel.epubfold

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Buscador de imágenes (Google, Bing o DuckDuckGo) dentro de la app: al mantener
 * pulsada una imagen se puede usar como portada del libro.
 */
class CoverWebActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BOOK = "book"
        private const val EXTRA_QUERY = "query"

        fun intent(context: Context, bookId: String, query: String) =
            Intent(context, CoverWebActivity::class.java).putExtra(EXTRA_BOOK, bookId).putExtra(EXTRA_QUERY, query)
    }

    private enum class Engine(val label: String) {
        GOOGLE("Google Imágenes"), BING("Bing"), DDG("DuckDuckGo");

        fun url(q: String): String {
            val e = URLEncoder.encode(q, "UTF-8")
            return when (this) {
                GOOGLE -> "https://www.google.com/search?tbm=isch&q=$e"
                BING -> "https://www.bing.com/images/search?q=$e"
                DDG -> "https://duckduckgo.com/?ia=images&iax=images&q=$e"
            }
        }
    }

    private lateinit var web: WebView
    private lateinit var bookId: String
    private var query = ""
    private var engine = Engine.GOOGLE
    private val chips = ArrayList<Pair<Engine, TextView>>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getStringExtra(EXTRA_BOOK) ?: run { finish(); return }
        query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_grouped))
        }
        // Barra superior: cerrar + buscadores
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(8), dpi(12), dpi(6))
        }
        bar.addView(TextView(this).apply {
            text = "Cerrar"
            setTextColor(color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dpi(4), dpi(6), dpi(12), dpi(6))
            setOnClickListener { finish() }
        })
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Engine.entries.forEach { e ->
            val c = TextView(this).apply {
                text = e.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dpi(14), dpi(7), dpi(14), dpi(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dpi(8) }
                setOnClickListener {
                    engine = e
                    paintChips()
                    web.loadUrl(e.url(query))
                }
            }
            chips += e to c
            chipRow.addView(c)
        }
        bar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        root.addView(TextView(this).apply {
            text = "Toca una imagen para verla grande y mantenla pulsada para usarla como portada."
            setTextAppearance(R.style.Text_Footnote)
            setPadding(dpi(16), 0, dpi(16), dpi(8))
        })

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(3)))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {}
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
                }
            }
            setOnLongClickListener {
                val r = hitTestResult
                if (r.type == WebView.HitTestResult.IMAGE_TYPE || r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    r.extra?.let { confirm(it) }
                    true
                } else false
            }
        }
        root.addView(FrameLayout(this).apply { addView(web) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(top = b.top, bottom = b.bottom, left = b.left, right = b.right)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        paintChips()
        web.loadUrl(engine.url(query))
    }

    private fun paintChips() {
        chips.forEach { (e, c) ->
            val on = e == engine
            c.setTextColor(if (on) 0xFFFFFFFF.toInt() else color(R.color.label))
            c.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            c.background = roundRect(if (on) color(R.color.accent) else color(R.color.seg_bg), dp(16f))
        }
    }

    private fun confirm(src: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Usar esta imagen como portada?")
            .setMessage(if (src.startsWith("data:")) "Es una miniatura: si se ve borrosa, tócala primero para abrirla en grande."
                else src.take(160))
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Usar") { _, _ -> use(src) }
            .show()
    }

    private fun use(src: String) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    if (src.startsWith("data:")) Base64.decode(src.substringAfter(","), Base64.DEFAULT)
                    else CoverSearch.download(src)
                }.getOrNull()
            }
            val ok = bytes != null && withContext(Dispatchers.IO) {
                runCatching { Library(this@CoverWebActivity).setCover(bookId, bytes!!) }.getOrDefault(false)
            }
            if (ok) {
                Toast.makeText(this@CoverWebActivity, "Portada actualizada", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this@CoverWebActivity, "No se pudo usar esa imagen. Ábrela en grande e inténtalo otra vez.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
