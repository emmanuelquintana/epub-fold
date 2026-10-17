package com.emmanuel.epubfold

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pantalla con lista agrupada al estilo de Ajustes de iOS (como Cuenta):
 * «‹ Biblioteca», título grande y grupos de filas. Las subclases rellenan [build].
 */
abstract class GroupedActivity : AppCompatActivity() {

    protected lateinit var col: LinearLayout
    protected lateinit var prefs: ReaderPrefs
    protected abstract val screenTitle: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = ReaderPrefs(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg_grouped)) }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), dpi(32))
        }
        scroll.addView(col)
        setContentView(scroll)
        capWidth(col)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            col.updatePadding(top = b.top + dpi(8), bottom = b.bottom + dpi(32), left = b.left + dpi(20), right = b.right + dpi(20))
            insets
        }
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night
        rebuild()
    }

    /** Vuelve a dibujar la pantalla entera. */
    fun rebuild() {
        col.removeAllViews()
        col.addView(TextView(this).apply {
            text = "‹ Atrás"
            setTextColor(color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dpi(10), 0, dpi(10))
            setOnClickListener { finish() }
        })
        col.addView(TextView(this).apply {
            text = screenTitle
            setTextAppearance(R.style.Text_LargeTitle)
            setPadding(0, dpi(4), 0, dpi(16))
        })
        build()
    }

    protected abstract fun build()

    // ---------------------------------------------------------------- piezas

    fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    fun caption(t: String) = col.addView(TextView(this).apply {
        text = t
        setTextAppearance(R.style.Text_Caption)
        setPadding(dpi(16), 0, 0, dpi(8))
    })

    fun footnote(t: String) = col.addView(TextView(this).apply {
        text = t
        setTextAppearance(R.style.Text_Footnote)
        setPadding(dpi(16), dpi(8), dpi(16), 0)
    })

    fun spacer(h: Int) = col.addView(View(this), LinearLayout.LayoutParams(1, dpi(h)))

    fun group(): LinearLayout {
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(color(R.color.card), dp(12f))
            clipToOutline = true
        }
        col.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return g
    }

    fun paragraph(t: CharSequence) = TextView(this).apply {
        text = t
        setTextAppearance(R.style.Text_Subhead)
        setPadding(dpi(16), dpi(14), dpi(16), dpi(12))
    }

    fun field(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setText(value)
        setSingleLine()
        background = roundRect(color(R.color.fill), dp(10f))
        setPadding(dpi(12), dpi(11), dpi(12), dpi(11))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dpi(16), dpi(6), dpi(16), dpi(6))
        }
    }

    fun pill(label: String, filled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else color(R.color.accent))
        background = roundRect(if (filled) color(R.color.accent) else color(R.color.fill), dp(12f))
        setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        setOnClickListener { onClick() }
    }

    fun row(
        title: CharSequence,
        sub: CharSequence? = null,
        trailing: CharSequence? = null,
        destructive: Boolean = false,
        plain: Boolean = false,
        onLongClick: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dpi(52)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(10), dpi(16), dpi(10))
            if (onClick != null || onLongClick != null) {
                val tv = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                foreground = getDrawable(tv.resourceId)
            }
            onClick?.let { c -> setOnClickListener { c() } }
            onLongClick?.let { c -> setOnLongClickListener { c(); true } }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextAppearance(R.style.Text_Body)
            setTextColor(
                when {
                    destructive -> 0xFFFF3B30.toInt()
                    plain || onClick == null -> color(R.color.label)
                    else -> color(R.color.accent)
                }
            )
        })
        if (!sub.isNullOrEmpty()) texts.addView(TextView(this).apply { text = sub; setTextAppearance(R.style.Text_Footnote) })
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!trailing.isNullOrEmpty()) r.addView(TextView(this).apply {
            text = trailing
            setTextAppearance(R.style.Text_Footnote)
            setPadding(dpi(10), 0, 0, 0)
        })
        return r
    }

    /** Añade filas a un grupo separadas por líneas finas. */
    fun LinearLayout.rows(vararg views: View) {
        views.forEachIndexed { i, v ->
            if (i > 0 || childCount > 0) addView(hairline(this@GroupedActivity, dpi(16)))
            addView(v)
        }
    }
}
