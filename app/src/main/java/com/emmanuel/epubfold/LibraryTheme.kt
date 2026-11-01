package com.emmanuel.epubfold

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import org.json.JSONObject
import kotlin.math.abs

/**
 * Tema de la estantería creado a partir de la portada de un libro o cómic:
 * fondo, tarjetas y color de acento de la biblioteca y del resto de pantallas.
 */
data class LibraryTheme(
    val name: String,
    val source: String,
    val dark: Boolean,
    val background: Int,
    val card: Int,
    val group: Int,
    val accent: Int,
) {
    fun toJson(): String = JSONObject()
        .put("name", name).put("source", source).put("dark", dark)
        .put("background", background).put("card", card).put("group", group).put("accent", accent)
        .toString()

    /** El acento predefinido más parecido, para los controles que usan el color del tema de Android. */
    val nearestAccent: AppAccent
        get() {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(accent, hsl)
            if (hsl[1] < 0.18f) return AppAccent.GRAFITO
            val hues = mapOf(
                AppAccent.ROJO to 4f, AppAccent.NARANJA to 32f, AppAccent.VERDE to 130f,
                AppAccent.TURQUESA to 180f, AppAccent.AZUL to 212f, AppAccent.MORADO to 280f, AppAccent.ROSA to 335f,
            )
            return hues.minBy { (_, h) -> abs(((hsl[0] - h + 540f) % 360f) - 180f) }.key
        }

    companion object {
        private const val KEY = "libraryTheme"

        @Volatile private var loaded = false
        @Volatile private var cached: LibraryTheme? = null

        private fun sp(c: Context) = c.applicationContext.getSharedPreferences("reader", Context.MODE_PRIVATE)

        fun current(context: Context): LibraryTheme? {
            if (!loaded) {
                cached = sp(context).getString(KEY, null)?.let { s ->
                    runCatching {
                        val o = JSONObject(s)
                        LibraryTheme(o.optString("name"), o.optString("source"), o.optBoolean("dark"),
                            o.getInt("background"), o.getInt("card"), o.getInt("group"), o.getInt("accent"))
                    }.getOrNull()
                }
                loaded = true
            }
            return cached
        }

        fun set(context: Context, t: LibraryTheme?) {
            val e = sp(context).edit()
            if (t == null) e.remove(KEY) else e.putString(KEY, t.toJson())
            e.apply()
            cached = t
            loaded = true
        }

        /**
         * Color que sustituye al recurso [id] si hay un tema de estantería activo y la pantalla
         * está en el mismo modo (claro u oscuro) para el que se creó.
         */
        fun override(context: Context, id: Int): Int? {
            val t = current(context) ?: return null
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (night != t.dark) return null
            return when (id) {
                R.color.accent -> t.accent
                R.color.bg_grouped -> t.background
                R.color.card -> t.card
                R.color.group_bg -> t.group
                R.color.sheet_bg -> if (t.dark) t.card else t.background
                else -> null
            }
        }

        private fun hsl(color: Int, maxSat: Float, light: Float): Int {
            val h = FloatArray(3)
            ColorUtils.colorToHSL(color, h)
            h[1] = h[1].coerceAtMost(maxSat)
            h[2] = light
            return ColorUtils.HSLToColor(h) or 0xFF000000.toInt()
        }

        /** Mueve la luminosidad del acento hasta que se lea bien sobre [bg]. */
        private fun readableAccent(color: Int, bg: Int, dark: Boolean): Int {
            val h = FloatArray(3)
            ColorUtils.colorToHSL(color, h)
            h[1] = h[1].coerceAtLeast(0.45f)
            var c = ColorUtils.HSLToColor(h) or 0xFF000000.toInt()
            var guard = 0
            while (ColorUtils.calculateContrast(c, bg or 0xFF000000.toInt()) < 3.2 && guard < 40) {
                h[2] = (h[2] + if (dark) 0.02f else -0.02f).coerceIn(0f, 1f)
                c = ColorUtils.HSLToColor(h) or 0xFF000000.toInt()
                guard++
            }
            return c
        }

        /** Variantes oscura y clara a partir de la portada. */
        fun generate(cover: Bitmap, title: String): List<LibraryTheme> {
            val p = Palette.from(cover).maximumColorCount(24).generate()
            val dominant = p.getDominantColor(0xFF6B5B4A.toInt())
            val vibrant = p.getVibrantColor(p.getLightVibrantColor(p.getDarkVibrantColor(dominant)))
            val darkBase = p.getDarkMutedColor(p.getDarkVibrantColor(dominant))
            val lightBase = p.getLightMutedColor(p.getLightVibrantColor(dominant))
            val name = cleanBookTitle(title)

            val dBg = hsl(darkBase, 0.40f, 0.06f)
            val dCard = hsl(darkBase, 0.35f, 0.12f)
            val dGroup = hsl(darkBase, 0.30f, 0.16f)
            val dAccent = readableAccent(vibrant, dCard, dark = true)

            val lBg = hsl(lightBase, 0.35f, 0.94f)
            val lCard = hsl(lightBase, 0.30f, 0.985f)
            val lGroup = Color.WHITE
            val lAccent = readableAccent(vibrant, lCard, dark = false)

            return listOf(
                LibraryTheme("$name · Oscuro", title, true, dBg, dCard, dGroup, dAccent),
                LibraryTheme("$name · Claro", title, false, lBg, lCard, lGroup, lAccent),
            )
        }
    }
}
