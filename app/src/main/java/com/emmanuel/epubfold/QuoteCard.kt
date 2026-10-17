package com.emmanuel.epubfold

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import java.io.File

/**
 * Tarjetas para compartir: un subrayado convertido en imagen 1080×1350 (formato
 * de Instagram) con la portada, la cita en tipografía serif y el libro.
 */
object QuoteCard {

    private const val W = 1080
    private const val H = 1350

    fun share(activity: Activity, quote: String, note: String, book: BookEntry, accent: Int = 0xFFFF9500.toInt()) {
        val bmp = render(activity, quote, note, book, accent)
        val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "cita-${System.currentTimeMillis() % 100000}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "“${quote.trim()}” — ${book.title}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Compartir tarjeta"))
    }

    /** Color medio de la portada, oscurecido para que el texto blanco se lea bien. */
    private fun baseColor(cover: Bitmap?, fallback: Int): Int {
        if (cover == null) return ColorUtils.blendARGB(fallback, Color.BLACK, 0.55f)
        val s = Bitmap.createScaledBitmap(cover, 8, 8, true)
        var r = 0L; var g = 0L; var b = 0L
        for (x in 0 until 8) for (y in 0 until 8) {
            val c = s.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c)
        }
        val avg = Color.rgb((r / 64).toInt(), (g / 64).toInt(), (b / 64).toInt())
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(avg, hsl)
        hsl[1] = (hsl[1] * 1.1f).coerceAtMost(0.75f)
        hsl[2] = hsl[2].coerceIn(0.14f, 0.26f)
        return ColorUtils.HSLToColor(hsl)
    }

    fun render(activity: Activity, quote: String, note: String, book: BookEntry, accent: Int): Bitmap {
        val cover = book.coverPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        val base = baseColor(cover, accent)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(),
                ColorUtils.blendARGB(base, Color.WHITE, 0.08f), ColorUtils.blendARGB(base, Color.BLACK, 0.35f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        val pad = 96f
        // Comilla grande decorativa
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(accent, 230)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = 220f
        }
        c.drawText("“", pad - 12f, 250f, mark)

        // Cita: la letra se reduce hasta que cabe
        val text = quote.trim().replace(Regex("\\s+"), " ")
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val maxTextH = if (note.isBlank()) 700 else 560
        var size = 68f
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, (W - 2 * pad).toInt())
                .setLineSpacing(0f, 1.18f)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            if (layout.height <= maxTextH || size <= 30f) break
            size -= 3f
        }
        // Si ni así cabe, se recorta con puntos suspensivos
        if (layout.height > maxTextH) {
            val lines = (maxTextH / (layout.height / layout.lineCount.toFloat())).toInt().coerceAtLeast(1)
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, (W - 2 * pad).toInt())
                .setLineSpacing(0f, 1.18f).setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        }
        c.save()
        c.translate(pad, 290f)
        layout.draw(c)
        c.restore()
        var y = 290f + layout.height + 40f

        if (note.isNotBlank()) {
            val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(Color.WHITE, 200)
                textSize = 34f
                typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            }
            val nl = StaticLayout.Builder.obtain(note.trim(), 0, note.trim().length, np, (W - 2 * pad).toInt())
                .setMaxLines(4).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            c.save()
            c.translate(pad, y)
            nl.draw(c)
            c.restore()
            y += nl.height + 30f
        }

        // Pie: portada, título y autor
        val footTop = H - 300f
        val line = Paint().apply { color = ColorUtils.setAlphaComponent(Color.WHITE, 60); strokeWidth = 2f }
        c.drawLine(pad, footTop - 40f, W - pad, footTop - 40f, line)
        var textLeft = pad
        if (cover != null) {
            val ch = 200f
            val cw = ch * cover.width / cover.height.coerceAtLeast(1)
            val r = RectF(pad, footTop, pad + cw, footTop + ch)
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
            c.drawRoundRect(RectF(r.left + 8, r.top + 10, r.right + 8, r.bottom + 10), 10f, 10f, shadow)
            c.save()
            val clip = android.graphics.Path().apply { addRoundRect(r, 10f, 10f, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            c.drawBitmap(cover, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
            textLeft = r.right + 36f
        }
        val tw = W - pad - textLeft
        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val tl = StaticLayout.Builder.obtain(book.title, 0, book.title.length, titleP, tw.toInt())
            .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save()
        c.translate(textLeft, footTop + 30f)
        tl.draw(c)
        c.restore()
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 190)
            textSize = 34f
        }
        if (book.author.isNotEmpty()) {
            c.drawText(android.text.TextUtils.ellipsize(book.author, TextPaint(sub), tw, android.text.TextUtils.TruncateAt.END).toString(),
                textLeft, footTop + 30f + tl.height + 46f, sub)
        }
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(accent, 220)
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("EPUBFOLD", W - pad, H - 56f, brand)
        return bmp
    }
}
