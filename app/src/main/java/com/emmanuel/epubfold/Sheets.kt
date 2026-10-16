package com.emmanuel.epubfold

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** Fila genérica de las hojas de Contenido / Marcadores / Búsqueda. */
class SheetRow(
    val title: CharSequence,
    val caption: CharSequence? = null,
    val trailing: CharSequence? = null,
    val highlighted: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
    var rows: List<SheetRow> = emptyList()
        set(value) {
            field = value
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val caption: TextView = v.findViewById(R.id.rowCaption)
        val title: TextView = v.findViewById(R.id.rowTitle)
        val trailing: TextView = v.findViewById(R.id.rowTrailing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = rows[position]
        val ctx = h.itemView.context
        h.title.text = r.title
        h.title.setTextColor(ctx.color(if (r.highlighted) R.color.accent else R.color.label))
        h.title.typeface = Typeface.create(if (r.highlighted) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        h.caption.visibility = if (r.caption.isNullOrEmpty()) View.GONE else View.VISIBLE
        h.caption.text = r.caption
        h.trailing.text = r.trailing ?: ""
        h.itemView.setOnClickListener { r.onClick() }
        h.itemView.setOnLongClickListener { r.onLongClick?.invoke(); r.onLongClick != null }
    }
}

/** Hoja con título, cabecera opcional y lista. */
class ListSheet(activity: Activity, title: String) {
    val dialog = BottomSheetDialog(activity)
    val view: View = LayoutInflater.from(activity).inflate(R.layout.sheet_list, null)
    val headerBox: FrameLayout = view.findViewById(R.id.headerBox)
    val status: TextView = view.findViewById(R.id.status)
    val adapter = RowAdapter()

    init {
        view.findViewById<TextView>(R.id.sheetTitle).text = title
        val rows = view.findViewById<RecyclerView>(R.id.rows)
        rows.layoutManager = LinearLayoutManager(activity)
        rows.adapter = adapter
        val h = (activity.resources.displayMetrics.heightPixels * 0.82f).toInt()
        dialog.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    fun setStatus(text: String?) {
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        status.text = text
    }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
}

/**
 * Hoja "Temas y ajustes" al estilo Apple Books: brillo, tamaño de letra,
 * muestrario de temas y una lista agrupada para personalizar.
 */
class SettingsSheet(
    private val activity: Activity,
    private val prefs: ReaderPrefs,
    private val onTheme: (ReaderTheme) -> Unit,
    private val onRelayout: () -> Unit,
    private val onAnimation: () -> Unit,
    private val onBrightness: () -> Unit,
    private val onKeepScreenOn: () -> Unit,
) {
    private val ctx = activity
    private val accent = ctx.color(R.color.accent)
    private val themeTiles = ArrayList<Pair<ReaderTheme, View>>()

    fun show() {
        val dialog = BottomSheetDialog(ctx)
        val scroll = NestedScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), ctx.dpi(6), ctx.dpi(16), ctx.dpi(32))
        }
        scroll.addView(col)

        col.addView(TextView(ctx).apply {
            text = "Temas y ajustes"
            setTextAppearance(R.style.Text_Headline)
            gravity = Gravity.CENTER
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(14))
        })

        // --- Brillo
        val brightness = group(col)
        val bRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(14), ctx.dpi(6), ctx.dpi(14), ctx.dpi(6))
        }
        bRow.addView(icon(R.drawable.ic_sun, 16))
        val slider = Slider(ctx).apply {
            valueFrom = 0.02f
            valueTo = 1f
            value = if (prefs.brightness < 0) 0.6f else prefs.brightness.coerceIn(0.02f, 1f)
            trackActiveTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(ctx.color(R.color.seg_selected))
            trackInactiveTintList = ColorStateList.valueOf(ctx.color(R.color.seg_bg))
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            isEnabled = prefs.brightness >= 0
        }
        bRow.addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bRow.addView(icon(R.drawable.ic_sun, 24))
        brightness.addView(bRow)
        brightness.addView(hairline(ctx, ctx.dpi(16)))
        brightness.addView(switchRow("Brillo del sistema", prefs.brightness < 0) { on ->
            prefs.brightness = if (on) -1f else slider.value
            slider.isEnabled = !on
            onBrightness()
        })
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.brightness = value
                onBrightness()
            }
        }

        // --- Tamaño de letra
        spacer(col, 14)
        val sizeGroup = group(col)
        val sizeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sizeLabel = TextView(ctx).apply {
            setTextAppearance(R.style.Text_Subhead)
            gravity = Gravity.CENTER
        }
        fun showSize() { sizeLabel.text = "${prefs.fontSizeSp.toInt()} pt" }
        showSize()
        sizeRow.addView(sizeButton("A", 15f) {
            if (prefs.fontSizeSp > 12f) { prefs.fontSizeSp -= 1f; showSize(); onRelayout() }
        }, LinearLayout.LayoutParams(0, ctx.dpi(52), 1f))
        sizeRow.addView(View(ctx).apply { setBackgroundColor(ctx.color(R.color.separator)) },
            LinearLayout.LayoutParams(1, ctx.dpi(28)))
        sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(ctx.dpi(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        sizeRow.addView(View(ctx).apply { setBackgroundColor(ctx.color(R.color.separator)) },
            LinearLayout.LayoutParams(1, ctx.dpi(28)))
        sizeRow.addView(sizeButton("A", 24f) {
            if (prefs.fontSizeSp < 40f) { prefs.fontSizeSp += 1f; showSize(); onRelayout() }
        }, LinearLayout.LayoutParams(0, ctx.dpi(52), 1f))
        sizeGroup.addView(sizeRow)

        // --- Temas
        spacer(col, 22)
        col.addView(caption("Temas"))
        val perRow = 4
        themeTiles.clear()
        ReaderTheme.entries.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { t -> row.addView(themeTile(t), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(perRow - chunk.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
            col.addView(row)
        }
        refreshTiles()

        // --- Personalizar
        spacer(col, 18)
        col.addView(caption("Personalizar"))
        val custom = group(col)
        custom.addView(segmentRow("Tipografía", listOf("Serif", "Sans"), if (prefs.serif) 0 else 1) {
            prefs.serif = it == 0; onRelayout()
        })
        custom.addView(hairline(ctx, ctx.dpi(16)))
        val spacings = listOf(1.12f, 1.3f, 1.55f)
        custom.addView(segmentRow("Interlineado", listOf("Compacto", "Normal", "Amplio"),
            spacings.indexOfFirst { kotlin.math.abs(it - prefs.lineSpacing) < 0.05f }.coerceAtLeast(1)) {
            prefs.lineSpacing = spacings[it]; onRelayout()
        })
        custom.addView(hairline(ctx, ctx.dpi(16)))
        val margins = listOf(0.6f, 1f, 1.5f)
        custom.addView(segmentRow("Márgenes", listOf("Estrechos", "Normales", "Amplios"),
            margins.indexOfFirst { kotlin.math.abs(it - prefs.marginScale) < 0.05f }.coerceAtLeast(1)) {
            prefs.marginScale = margins[it]; onRelayout()
        })
        custom.addView(hairline(ctx, ctx.dpi(16)))
        custom.addView(segmentRow("Pasar página", PageAnimation.entries.map { it.label }, prefs.animation.ordinal) {
            prefs.animation = PageAnimation.entries[it]; onAnimation()
        })
        custom.addView(hairline(ctx, ctx.dpi(16)))
        custom.addView(segmentRow("Páginas (doble página con el Fold abierto)",
            listOf("Auto", "Una", "Doble"), prefs.layoutMode.ordinal) {
            prefs.layoutMode = LayoutMode.entries[it]; onRelayout()
        })
        custom.addView(hairline(ctx, ctx.dpi(16)))
        custom.addView(switchRow("Justificar texto", prefs.justify) { prefs.justify = it; onRelayout() })
        custom.addView(hairline(ctx, ctx.dpi(16)))
        custom.addView(switchRow("Pasar página con el volumen", prefs.volumeKeys) { prefs.volumeKeys = it })
        custom.addView(hairline(ctx, ctx.dpi(16)))
        custom.addView(switchRow("Mantener pantalla encendida", prefs.keepScreenOn) {
            prefs.keepScreenOn = it; onKeepScreenOn()
        })

        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    // ---------------------------------------------------------------- piezas

    private fun group(parent: LinearLayout): LinearLayout {
        val g = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(ctx.color(R.color.group_bg), ctx.dp(12f))
            clipToOutline = true
        }
        parent.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return g
    }

    private fun spacer(parent: LinearLayout, h: Int) =
        parent.addView(View(ctx), LinearLayout.LayoutParams(1, ctx.dpi(h)))

    private fun caption(text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(R.style.Text_Caption)
        setPadding(ctx.dpi(16), 0, 0, ctx.dpi(8))
    }

    private fun icon(res: Int, sizeDp: Int) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(ctx.color(R.color.label_secondary))
        layoutParams = LinearLayout.LayoutParams(ctx.dpi(sizeDp), ctx.dpi(sizeDp))
    }

    private fun sizeButton(label: String, sizeSp: Float, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = Typeface.SERIF
        setTextColor(ctx.color(R.color.label))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        }
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dpi(50)
            setPadding(ctx.dpi(16), ctx.dpi(4), ctx.dpi(12), ctx.dpi(4))
        }
        row.addView(TextView(ctx).apply {
            text = label
            setTextAppearance(R.style.Text_Body)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = MaterialSwitch(ctx).apply {
            isChecked = checked
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ctx.color(R.color.accent), ctx.color(R.color.seg_bg))
            )
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            trackDecorationTintList = ColorStateList.valueOf(0)
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(sw)
        return row
    }

    private fun segmentRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), ctx.dpi(12), ctx.dpi(16), ctx.dpi(12))
        }
        row.addView(TextView(ctx).apply {
            text = label
            setTextAppearance(R.style.Text_Body)
            setPadding(0, 0, 0, ctx.dpi(8))
        })
        val seg = SegmentedControl(ctx)
        seg.setOptions(options, selected)
        seg.onSelected = onSelect
        row.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun themeTile(t: ReaderTheme): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dpi(5), ctx.dpi(4), ctx.dpi(5), ctx.dpi(10))
        }
        val swatch = TextView(ctx).apply {
            text = "Aa"
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(t.text)
            tag = t
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onTheme(t)
                refreshTiles()
            }
        }
        box.addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(62)))
        box.addView(TextView(ctx).apply {
            text = t.label
            gravity = Gravity.CENTER
            maxLines = 1
            setTextAppearance(R.style.Text_Footnote)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, ctx.dpi(6), 0, 0)
        })
        themeTiles += t to swatch
        return box
    }

    private fun refreshTiles() {
        val current = prefs.theme
        themeTiles.forEach { (t, v) ->
            val sel = t == current
            v.background = roundRect(
                t.paper, ctx.dp(14f),
                if (sel) accent else ctx.color(R.color.separator),
                if (sel) ctx.dpi(3) else ctx.dpi(1)
            )
        }
    }
}
