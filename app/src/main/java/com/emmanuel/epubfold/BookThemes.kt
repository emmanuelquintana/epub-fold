package com.emmanuel.epubfold

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.palette.graphics.Palette
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Un tema de lectura creado a partir de la portada de un libro o cómic. */
data class BookTheme(
    val id: String,
    val name: String,
    val paper: Int,
    val text: Int,
    val accent: Int,
    val source: String,
)

/** Temas generados guardados en el teléfono. */
object BookThemes {

    private fun sp(c: Context) = c.getSharedPreferences("book_themes", Context.MODE_PRIVATE)

    fun all(context: Context): List<BookTheme> = runCatching {
        val arr = JSONArray(sp(context).getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BookTheme(o.getString("id"), o.optString("name"), o.getInt("paper"), o.getInt("text"),
                o.optInt("accent"), o.optString("source"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, list: List<BookTheme>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("paper", it.paper)
                .put("text", it.text).put("accent", it.accent).put("source", it.source))
        }
        sp(context).edit().putString("list", arr.toString()).apply()
    }

    fun add(context: Context, t: BookTheme) = save(context, listOf(t) + all(context).filter { it.id != t.id }.take(23))

    fun remove(context: Context, id: String) = save(context, all(context).filter { it.id != id })

    /** Usa el tema: sus colores pasan al tema «Personal». */
    fun apply(prefs: ReaderPrefs, t: BookTheme) {
        prefs.customPaper = t.paper
        prefs.customText = t.text
        prefs.theme = ReaderTheme.PERSONAL
    }

    fun isActive(prefs: ReaderPrefs, t: BookTheme) =
        prefs.theme == ReaderTheme.PERSONAL && prefs.customPaper == t.paper && prefs.customText == t.text

    // ---------------------------------------------------------------- generador

    /** Ajusta la luminosidad (HSL) de un color hasta lograr el contraste pedido contra [bg]. */
    private fun withContrast(color: Int, bg: Int, min: Double, lighter: Boolean): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        var c = color
        var guard = 0
        while (ColorUtils.calculateContrast(c or 0xFF000000.toInt(), bg or 0xFF000000.toInt()) < min && guard < 40) {
            hsl[2] = (hsl[2] + if (lighter) 0.025f else -0.025f).coerceIn(0f, 1f)
            c = ColorUtils.HSLToColor(hsl)
            guard++
        }
        return c or 0xFF000000.toInt()
    }

    private fun hsl(color: Int, sat: Float? = null, maxSat: Float = 1f, light: Float): Int {
        val h = FloatArray(3)
        ColorUtils.colorToHSL(color, h)
        h[1] = (sat ?: h[1]).coerceAtMost(maxSat)
        h[2] = light
        return ColorUtils.HSLToColor(h) or 0xFF000000.toInt()
    }

    /** Tres variantes (clara, oscura y viva) a partir de los colores de la portada. */
    fun generate(cover: Bitmap, title: String): List<BookTheme> {
        val p = Palette.from(cover).maximumColorCount(24).generate()
        val dominant = p.getDominantColor(0xFF7A6A55.toInt())
        val vibrant = p.getVibrantColor(p.getLightVibrantColor(dominant))
        val darkBase = p.getDarkMutedColor(p.getDarkVibrantColor(dominant))
        val lightBase = p.getLightMutedColor(p.getLightVibrantColor(dominant))
        val accent = p.getVibrantColor(p.getDarkVibrantColor(vibrant)) or 0xFF000000.toInt()
        val short = cleanBookTitle(title).let { if (it.length > 22) it.take(21).trimEnd() + "…" else it }

        // Clara: papel muy claro con un toque del tono de la portada, tinta del tono oscuro
        val lightPaper = hsl(lightBase, maxSat = 0.35f, light = 0.93f)
        val lightText = withContrast(hsl(darkBase, maxSat = 0.45f, light = 0.18f), lightPaper, 9.0, lighter = false)

        // Oscura: fondo casi negro teñido, letra clara cálida
        val darkPaper = hsl(darkBase, maxSat = 0.35f, light = 0.08f)
        val darkText = withContrast(hsl(lightBase, maxSat = 0.25f, light = 0.84f), darkPaper, 9.0, lighter = true)

        // Viva: el color principal de la portada como fondo profundo
        val vividPaper = hsl(vibrant, maxSat = 0.6f, light = 0.16f)
        val vividText = withContrast(hsl(p.getLightVibrantColor(lightBase), maxSat = 0.5f, light = 0.88f), vividPaper, 8.0, lighter = true)

        return listOf(
            BookTheme(UUID.randomUUID().toString(), "$short · Claro", lightPaper, lightText, accent, title),
            BookTheme(UUID.randomUUID().toString(), "$short · Oscuro", darkPaper, darkText, accent, title),
            BookTheme(UUID.randomUUID().toString(), "$short · Vivo", vividPaper, vividText, accent, title),
        )
    }

    fun loadCover(path: String?): Bitmap? {
        if (path == null || !File(path).exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 200) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * Hoja «Crear tema desde un libro»: eliges una portada de tu biblioteca, se generan
 * tres variantes con sus colores y guardas las que quieras.
 */
class BookThemeSheet(
    private val activity: Activity,
    private val prefs: ReaderPrefs,
    private val preselected: BookEntry? = null,
    private val onApplied: () -> Unit,
) {
    private val ctx = activity
    private val dialog = BottomSheetDialog(activity)
    private lateinit var previews: LinearLayout
    private lateinit var hint: TextView

    fun show() {
        val scroll = NestedScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(28))
        }
        scroll.addView(col)
        col.addView(TextView(ctx).apply {
            text = "Tema desde un libro"
            setTextAppearance(R.style.Text_Headline)
            gravity = Gravity.CENTER
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(4))
        })
        col.addView(TextView(ctx).apply {
            text = "Elige una portada de tu biblioteca: se crean un tema claro, uno oscuro y uno vivo con sus colores."
            setTextAppearance(R.style.Text_Footnote)
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(24), 0, ctx.dpi(24), ctx.dpi(12))
        })

        val books = Library(ctx).all().filter { it.coverPath != null && File(it.coverPath).exists() }
        if (books.isEmpty()) {
            col.addView(TextView(ctx).apply {
                text = "Ningún libro tiene portada todavía. Añade una con «Cambiar portada…»."
                setTextAppearance(R.style.Text_Subhead)
                gravity = Gravity.CENTER
                setPadding(ctx.dpi(24), ctx.dpi(12), ctx.dpi(24), ctx.dpi(12))
            })
        } else {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            books.forEach { b -> row.addView(coverTile(b)) }
            col.addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(ctx.dpi(12), 0, ctx.dpi(12), 0)
                addView(row)
            })
        }

        hint = TextView(ctx).apply {
            setTextAppearance(R.style.Text_Caption)
            setPadding(ctx.dpi(20), ctx.dpi(18), ctx.dpi(20), ctx.dpi(8))
            visibility = View.GONE
        }
        col.addView(hint)
        previews = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), 0, ctx.dpi(16), 0)
        }
        col.addView(previews)

        dialog.setContentView(scroll)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = ctx.dpi(760)
        dialog.show()

        (preselected ?: books.firstOrNull())?.let { generateFor(it) }
    }

    private fun coverTile(b: BookEntry): View {
        val w = ctx.dpi(74)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(4), ctx.dpi(4), ctx.dpi(4), ctx.dpi(4))
        }
        val card = CardView(ctx).apply {
            radius = ctx.dp(5f)
            cardElevation = ctx.dp(3f)
            useCompatPadding = true
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                generateFor(b)
            }
        }
        card.addView(ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(BookThemes.loadCover(b.coverPath))
            contentDescription = b.title
        }, ViewGroup.LayoutParams(w, w * 3 / 2))
        box.addView(card)
        return box
    }

    private fun generateFor(b: BookEntry) {
        val cover = BookThemes.loadCover(b.coverPath) ?: return
        val themes = BookThemes.generate(cover, b.title)
        hint.visibility = View.VISIBLE
        hint.text = "Temas de «${cleanBookTitle(b.title)}»"
        previews.removeAllViews()
        themes.forEach { t -> previews.addView(preview(t)) }
    }

    private fun preview(t: BookTheme): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(t.paper, ctx.dp(14f), ctx.color(R.color.separator), 1)
            setPadding(ctx.dpi(16), ctx.dpi(14), ctx.dpi(16), ctx.dpi(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = ctx.dpi(10) }
        }
        card.addView(TextView(ctx).apply {
            text = t.name.substringAfterLast("· ").ifEmpty { t.name }
            setTextColor(t.accent.takeIf { ColorUtils.calculateContrast(it, t.paper) >= 3.0 } ?: t.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.06f
        })
        card.addView(TextView(ctx).apply {
            text = "Era el mejor de los tiempos, era el peor de los tiempos; la edad de la sabiduría y también de la locura."
            setTextColor(t.text)
            typeface = Typeface.SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setLineSpacing(0f, 1.2f)
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(10))
        })
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(button("Guardar", t.text, ColorUtils.setAlphaComponent(t.text, 0x22)) {
            BookThemes.add(ctx, t)
            android.widget.Toast.makeText(ctx, "Tema guardado en «Temas de tu biblioteca»", android.widget.Toast.LENGTH_SHORT).show()
        })
        actions.addView(button("Usar ahora", t.paper, t.text) {
            BookThemes.add(ctx, t)
            BookThemes.apply(prefs, t)
            dialog.dismiss()
            onApplied()
        })
        card.addView(actions)
        return card
    }

    private fun button(label: String, fg: Int, bg: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(fg)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = roundRect(bg, ctx.dp(16f))
        setPadding(ctx.dpi(14), ctx.dpi(7), ctx.dpi(14), ctx.dpi(7))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = ctx.dpi(8) }
        setOnClickListener { onClick() }
    }
}
