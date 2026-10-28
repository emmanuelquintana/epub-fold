package com.emmanuel.epubfold

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/** «Tu año en lectura»: tarjetas al estilo Wrapped que se pasan deslizando y se pueden compartir. */
class WrappedActivity : AppCompatActivity() {

    private data class Card(val colors: IntArray, val kicker: String, val big: String, val text: String, val covers: List<String> = emptyList())

    private lateinit var pager: RecyclerView
    private lateinit var dots: TextView
    private var cards: List<Card> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val year = intent.getIntExtra("year", Calendar.getInstance().get(Calendar.YEAR))
        cards = buildCards(year)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        pager = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@WrappedActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = CardAdapter()
        }
        PagerSnapHelper().attachToRecyclerView(pager)
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
        }
        bar.addView(TextView(this).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dpi(6), dpi(6), dpi(16), dpi(6))
            setOnClickListener { finish() }
        })
        dots = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        bar.addView(dots, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "Compartir"
            setTextColor(0xFF000000.toInt())
            background = roundRect(0xFFFFFFFF.toInt(), dp(16f))
            setPadding(dpi(14), dpi(6), dpi(14), dpi(6))
            setOnClickListener { shareCurrent() }
        })
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, i ->
            val b = i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            bar.setPadding(b.left + dpi(16), b.top + dpi(8), b.right + dpi(16), dpi(8))
            i
        }
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) = updateDots()
        })
        updateDots()
    }

    private fun current(): Int =
        (pager.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)

    private fun updateDots() {
        val c = current()
        dots.text = cards.indices.joinToString(" ") { if (it == c) "●" else "○" }
    }

    private fun shareCurrent() {
        val v = pager.layoutManager?.findViewByPosition(current()) ?: return
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "mi-anio-lector.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartir"))
    }

    // ---------------------------------------------------------------- datos

    private fun buildCards(year: Int): List<Card> {
        val stats = ReadingStats(this)
        val library = Library(this).everything()
        val prefs = ReaderPrefs(this)
        fun inYear(t: Long) = t > 0 && Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR) == year

        val yearDays = stats.days.filterKeys { it.startsWith("$year-") }
        val minutes = yearDays.values.sum() / 60
        val finished = library.filter { inYear(it.finishedAt) }.sortedBy { it.finishedAt }
        val byBook = stats.books.filterKeys { it.startsWith("$year|") }.mapKeys { it.key.substringAfter('|') }
        val topBook = byBook.maxByOrNull { it.value }?.let { (k, s) -> library.firstOrNull { it.key == k }?.let { it to s } }
        val authorTime = HashMap<String, Int>()
        byBook.forEach { (k, s) -> library.firstOrNull { it.key == k }?.author?.takeIf { it.isNotBlank() }?.let { a -> authorTime[a] = (authorTime[a] ?: 0) + s } }
        // Por tiempo de lectura; si no hay datos, el autor del que más libros terminaste
        val topAuthor: Pair<String, Int>? = authorTime.maxByOrNull { it.value }?.toPair()
            ?: finished.filter { it.author.isNotBlank() }.groupBy { it.author }.maxByOrNull { it.value.size }?.let { it.key to 0 }
        val months = IntArray(12)
        yearDays.forEach { (k, v) -> k.substring(5, 7).toIntOrNull()?.let { m -> months[m - 1] += v } }
        val bestMonth = months.indices.maxByOrNull { months[it] } ?: 0
        val monthName = DateFormatSymbols(Locale("es")).months[bestMonth].replaceFirstChar { it.uppercase() }
        val activeDays = yearDays.count { it.value >= 60 }
        val highlights = library.flatMap { b -> Annotations(this, b.id).highlights.filter { inYear(it.created) }.map { b to it } }
        val quote = highlights.filter { it.second.note.isNotBlank() }.maxByOrNull { it.second.created }
            ?: highlights.filter { it.second.text.length in 40..280 }.maxByOrNull { it.second.text.length }
        val vocab = Vocabulary(this).words.count { inYear(it.created) }

        val out = ArrayList<Card>()
        val hours = minutes / 60
        out += Card(intArrayOf(0xFFFF9500.toInt(), 0xFFFF2D55.toInt()), "Tu año en lectura",
            "$year", if (minutes == 0) "Aún no hay lecturas registradas este año. ¡Abre un libro!"
            else "Leíste ${if (hours > 0) "$hours h ${minutes % 60} min" else "$minutes minutos"} en $activeDays días.")
        out += Card(intArrayOf(0xFF5856D6.toInt(), 0xFF0A84FF.toInt()), "Libros terminados",
            "${finished.size}", when {
                finished.isEmpty() -> "Ninguno todavía: tu meta es ${prefs.yearlyGoalBooks}."
                finished.size >= prefs.yearlyGoalBooks -> "¡Cumpliste tu meta de ${prefs.yearlyGoalBooks}! 🎉"
                else -> "Tu meta: ${prefs.yearlyGoalBooks}. Te faltan ${prefs.yearlyGoalBooks - finished.size}."
            }, finished.mapNotNull { it.coverPath }.takeLast(6))
        topBook?.let { (b, s) ->
            out += Card(intArrayOf(0xFF34C759.toInt(), 0xFF0E7C3A.toInt()), "El libro al que más tiempo dedicaste",
                b.title, "${s / 3600} h ${(s / 60) % 60} min" + (if (b.author.isNotEmpty()) " · ${b.author}" else ""),
                listOfNotNull(b.coverPath))
        }
        topAuthor?.let { a ->
            out += Card(intArrayOf(0xFFAF52DE.toInt(), 0xFF5E2B8C.toInt()), "Tu autor del año", a.first,
                if (a.second > 0) "${a.second / 3600} h ${(a.second / 60) % 60} min leyéndole" else "El que más terminaste")
        }
        if (minutes > 0) out += Card(intArrayOf(0xFFFF3B30.toInt(), 0xFF8E1F1A.toInt()), "Tu mes más lector",
            monthName, "${months[bestMonth] / 60} minutos · mejor racha: ${stats.bestStreakDays(prefs.dailyGoalMin)} días seguidos cumpliendo tu meta")
        quote?.let { (b, h) ->
            out += Card(intArrayOf(0xFF1C1C1E.toInt(), 0xFF3A3A3C.toInt()), "${highlights.size} subrayados · una frase tuya",
                "“${h.text.trim().take(240)}”", b.title + if (h.note.isNotBlank()) "\nTu nota: ${h.note.trim().take(140)}" else "")
        }
        if (vocab > 0) out += Card(intArrayOf(0xFF0A84FF.toInt(), 0xFF00C7BE.toInt()), "Palabras nuevas", "$vocab",
            "palabras guardadas en tu vocabulario")
        out += Card(intArrayOf(0xFFFF9500.toInt(), 0xFFFFCC00.toInt()), "EpubFold", "¡Gracias por leer!",
            "Toca «Compartir» para enviar cualquier tarjeta.")
        return out
    }

    // ---------------------------------------------------------------- tarjetas

    private inner class CardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = cards.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(parent.width, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val box = holder.itemView as FrameLayout
            box.layoutParams = RecyclerView.LayoutParams(pager.width, ViewGroup.LayoutParams.MATCH_PARENT)
            box.removeAllViews()
            val c = cards[position]
            box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, c.colors)
            val col = LinearLayout(this@WrappedActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(32), dpi(80), dpi(32), dpi(48))
            }
            box.addView(col, FrameLayout.LayoutParams(minOf(pager.width, dpi(620)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
            col.addView(TextView(this@WrappedActivity).apply {
                text = c.kicker.uppercase()
                setTextColor(0xD9FFFFFF.toInt())
                letterSpacing = 0.1f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            col.addView(TextView(this@WrappedActivity).apply {
                text = c.big
                setTextColor(0xFFFFFFFF.toInt())
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (c.big.length < 8) 88f else if (c.big.length < 40) 40f else 26f)
                setPadding(0, dpi(10), 0, dpi(10))
            })
            col.addView(TextView(this@WrappedActivity).apply {
                text = c.text
                setTextColor(0xF2FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setLineSpacing(0f, 1.2f)
            })
            if (c.covers.isNotEmpty()) {
                val row = LinearLayout(this@WrappedActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dpi(28), 0, 0)
                }
                c.covers.forEach { path ->
                    val bmp = Widgets.cover(path, 240) ?: return@forEach
                    row.addView(ImageView(this@WrappedActivity).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        elevation = dp(6f)
                        clipToOutline = true
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        background = roundRect(0xFF000000.toInt(), dp(6f))
                    }, LinearLayout.LayoutParams(dpi(if (c.covers.size == 1) 120 else 64), dpi(if (c.covers.size == 1) 180 else 96)).apply {
                        marginEnd = dpi(10)
                    })
                }
                col.addView(row)
            }
            box.visibility = View.VISIBLE
        }
    }

}
