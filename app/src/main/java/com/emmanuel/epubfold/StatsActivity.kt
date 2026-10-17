package com.emmanuel.epubfold

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Anillo de progreso de la meta diaria (estilo Apple Books). */
class RingView(context: Context) : View(context) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    var trackColor = 0
    var color = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    override fun onDraw(c: Canvas) {
        val stroke = width * 0.09f
        paint.strokeWidth = stroke
        oval.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        paint.color = trackColor
        c.drawArc(oval, 0f, 360f, false, paint)
        paint.color = color
        val sweep = 360f * progress.coerceIn(0f, 1f)
        if (sweep > 0.5f) c.drawArc(oval, -90f, sweep, false, paint)
    }
}

/** Barras de minutos por día con línea de meta. Una sola serie: un solo color. */
class BarsView(context: Context) : View(context) {
    var values: List<Pair<String, Int>> = emptyList()
        set(v) { field = v; invalidate() }
    var goal = 20
    var barColor = 0
    var mutedColor = 0
    var labelColor = 0
    var gridColor = 0
    var onSelect: ((Int) -> Unit)? = null
    var selected = -1
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11 * resources.displayMetrics.scaledDensity
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f)
    }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val top = 22 * d
        val bottom = height - 24 * d
        val maxV = max(goal, values.maxOf { it.second }).coerceAtLeast(1) * 1.1f
        val slot = width.toFloat() / values.size
        val bw = min(slot * 0.5f, 28 * d)
        // línea de meta
        val gy = bottom - (bottom - top) * goal / maxV
        line.color = gridColor
        c.drawLine(0f, gy, width.toFloat(), gy, line)
        text.color = labelColor
        text.textAlign = Paint.Align.LEFT
        c.drawText("Meta $goal min", 0f, gy - 5 * d, text)
        text.textAlign = Paint.Align.CENTER
        values.forEachIndexed { i, (label, v) ->
            val cx = slot * i + slot / 2
            val h = (bottom - top) * v / maxV
            bar.color = if (v >= goal) barColor else mutedColor
            if (v > 0) {
                r.set(cx - bw / 2, bottom - max(h, 4 * d), cx + bw / 2, bottom)
                c.drawRoundRect(r, 4 * d, 4 * d, bar)
                c.drawRect(r.left, r.bottom - min(r.height(), 4 * d), r.right, r.bottom, bar)
            } else {
                bar.color = gridColor
                c.drawRect(cx - bw / 2, bottom - 2 * d, cx + bw / 2, bottom, bar)
            }
            text.color = labelColor
            text.typeface = if (i == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.drawText(label, cx, height - 6 * d, text)
            if (i == selected || i == values.size - 1) {
                c.drawText("$v", cx, bottom - max(h, 4 * d) - 6 * d, text)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (values.isEmpty()) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            val i = (e.x / (width.toFloat() / values.size)).toInt().coerceIn(0, values.size - 1)
            if (i != selected) {
                selected = i
                onSelect?.invoke(i)
            }
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }
}

/** Estadísticas y metas de lectura. */
class StatsActivity : AppCompatActivity() {

    private lateinit var prefs: ReaderPrefs
    private lateinit var stats: ReadingStats
    private lateinit var library: Library
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = ReaderPrefs(this)
        stats = ReadingStats(this)
        library = Library(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg_grouped)) }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), dpi(32))
        }
        scroll.addView(col)
        setContentView(scroll)
        capWidth(col, 720)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            col.updatePadding(top = b.top + dpi(8), bottom = b.bottom + dpi(32), left = b.left + dpi(20), right = b.right + dpi(20))
            insets
        }
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night
        build()
    }

    private fun build() {
        col.removeAllViews()
        // Barra superior
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "‹ Biblioteca"
            setTextColor(color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dpi(10), dpi(10), dpi(10))
            setOnClickListener { finish() }
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(TextView(this).apply {
            text = "Metas"
            setTextColor(color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dpi(10), dpi(10), 0, dpi(10))
            setOnClickListener { editGoals() }
        })
        col.addView(bar)
        col.addView(TextView(this).apply {
            text = "Lectura"
            setTextAppearance(R.style.Text_LargeTitle)
            setPadding(0, dpi(4), 0, dpi(16))
        })

        val goal = prefs.dailyGoalMin
        val today = stats.todaySeconds() / 60

        // --- Hoy
        val todayCard = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(18), dpi(18), dpi(18), dpi(18))
        }
        val accentC = color(R.color.accent)
        val fillC = color(R.color.fill)
        val ring = RingView(this).apply {
            trackColor = fillC
            color = accentC
            progress = today.toFloat() / goal.coerceAtLeast(1)
        }
        row.addView(ring, LinearLayout.LayoutParams(dpi(96), dpi(96)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18), 0, 0, 0)
        }
        texts.addView(TextView(this).apply { text = "Hoy"; setTextAppearance(R.style.Text_Caption) })
        texts.addView(TextView(this).apply {
            text = "$today de $goal min"
            setTextAppearance(R.style.Text_Title2)
        })
        texts.addView(TextView(this).apply {
            text = if (today >= goal) "¡Meta diaria cumplida!" else "Te faltan ${goal - today} min para tu meta"
            setTextAppearance(R.style.Text_Subhead)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        todayCard.addView(row)

        // --- Racha y totales
        spacer(12)
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val streak = stats.streak(goal)
        tiles.addView(tile("Racha", "$streak ${if (streak == 1) "día" else "días"}", "Mejor: ${stats.bestStreakDays(goal)}"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(6) })
        val totalH = stats.totalSeconds() / 3600f
        tiles.addView(tile("Tiempo total", if (totalH >= 1) "%.1f h".format(totalH).replace('.', ',') else "${stats.totalSeconds() / 60} min",
            "Desde que usas la app"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(6) })
        col.addView(tiles)

        // --- Últimos 7 días
        spacer(22)
        caption("Últimos 7 días")
        val weekCard = card()
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val days = stats.lastDays(7)
        val detail = TextView(this).apply {
            setTextAppearance(R.style.Text_Footnote)
            setPadding(dpi(16), dpi(12), dpi(16), 0)
            text = "Toca una barra para ver el detalle"
        }
        val bars = BarsView(this).apply {
            values = days.map { (c, m) -> dayFmt.format(c.time).take(3).replaceFirstChar { it.uppercase() } to m }
            this.goal = goal
            barColor = color(R.color.accent)
            mutedColor = (color(R.color.accent) and 0x00FFFFFF) or 0x66000000
            labelColor = color(R.color.label_secondary)
            gridColor = color(R.color.separator)
            onSelect = { i ->
                val (c, m) = days[i]
                detail.text = "${SimpleDateFormat("EEEE d 'de' MMMM", Locale.getDefault()).format(c.time)}: $m min" +
                    if (m >= goal) " · meta cumplida" else ""
            }
        }
        weekCard.addView(detail)
        weekCard.addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(170)).apply {
            setMargins(dpi(16), dpi(4), dpi(16), dpi(12))
        })

        // --- Libros del año
        spacer(22)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val finished = library.all().filter {
            it.finishedAt > 0L && Calendar.getInstance().apply { timeInMillis = it.finishedAt }.get(Calendar.YEAR) == year
        }.sortedBy { it.finishedAt }
        caption("Libros terminados en $year")
        val booksCard = card()
        val yGoal = prefs.yearlyGoalBooks
        booksCard.addView(TextView(this).apply {
            text = "${finished.size} de $yGoal"
            setTextAppearance(R.style.Text_Title2)
            setPadding(dpi(16), dpi(14), dpi(16), 0)
        })
        val progress = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            max = yGoal.coerceAtLeast(1)
            this.progress = min(finished.size, yGoal)
            setIndicatorColor(color(R.color.accent))
            trackColor = color(R.color.fill)
            trackThickness = dpi(6)
            trackCornerRadius = dpi(3)
        }
        booksCard.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dpi(16), dpi(10), dpi(16), dpi(8))
        })
        if (finished.isEmpty()) {
            booksCard.addView(TextView(this).apply {
                text = "Cuando termines un libro aparecerá aquí."
                setTextAppearance(R.style.Text_Subhead)
                setPadding(dpi(16), 0, dpi(16), dpi(16))
            })
        } else {
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val covers = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpi(16), dpi(6), dpi(16), dpi(16))
            }
            finished.forEach { b ->
                val cv = CardView(this).apply {
                    radius = dp(5f)
                    cardElevation = dp(4f)
                    useCompatPadding = true
                }
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.cover_placeholder)
                    b.coverPath?.let { p -> if (File(p).exists()) setImageBitmap(BitmapFactory.decodeFile(p)) }
                    contentDescription = b.title
                }
                cv.addView(iv)
                covers.addView(cv, LinearLayout.LayoutParams(dpi(66), dpi(100)))
            }
            hs.addView(covers)
            booksCard.addView(hs)
        }
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(color(R.color.card), dp(14f))
        }
        col.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return c
    }

    private fun tile(label: String, value: String, sub: String): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(color(R.color.card), dp(14f))
            setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
        }
        c.addView(TextView(this).apply { text = label; setTextAppearance(R.style.Text_Caption) })
        c.addView(TextView(this).apply {
            text = value
            setTextAppearance(R.style.Text_Title2)
            setPadding(0, dpi(4), 0, dpi(2))
        })
        c.addView(TextView(this).apply { text = sub; setTextAppearance(R.style.Text_Footnote) })
        return c
    }

    private fun caption(t: String) {
        col.addView(TextView(this).apply {
            text = t
            setTextAppearance(R.style.Text_Caption)
            setPadding(dpi(4), 0, 0, dpi(8))
        })
    }

    private fun spacer(h: Int) = col.addView(View(this), LinearLayout.LayoutParams(1, dpi(h)))

    private fun editGoals() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22), dpi(8), dpi(22), 0)
        }
        val dailyLabel = TextView(this).apply { setTextAppearance(R.style.Text_Body) }
        val daily = Slider(this).apply {
            valueFrom = 5f; valueTo = 120f; stepSize = 5f
            value = prefs.dailyGoalMin.toFloat().coerceIn(5f, 120f)
        }
        dailyLabel.text = "Minutos al día: ${daily.value.toInt()}"
        daily.addOnChangeListener { _, v, _ -> dailyLabel.text = "Minutos al día: ${v.toInt()}" }
        val yearLabel = TextView(this).apply { setTextAppearance(R.style.Text_Body); setPadding(0, dpi(16), 0, 0) }
        val yearly = Slider(this).apply {
            valueFrom = 1f; valueTo = 100f; stepSize = 1f
            value = prefs.yearlyGoalBooks.toFloat().coerceIn(1f, 100f)
        }
        yearLabel.text = "Libros al año: ${yearly.value.toInt()}"
        yearly.addOnChangeListener { _, v, _ -> yearLabel.text = "Libros al año: ${v.toInt()}" }
        box.addView(dailyLabel); box.addView(daily); box.addView(yearLabel); box.addView(yearly)
        MaterialAlertDialogBuilder(this)
            .setTitle("Metas de lectura")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.dailyGoalMin = daily.value.toInt()
                prefs.yearlyGoalBooks = yearly.value.toInt()
                build()
            }
            .show()
    }
}
