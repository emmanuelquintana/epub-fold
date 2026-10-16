package com.emmanuel.epubfold

import android.app.Activity
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Menú flotante sobre la selección, como el menú de edición de iOS:
 * colores de subrayado arriba y acciones debajo.
 */
class SelectionMenu(private val activity: Activity) {

    private var popup: PopupWindow? = null

    val isShowing get() = popup?.isShowing == true

    fun show(
        parent: View,
        anchor: RectF,
        selectedColor: Int?,
        onColor: (Int) -> Unit,
        actions: List<Pair<String, () -> Unit>>,
    ) {
        dismiss()
        val ctx = activity
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(0xF22C2C2E.toInt(), ctx.dp(14f))
            elevation = ctx.dp(12f)
            setPadding(ctx.dpi(6), ctx.dpi(8), ctx.dpi(6), ctx.dpi(4))
        }
        val dots = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        HIGHLIGHT_COLORS.forEachIndexed { i, c ->
            val size = ctx.dpi(28)
            val dot = View(ctx).apply {
                background = roundRect(c, size / 2f, Color.WHITE, if (selectedColor == i) ctx.dpi(3) else 0)
                contentDescription = HIGHLIGHT_NAMES[i]
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    onColor(i)
                }
            }
            dots.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                marginStart = ctx.dpi(6); marginEnd = ctx.dpi(6)
            })
        }
        col.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(View(ctx).apply { setBackgroundColor(0x33FFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = ctx.dpi(8) })

        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEachIndexed { i, (label, action) ->
            if (i > 0) row.addView(View(ctx).apply { setBackgroundColor(0x33FFFFFF) },
                LinearLayout.LayoutParams(1, ctx.dpi(22)).apply { gravity = Gravity.CENTER_VERTICAL })
            row.addView(TextView(ctx).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(ctx.dpi(12), ctx.dpi(10), ctx.dpi(12), ctx.dpi(10))
                setOnClickListener { action() }
            })
        }
        scroll.addView(row)
        col.addView(scroll)

        val screenW = parent.rootView.width
        col.measure(
            View.MeasureSpec.makeMeasureSpec(screenW - ctx.dpi(24), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = col.measuredWidth
        val h = col.measuredHeight
        val loc = IntArray(2)
        parent.getLocationInWindow(loc)
        val cx = loc[0] + anchor.centerX()
        var x = (cx - w / 2f).toInt()
        x = x.coerceIn(ctx.dpi(8), (loc[0] + parent.width - w - ctx.dpi(8)).coerceAtLeast(ctx.dpi(8)))
        var y = (loc[1] + anchor.top - h - ctx.dpi(22)).toInt()
        if (y < ctx.dpi(40)) y = (loc[1] + anchor.bottom + ctx.dpi(26)).toInt()

        popup = PopupWindow(col, w, h, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = false
            elevation = ctx.dp(12f)
            animationStyle = android.R.style.Animation_Dialog
            showAtLocation(parent, Gravity.NO_GRAVITY, x, y)
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }
}

/** Diálogo para escribir o editar una nota. */
fun showNoteEditor(activity: Activity, quote: String, initial: String, onSave: (String) -> Unit) {
    val ctx = activity
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dpi(22), ctx.dpi(8), ctx.dpi(22), 0)
    }
    box.addView(TextView(ctx).apply {
        text = "“${quote.take(220)}${if (quote.length > 220) "…" else ""}”"
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        setTextAppearance(R.style.Text_Subhead)
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        setPadding(0, 0, 0, ctx.dpi(12))
    })
    val field = EditText(ctx).apply {
        setText(initial)
        hint = "Escribe tu nota"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 3
        gravity = Gravity.TOP
        background = roundRect(ctx.color(R.color.fill), ctx.dp(10f))
        setPadding(ctx.dpi(12), ctx.dpi(10), ctx.dpi(12), ctx.dpi(10))
    }
    box.addView(field)
    MaterialAlertDialogBuilder(ctx)
        .setTitle(if (initial.isEmpty()) "Nueva nota" else "Editar nota")
        .setView(box)
        .setNegativeButton("Cancelar", null)
        .setPositiveButton("Guardar") { _, _ -> onSave(field.text.toString()) }
        .show()
    field.requestFocus()
}

/** Editor del tema "Personal": color de fondo y de texto. */
fun showCustomThemeEditor(activity: Activity, prefs: ReaderPrefs, onSave: () -> Unit) {
    val ctx = activity
    val papers = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFDF6E3.toInt(), 0xFFF4ECD8.toInt(), 0xFFE8F0E3.toInt(), 0xFFE3ECF4.toInt(),
        0xFFF6E6EA.toInt(), 0xFF2B2B2B.toInt(), 0xFF1E2A33.toInt(), 0xFF162119.toInt(), 0xFF000000.toInt(),
    )
    val texts = intArrayOf(
        0xFF000000.toInt(), 0xFF333333.toInt(), 0xFF073642.toInt(), 0xFF3B2A1A.toInt(), 0xFF1D3557.toInt(),
        0xFF2D4A2B.toInt(), 0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt(), 0xFFF2B05E.toInt(), 0xFF9EE6B0.toInt(),
    )
    var paper = prefs.customPaper
    var text = prefs.customText

    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dpi(22), ctx.dpi(8), ctx.dpi(22), 0)
    }
    val preview = TextView(ctx).apply {
        this.text = "Había una vez un lector que quería sus propios colores."
        typeface = Typeface.SERIF
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setPadding(ctx.dpi(16), ctx.dpi(14), ctx.dpi(16), ctx.dpi(14))
    }
    fun refresh() {
        preview.setTextColor(text)
        preview.background = roundRect(paper, ctx.dp(12f), ctx.color(R.color.separator), 1)
    }
    refresh()
    box.addView(preview)

    fun swatchRow(label: String, colors: IntArray, current: () -> Int, set: (Int) -> Unit) {
        box.addView(TextView(ctx).apply {
            this.text = label
            setTextAppearance(R.style.Text_Caption)
            setPadding(0, ctx.dpi(16), 0, ctx.dpi(8))
        })
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val views = ArrayList<View>()
        fun paint() {
            views.forEachIndexed { i, v ->
                val sel = colors[i] == current()
                v.background = roundRect(colors[i], ctx.dp(15f),
                    if (sel) ctx.color(R.color.accent) else ctx.color(R.color.separator), if (sel) ctx.dpi(3) else 1)
            }
        }
        colors.toList().chunked(5).forEach { chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { c ->
                val v = View(ctx).apply {
                    setOnClickListener { set(c); paint(); refresh() }
                }
                views += v
                row.addView(FrameLayout(ctx).apply {
                    addView(v, FrameLayout.LayoutParams(ctx.dpi(30), ctx.dpi(30), Gravity.CENTER))
                }, LinearLayout.LayoutParams(0, ctx.dpi(40), 1f))
            }
            grid.addView(row)
        }
        paint()
        box.addView(grid)
    }
    swatchRow("Fondo", papers, { paper }) { paper = it }
    swatchRow("Texto", texts, { text }) { text = it }

    MaterialAlertDialogBuilder(ctx)
        .setTitle("Tema personal")
        .setView(box)
        .setNegativeButton("Cancelar", null)
        .setPositiveButton("Usar") { _, _ ->
            prefs.customPaper = paper
            prefs.customText = text
            onSave()
        }
        .show()
}
