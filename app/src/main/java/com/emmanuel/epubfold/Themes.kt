package com.emmanuel.epubfold

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import java.util.Calendar

/** Colores efectivos con los que se dibuja el libro. */
data class ThemeColors(
    val paper: Int,
    val text: Int,
    val secondary: Int,
    val isDark: Boolean,
    val texture: Boolean,
)

/** Temas de lectura inspirados en Apple Books. */
enum class ReaderTheme(
    val paper: Int,
    val text: Int,
    val secondary: Int,
    val isDark: Boolean,
    val label: String,
) {
    ORIGINAL(0xFFFFFFFF.toInt(), 0xFF1C1C1E.toInt(), 0xFF8E8E93.toInt(), false, "Original"),
    CALMA(0xFFF5EDDC.toInt(), 0xFF4B3A28.toInt(), 0xFF9C8B74.toInt(), false, "Calma"),
    PAPEL(0xFFEDEBE6.toInt(), 0xFF2B2B2D.toInt(), 0xFF8C8A85.toInt(), false, "Papel"),
    PERGAMINO(0xFFEFE2C4.toInt(), 0xFF3E2E1C.toInt(), 0xFF8F7A58.toInt(), false, "Pergamino"),
    SILENCIO(0xFF4A4A4F.toInt(), 0xFFD9D9DE.toInt(), 0xFFA0A0A6.toInt(), true, "Silencio"),
    NOCHE(0xFF1C1C1E.toInt(), 0xFFE3E3E8.toInt(), 0xFF8E8E93.toInt(), true, "Noche"),
    NEGRO(0xFF000000.toInt(), 0xFFC7C7CC.toInt(), 0xFF6C6C70.toInt(), true, "Negro OLED"),
    AMBAR(0xFF0A0806.toInt(), 0xFFF2B05E.toInt(), 0xFF8A6A40.toInt(), true, "Ámbar"),
    PERSONAL(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF888888.toInt(), false, "Personal");
}

enum class PageAnimation(val label: String) {
    CURL("Curvatura"),
    SLIDE("Deslizar"),
    NONE("Ninguna");
}

enum class LibrarySort(val label: String) {
    RECENT("Recientes"),
    TITLE("Título"),
    AUTHOR("Autor");
}

enum class AutoTheme(val label: String) {
    OFF("No"),
    SYSTEM("Sistema"),
    SCHEDULE("Horario");
}

/** Tipografías disponibles. Las tres últimas van incluidas en la app (licencia OFL). */
enum class ReaderFont(val label: String, val res: Int) {
    SERIF("Serif", 0),
    SANS("Sans", 0),
    LITERATA("Literata", R.font.font_literata),
    ATKINSON("Atkinson", R.font.font_atkinson),
    OPENDYSLEXIC("OpenDyslexic", R.font.font_opendyslexic);

    fun typeface(context: Context): Typeface = when (this) {
        SERIF -> Typeface.SERIF
        SANS -> Typeface.SANS_SERIF
        else -> runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: Typeface.SERIF
    }
}

fun isDarkColor(c: Int) = ColorUtils.calculateLuminance(c) < 0.4

/** Colores reales de un tema (resuelve el tema Personal y la textura de papel). */
fun ReaderPrefs.colorsFor(t: ReaderTheme): ThemeColors {
    if (t == ReaderTheme.PERSONAL) {
        val paper = customPaper
        val text = customText
        return ThemeColors(paper, text, ColorUtils.blendARGB(text, paper, 0.45f), isDarkColor(paper), texture)
    }
    return ThemeColors(t.paper, t.text, t.secondary, t.isDark, texture || t == ReaderTheme.PERGAMINO)
}

fun ReaderPrefs.isDarkTheme(t: ReaderTheme) =
    if (t == ReaderTheme.PERSONAL) isDarkColor(customPaper) else t.isDark

/** ¿El sistema está en modo oscuro? (independiente del modo forzado por la app). */
fun systemIsDark(): Boolean =
    (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Tema que toca usar ahora según el modo automático. */
fun ReaderPrefs.effectiveTheme(): ReaderTheme = when (autoTheme) {
    AutoTheme.OFF -> theme
    AutoTheme.SYSTEM -> if (systemIsDark()) lastDark else lastLight
    AutoTheme.SCHEDULE -> {
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val s = nightStartMin
        val e = nightEndMin
        val night = if (s <= e) now in s until e else now >= s || now < e
        if (night) lastDark else lastLight
    }
}

fun minutesLabel(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** Colores de los subrayados (estilo iOS). */
val HIGHLIGHT_COLORS = intArrayOf(
    0xFFFFD60A.toInt(), // amarillo
    0xFF30D158.toInt(), // verde
    0xFF0A84FF.toInt(), // azul
    0xFFFF375F.toInt(), // rosa
    0xFFBF5AF2.toInt(), // morado
)
val HIGHLIGHT_NAMES = listOf("Amarillo", "Verde", "Azul", "Rosa", "Morado")
