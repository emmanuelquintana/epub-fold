package com.emmanuel.epubfold

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Claro u oscuro de la biblioteca y del resto de pantallas (el lector usa su propio tema). */
enum class AppMode(val label: String, val hint: String) {
    READER("Lectura", "Claro u oscuro según el tema que uses al leer."),
    SYSTEM("Sistema", "Sigue el modo claro u oscuro del teléfono."),
    LIGHT("Claro", "Siempre claro."),
    DARK("Oscuro", "Siempre oscuro."),
}

/** Color de acento de la app: botones, estanterías, progreso y enlaces. */
enum class AppAccent(val label: String, val colorRes: Int, val overlay: Int) {
    NARANJA("Naranja", R.color.accent_naranja, 0),
    ROJO("Rojo", R.color.accent_rojo, R.style.Accent_Rojo),
    ROSA("Rosa", R.color.accent_rosa, R.style.Accent_Rosa),
    MORADO("Morado", R.color.accent_morado, R.style.Accent_Morado),
    AZUL("Azul", R.color.accent_azul, R.style.Accent_Azul),
    TURQUESA("Turquesa", R.color.accent_turquesa, R.style.Accent_Turquesa),
    VERDE("Verde", R.color.accent_verde, R.style.Accent_Verde),
    GRAFITO("Grafito", R.color.accent_grafito, R.style.Accent_Grafito);

    companion object {
        @Volatile private var cached: AppAccent? = null

        fun current(context: Context): AppAccent =
            cached ?: ReaderPrefs(context).accent.also { cached = it }

        fun set(context: Context, a: AppAccent) {
            ReaderPrefs(context).accent = a
            cached = a
        }
    }
}

/** Modo de AppCompat que toca para las pantallas fuera del lector. */
fun ReaderPrefs.appNightMode(): Int = when (appMode) {
    AppMode.READER -> if (isDarkTheme(effectiveTheme())) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
    AppMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    AppMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    AppMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}

private fun ReaderPrefs.wantsDark(): Boolean = when (appNightMode()) {
    AppCompatDelegate.MODE_NIGHT_YES -> true
    AppCompatDelegate.MODE_NIGHT_NO -> false
    else -> systemIsDark()
}

/** Aplica el color de acento a cada pantalla antes de que infle sus vistas. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val a = AppAccent.current(activity)
                if (a.overlay != 0) activity.theme.applyStyle(a.overlay, true)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

/**
 * Hoja «Apariencia» de la biblioteca. Cada cambio se aplica al momento: la pantalla
 * se vuelve a crear y la hoja reaparece sin animación para seguir probando.
 */
class AppearanceSheet(
    private val activity: Activity,
    private val prefs: ReaderPrefs,
    /** Se llama tras guardar un cambio; la actividad decide cómo recrearse. */
    private val onChanged: (recreate: Boolean) -> Unit,
) {
    private val ctx = activity
    val dialog = BottomSheetDialog(activity)

    fun show(animate: Boolean) {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), ctx.dpi(6), ctx.dpi(16), ctx.dpi(32))
        }
        col.addView(TextView(ctx).apply {
            text = "Apariencia"
            setTextAppearance(R.style.Text_Headline)
            gravity = Gravity.CENTER
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(18))
        })

        // --- Modo
        col.addView(caption("Modo"))
        val modeGroup = group(col)
        val seg = SegmentedControl(ctx)
        val hint = TextView(ctx).apply {
            setTextAppearance(R.style.Text_Footnote)
            text = prefs.appMode.hint
            setPadding(0, ctx.dpi(10), 0, 0)
        }
        seg.setOptions(AppMode.entries.map { it.label }, prefs.appMode.ordinal)
        seg.onSelected = { i ->
            val wasDark = isNight()
            prefs.appMode = AppMode.entries[i]
            hint.text = prefs.appMode.hint
            AppCompatDelegate.setDefaultNightMode(prefs.appNightMode())
            // Si cambia claro/oscuro, AppCompat ya recrea la pantalla
            onChanged(prefs.wantsDark() == wasDark)
        }
        modeGroup.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), ctx.dpi(14), ctx.dpi(16), ctx.dpi(14))
            addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hint)
        })

        col.addView(View(ctx), LinearLayout.LayoutParams(1, ctx.dpi(24)))

        // --- Color
        col.addView(caption("Color"))
        val colorGroup = group(col)
        val current = AppAccent.current(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dpi(12), ctx.dpi(14), ctx.dpi(12), ctx.dpi(6))
        }
        AppAccent.entries.forEach { a -> row.addView(swatch(a, a == current)) }
        colorGroup.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        colorGroup.addView(TextView(ctx).apply {
            setTextAppearance(R.style.Text_Footnote)
            text = current.label
            setPadding(ctx.dpi(16), 0, ctx.dpi(16), ctx.dpi(14))
        })

        val scroll = NestedScrollView(ctx).apply { addView(col) }
        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = ctx.dpi(680)
        if (!animate) dialog.window?.setWindowAnimations(0)
        dialog.show()
    }

    private fun isNight() =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun swatch(a: AppAccent, selected: Boolean): View {
        val c = ContextCompat.getColor(ctx, a.colorRes)
        val size = ctx.dpi(44)
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        val bg = if (selected) {
            // Anillo del mismo color separado del círculo por el fondo del grupo
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0)
                setStroke(ctx.dpi(2), c)
            }
            LayerDrawable(arrayOf(ring, dot)).apply { setLayerInset(1, ctx.dpi(5), ctx.dpi(5), ctx.dpi(5), ctx.dpi(5)) }
        } else LayerDrawable(arrayOf(dot)).apply { setLayerInset(0, ctx.dpi(5), ctx.dpi(5), ctx.dpi(5), ctx.dpi(5)) }
        return FrameLayout(ctx).apply {
            background = bg
            contentDescription = a.label + if (selected) ", seleccionado" else ""
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = ctx.dpi(6) }
            if (selected) addView(View(ctx).apply {
                background = ContextCompat.getDrawable(ctx, R.drawable.ic_check)
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(ctx.dpi(20), ctx.dpi(20), Gravity.CENTER))
            setOnClickListener {
                if (selected) return@setOnClickListener
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                AppAccent.set(ctx, a)
                onChanged(true)
            }
        }
    }

    private fun group(parent: LinearLayout): LinearLayout {
        val g = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(ctx.color(R.color.group_bg), ctx.dp(12f))
            clipToOutline = true
        }
        parent.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return g
    }

    private fun caption(text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(R.style.Text_Caption)
        setPadding(ctx.dpi(16), 0, 0, ctx.dpi(8))
    }
}
