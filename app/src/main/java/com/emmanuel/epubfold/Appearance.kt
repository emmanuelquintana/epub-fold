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

        /** El acento elegido en su versión clara u oscura (el lector tiene su propio modo). */
        fun color(context: Context, dark: Boolean): Int {
            val cfg = android.content.res.Configuration(context.resources.configuration)
            cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            return context.createConfigurationContext(cfg).getColor(current(context).colorRes)
        }

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
                // Con un tema de estantería, los controles de Android usan el acento predefinido más parecido
                val a = LibraryTheme.current(activity)?.nearestAccent ?: AppAccent.current(activity)
                if (a.overlay != 0) activity.theme.applyStyle(a.overlay, true)
                if (activity !is ReaderActivity && activity !is ImageReaderActivity) {
                    LibraryTheme.override(activity, R.color.bg_grouped)?.let {
                        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(it))
                    }
                }
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

        // --- Tema desde un libro (colores de una portada para toda la estantería)
        col.addView(View(ctx), LinearLayout.LayoutParams(1, ctx.dpi(24)))
        col.addView(caption("Tema desde un libro"))
        val themeGroup = group(col)
        buildLibraryThemes(themeGroup)

        val scroll = NestedScrollView(ctx).apply { addView(col) }
        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = ctx.dpi(680)
        if (!animate) dialog.window?.setWindowAnimations(0)
        dialog.show()
    }

    private fun buildLibraryThemes(g: LinearLayout) {
        val active = LibraryTheme.current(ctx)
        g.addView(TextView(ctx).apply {
            setTextAppearance(R.style.Text_Footnote)
            text = if (active != null) "Ahora: ${active.name}. Elige otra portada o quítalo."
            else "Elige una portada: la biblioteca toma su fondo y su color, en claro u oscuro."
            setPadding(ctx.dpi(16), ctx.dpi(14), ctx.dpi(16), ctx.dpi(6))
        })
        val books = Library(ctx).all().filter { it.coverPath != null && java.io.File(it.coverPath).exists() }
        val previews = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dpi(12), 0, ctx.dpi(12), ctx.dpi(12))
        }
        val covers = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dpi(12), ctx.dpi(4), ctx.dpi(12), ctx.dpi(8))
        }
        fun showFor(b: BookEntry) {
            val bmp = BookThemes.loadCover(b.coverPath) ?: return
            previews.removeAllViews()
            LibraryTheme.generate(bmp, b.title).forEach { t ->
                previews.addView(themePreview(t, b),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(ctx.dpi(4), 0, ctx.dpi(4), 0)
                    })
            }
        }
        books.forEach { b ->
            val w = ctx.dpi(58)
            covers.addView(androidx.cardview.widget.CardView(ctx).apply {
                radius = ctx.dp(5f)
                cardElevation = ctx.dp(3f)
                useCompatPadding = true
                contentDescription = b.title
                addView(android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(BookThemes.loadCover(b.coverPath))
                }, ViewGroup.LayoutParams(w, w * 3 / 2))
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    showFor(b)
                }
            })
        }
        if (books.isEmpty()) {
            g.addView(TextView(ctx).apply {
                setTextAppearance(R.style.Text_Subhead)
                text = "Ningún libro tiene portada todavía."
                setPadding(ctx.dpi(16), 0, ctx.dpi(16), ctx.dpi(14))
            })
            return
        }
        g.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(covers)
        })
        g.addView(previews)
        (books.firstOrNull { it.title == active?.source } ?: books.first()).let { showFor(it) }
        if (active != null) {
            g.addView(hairline(ctx, ctx.dpi(16)))
            g.addView(TextView(ctx).apply {
                text = "Quitar tema del libro"
                setTextColor(0xFFFF3B30.toInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(ctx.dpi(16), ctx.dpi(14), ctx.dpi(16), ctx.dpi(14))
                setOnClickListener {
                    LibraryTheme.set(ctx, null)
                    onChanged(true)
                }
            })
        }
    }

    /** Miniatura de la biblioteca con los colores del tema: fondo, tarjeta, portada y botón de acento. */
    private fun themePreview(t: LibraryTheme, b: BookEntry): View {
        val label = if (t.dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(t.background, ctx.dp(14f), ctx.color(R.color.separator), 1)
            setPadding(ctx.dpi(10), ctx.dpi(10), ctx.dpi(10), ctx.dpi(10))
        }
        box.addView(TextView(ctx).apply {
            text = if (t.dark) "Oscuro" else "Claro"
            setTextColor(label)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(t.group, ctx.dp(10f))
            setPadding(ctx.dpi(8), ctx.dpi(8), ctx.dpi(8), ctx.dpi(8))
        }
        card.addView(android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageBitmap(BookThemes.loadCover(b.coverPath))
        }, LinearLayout.LayoutParams(ctx.dpi(26), ctx.dpi(39)))
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(8), 0, 0, 0)
            addView(TextView(ctx).apply {
                text = "SEGUIR LEYENDO"
                setTextColor(t.accent)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(ctx).apply {
                text = "Continuar"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                background = roundRect(t.accent, ctx.dp(10f))
                setPadding(ctx.dpi(8), ctx.dpi(2), ctx.dpi(8), ctx.dpi(2))
            })
        })
        box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(8)
        })
        val selected = LibraryTheme.current(ctx)?.let { it.source == t.source && it.dark == t.dark } == true
        box.addView(TextView(ctx).apply {
            text = if (selected) "En uso" else "Usar"
            gravity = Gravity.CENTER
            setTextColor(if (selected) label else t.accent)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, ctx.dpi(10), 0, 0)
        })
        box.setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            val wasDark = isNight()
            LibraryTheme.set(ctx, t)
            // El tema se creó para claro u oscuro: la app se pone en ese modo
            prefs.appMode = if (t.dark) AppMode.DARK else AppMode.LIGHT
            AppCompatDelegate.setDefaultNightMode(prefs.appNightMode())
            onChanged(t.dark == wasDark)
        }
        return box
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
