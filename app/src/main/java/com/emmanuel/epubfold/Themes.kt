package com.emmanuel.epubfold

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
    SILENCIO(0xFF4A4A4F.toInt(), 0xFFD9D9DE.toInt(), 0xFFA0A0A6.toInt(), true, "Silencio"),
    NOCHE(0xFF1C1C1E.toInt(), 0xFFE3E3E8.toInt(), 0xFF8E8E93.toInt(), true, "Noche"),
    NEGRO(0xFF000000.toInt(), 0xFFC7C7CC.toInt(), 0xFF6C6C70.toInt(), true, "Negro OLED"),
    AMBAR(0xFF0A0806.toInt(), 0xFFF2B05E.toInt(), 0xFF8A6A40.toInt(), true, "Ámbar");
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
