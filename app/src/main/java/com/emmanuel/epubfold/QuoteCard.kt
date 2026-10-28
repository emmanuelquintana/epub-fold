package com.emmanuel.epubfold

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

/** Fondos de la imagen: la portada desenfocada, su color o una paleta fija. */
enum class CardBg(val label: String, val paper: Int, val ink: Int) {
    PORTADA("Portada", 0, 0xFFFFFFFF.toInt()),
    COLOR("Color del libro", 0, 0xFFFFFFFF.toInt()),
    PAPEL("Papel", 0xFFF2E9D8.toInt(), 0xFF2B2118.toInt()),
    NOCHE("Noche", 0xFF131315.toInt(), 0xFFEDE6DA.toInt()),
    OCEANO("Océano", 0xFF1D3550.toInt(), 0xFFF2F0EA.toInt()),
    SALVIA("Salvia", 0xFF55705C.toInt(), 0xFFF5F2E9.toInt()),
    VINO("Vino", 0xFF5A1E2C.toInt(), 0xFFF7EDE6.toInt()),
    LAVANDA("Lavanda", 0xFFDDD6EA.toInt(), 0xFF29233D.toInt()),
}

/** Historia (9:16, Instagram y WhatsApp) o publicación (4:5). */
enum class CardFormat(val label: String, val w: Int, val h: Int) {
    HISTORIA("Historia", 1080, 1920),
    PUBLICACION("Publicación", 1080, 1350),
}

enum class CardFont(val label: String) { LITERATA("Literata"), CLASICA("Clásica"), MODERNA("Moderna") }

/**
 * Compartir un subrayado como imagen: la frase sobre un fondo a elegir, con la
 * portada en pequeño en una esquina, lista para historias de Instagram o WhatsApp.
 */
object QuoteCard {

    private const val PREFS = "quote_card"

    fun share(activity: Activity, quote: String, note: String, book: BookEntry) {
        QuoteCardSheet(activity, quote, note, book).show()
    }

    // ------------------------------------------------------------------ dibujo

    class Style(val bg: CardBg, val format: CardFormat, val font: CardFont, val withNote: Boolean)

    fun loadStyle(context: Context): Style {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun <E : Enum<E>> pick(values: Array<E>, key: String, def: E) =
            values.firstOrNull { it.name == sp.getString(key, null) } ?: def
        return Style(
            pick(CardBg.entries.toTypedArray(), "bg", CardBg.PORTADA),
            pick(CardFormat.entries.toTypedArray(), "format", CardFormat.HISTORIA),
            pick(CardFont.entries.toTypedArray(), "font", CardFont.LITERATA),
            sp.getBoolean("note", true),
        )
    }

    fun saveStyle(context: Context, s: Style) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("bg", s.bg.name).putString("format", s.format.name)
            .putString("font", s.font.name).putBoolean("note", s.withNote).apply()
    }

    /** Color medio de la portada, oscurecido para que el texto claro se lea bien. */
    fun coverColor(cover: Bitmap?): Int {
        if (cover == null) return 0xFF2A2A2E.toInt()
        val s = Bitmap.createScaledBitmap(cover, 8, 8, true)
        var r = 0L; var g = 0L; var b = 0L
        for (x in 0 until 8) for (y in 0 until 8) {
            val c = s.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c)
        }
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(Color.rgb((r / 64).toInt(), (g / 64).toInt(), (b / 64).toInt()), hsl)
        hsl[1] = (hsl[1] * 1.1f).coerceAtMost(0.7f)
        hsl[2] = hsl[2].coerceIn(0.14f, 0.24f)
        return ColorUtils.HSLToColor(hsl)
    }

    /** Desenfoque barato: reducir mucho y volver a ampliar con filtrado, en dos pasos. */
    private fun blurred(cover: Bitmap, w: Int, h: Int): Bitmap {
        // Recorte tipo «center crop» a la proporción de la imagen final
        val target = w.toFloat() / h
        val srcRatio = cover.width.toFloat() / cover.height
        val (cw, ch) = if (srcRatio > target) (cover.height * target).toInt() to cover.height
        else cover.width to (cover.width / target).toInt()
        val crop = Bitmap.createBitmap(cover, (cover.width - cw) / 2, (cover.height - ch) / 2, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
        val tiny = Bitmap.createScaledBitmap(crop, 18, (18 / target).toInt().coerceAtLeast(1), true)
        val mid = Bitmap.createScaledBitmap(tiny, 120, (120 / target).toInt(), true)
        return Bitmap.createScaledBitmap(mid, w, h, true)
    }

    fun render(context: Context, quote: String, note: String, book: BookEntry, cover: Bitmap?, s: Style): Bitmap {
        val w = s.format.w
        val h = s.format.h
        val story = s.format == CardFormat.HISTORIA
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // --- Fondo
        when (s.bg) {
            CardBg.PORTADA -> {
                if (cover != null) {
                    c.drawBitmap(blurred(cover, w, h), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                    c.drawColor(Color.argb(120, 0, 0, 0))
                } else c.drawColor(coverColor(null))
            }
            CardBg.COLOR -> c.drawColor(coverColor(cover))
            else -> c.drawColor(s.bg.paper)
        }
        val ink = s.bg.ink
        val soft = ColorUtils.setAlphaComponent(ink, 170)

        // --- Zonas: en historias se respeta la franja que tapan los controles de Instagram
        val pad = 112f
        val footH = 200f
        val footBottom = if (story) h - 360f else h - 120f
        val footTop = footBottom - footH
        val zoneTop = if (story) 330f else 150f
        val zoneBottom = footTop - 90f

        val face = when (s.font) {
            CardFont.LITERATA -> ReaderFont.LITERATA.typeface(context)
            CardFont.CLASICA -> Typeface.SERIF
            CardFont.MODERNA -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val text = quote.trim().replace(Regex("\\s+"), " ")
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = face }
        val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = soft
            textSize = 36f
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
        }
        val noteText = if (s.withNote) note.trim() else ""
        val noteLayout = if (noteText.isEmpty()) null else
            StaticLayout.Builder.obtain(noteText, 0, noteText.length, np, (w - 2 * pad).toInt())
                .setLineSpacing(0f, 1.2f).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build()
        val noteSpace = noteLayout?.let { it.height + 48f } ?: 0f
        val markH = 120f
        val maxTextH = (zoneBottom - zoneTop - noteSpace - markH).toInt()

        // La letra se reduce hasta que la frase cabe; si ni así, se recorta
        val width = (w - 2 * pad).toInt()
        var size = if (story) 82f else 72f
        fun build(maxLines: Int = Int.MAX_VALUE) = StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
            .setLineSpacing(0f, 1.22f).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = build()
            if (layout.height <= maxTextH || size <= 34f) break
            size -= 3f
        }
        if (layout.height > maxTextH) {
            layout = build((maxTextH / (layout.height / layout.lineCount.toFloat())).toInt().coerceAtLeast(1))
        }

        // Bloque centrado en su zona: comilla, frase y nota
        val blockH = markH + layout.height + noteSpace
        var y = zoneTop + ((zoneBottom - zoneTop) - blockH) / 2f
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(ink, 110)
            typeface = Typeface.create(face, Typeface.BOLD)
            textSize = 200f
        }
        c.drawText("“", pad - 10f, y + 150f, mark)
        y += markH
        c.save(); c.translate(pad, y); layout.draw(c); c.restore()
        y += layout.height
        noteLayout?.let {
            y += 48f
            c.save(); c.translate(pad, y); it.draw(c); c.restore()
        }

        // --- Pie: portada pequeña en la esquina, título y autor
        var textLeft = pad
        if (cover != null) {
            val ch = footH
            val cw = ch * cover.width / cover.height.coerceAtLeast(1)
            val r = RectF(pad, footTop, pad + cw, footTop + ch)
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 0, 0, 0)
                setShadowLayer(18f, 0f, 8f, Color.argb(90, 0, 0, 0))
            }
            c.drawRoundRect(r, 10f, 10f, shadow)
            c.save()
            c.clipPath(Path().apply { addRoundRect(r, 10f, 10f, Path.Direction.CW) })
            c.drawBitmap(cover, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
            textLeft = r.right + 34f
        }
        val tw = (w - pad - textLeft).toInt()
        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 40f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val tl = StaticLayout.Builder.obtain(book.title, 0, book.title.length, titleP, tw)
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
        val authorP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = 33f }
        val authorH = if (book.author.isNotEmpty()) 50f else 0f
        val ty = footTop + (footH - tl.height - authorH) / 2f
        c.save(); c.translate(textLeft, ty); tl.draw(c); c.restore()
        if (book.author.isNotEmpty()) {
            c.drawText(TextUtils.ellipsize(book.author, authorP, tw.toFloat(), TextUtils.TruncateAt.END).toString(),
                textLeft, ty + tl.height + 42f, authorP)
        }
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(ink, 120)
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("EpubFold", w - pad, footBottom + 64f, brand)
        return bmp
    }

    // ------------------------------------------------------------------ compartir

    fun cacheFile(context: Context, bmp: Bitmap): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "cita-${System.currentTimeMillis() % 100000}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    fun shareIntent(uri: Uri, caption: String, pkg: String?): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (pkg != null) setPackage(pkg)
    }

    fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Guarda la imagen en Imágenes/EpubFold. */
    fun saveToGallery(context: Context, bmp: Bitmap): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "EpubFold-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EpubFold")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)
}

/** Hoja para elegir fondo, formato y letra, con vista previa, y compartir. */
private class QuoteCardSheet(
    private val activity: Activity,
    private val quote: String,
    private val note: String,
    private val book: BookEntry,
) {
    private val ctx = activity
    private val dialog = BottomSheetDialog(activity)
    private var style = QuoteCard.loadStyle(activity)
    private val cover = book.coverPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    private var current: Bitmap? = null
    private val preview = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(14f))
        }
        clipToOutline = true
        elevation = ctx.dp(6f)
        contentDescription = "Vista previa de la imagen"
    }
    private val swatches = ArrayList<Pair<CardBg, View>>()

    fun show() {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16), ctx.dpi(6), ctx.dpi(16), ctx.dpi(28))
        }
        col.addView(TextView(ctx).apply {
            text = "Compartir frase"
            setTextAppearance(R.style.Text_Headline)
            gravity = Gravity.CENTER
            setPadding(0, ctx.dpi(6), 0, ctx.dpi(14))
        })
        col.addView(preview, previewParams())

        // --- Formato
        val seg = SegmentedControl(ctx)
        seg.setOptions(CardFormat.entries.map { it.label }, style.format.ordinal)
        seg.onSelected = { i ->
            style = QuoteCard.Style(style.bg, CardFormat.entries[i], style.font, style.withNote)
            preview.layoutParams = previewParams()
            redraw()
        }
        col.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = ctx.dpi(18) })

        // --- Fondos
        col.addView(caption("Fondo"))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        CardBg.entries.forEach { bg -> row.addView(swatch(bg)) }
        col.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        })

        // --- Letra
        col.addView(caption("Letra"))
        val fontSeg = SegmentedControl(ctx)
        fontSeg.setOptions(CardFont.entries.map { it.label }, style.font.ordinal)
        fontSeg.onSelected = { i ->
            style = QuoteCard.Style(style.bg, style.format, CardFont.entries[i], style.withNote)
            redraw()
        }
        col.addView(fontSeg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (note.isNotBlank()) {
            col.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dpi(4), ctx.dpi(14), 0, 0)
                addView(TextView(ctx).apply {
                    text = "Incluir mi nota"
                    setTextAppearance(R.style.Text_Body)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(MaterialSwitch(ctx).apply {
                    isChecked = style.withNote
                    trackTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(ctx.color(R.color.accent), ctx.color(R.color.seg_bg)))
                    thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    trackDecorationTintList = android.content.res.ColorStateList.valueOf(0)
                    setOnCheckedChangeListener { _, on ->
                        style = QuoteCard.Style(style.bg, style.format, style.font, on)
                        redraw()
                    }
                })
            })
        }

        // --- Acciones
        col.addView(View(ctx), LinearLayout.LayoutParams(1, ctx.dpi(22)))
        val caption = "“${quote.trim()}” — ${book.title}"
        val targets = ArrayList<Pair<String, () -> Unit>>()
        if (QuoteCard.installed(ctx, "com.instagram.android")) targets += "Instagram" to { send("com.instagram.android", caption) }
        val wa = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { QuoteCard.installed(ctx, it) }
        if (wa != null) targets += "WhatsApp" to { send(wa, caption) }
        if (Build.VERSION.SDK_INT >= 29) targets += "Guardar" to {
            val ok = current?.let { QuoteCard.saveToGallery(ctx, it) } == true
            Toast.makeText(ctx, if (ok) "Guardada en Imágenes › EpubFold" else "No se pudo guardar", Toast.LENGTH_SHORT).show()
        }
        targets += "Más…" to { send(null, caption) }
        targets.chunked(2).forEach { pair ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (label, action) ->
                line.addView(actionButton(label, primary = label == "Instagram" || label == "WhatsApp", action),
                    LinearLayout.LayoutParams(0, ctx.dpi(48), 1f).apply {
                        if (i == 0 && pair.size > 1) marginEnd = ctx.dpi(10)
                    })
            }
            col.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = ctx.dpi(10) })
        }

        redraw()
        dialog.setContentView(NestedScrollView(ctx).apply { addView(col) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = ctx.dpi(680)
        dialog.show()
    }

    /** Alto de la vista previa según el formato, sin pasar de media pantalla. */
    private fun previewParams(): LinearLayout.LayoutParams {
        val maxH = minOf((ctx.resources.displayMetrics.heightPixels * 0.5f).toInt(), ctx.dpi(460))
        val h = if (style.format == CardFormat.HISTORIA) maxH else (maxH * 0.82f).toInt()
        val w = h * style.format.w / style.format.h
        return LinearLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun redraw() {
        QuoteCard.saveStyle(ctx, style)
        val bmp = QuoteCard.render(ctx, quote, note, book, cover, style)
        current = bmp
        preview.setImageBitmap(bmp)
        refreshSwatches()
    }

    private fun send(pkg: String?, caption: String) {
        val bmp = current ?: return
        val uri = QuoteCard.cacheFile(ctx, bmp)
        val intent = QuoteCard.shareIntent(uri, caption, pkg)
        runCatching {
            ctx.startActivity(if (pkg == null) Intent.createChooser(intent, "Compartir imagen") else intent)
        }.onFailure { ctx.startActivity(Intent.createChooser(QuoteCard.shareIntent(uri, caption, null), "Compartir imagen")) }
    }

    private fun swatch(bg: CardBg): View {
        val size = ctx.dpi(52)
        val inner = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            when (bg) {
                CardBg.PORTADA -> {
                    background = roundRect(QuoteCard.coverColor(cover), ctx.dp(22f))
                    cover?.let { setImageBitmap(it) }
                }
                CardBg.COLOR -> background = roundRect(QuoteCard.coverColor(cover), ctx.dp(22f))
                else -> background = roundRect(bg.paper, ctx.dp(22f), ctx.color(R.color.separator), if (isDarkColor(bg.paper)) 0 else 1)
            }
        }
        val box = android.widget.FrameLayout(ctx).apply {
            setPadding(ctx.dpi(4), ctx.dpi(4), ctx.dpi(4), ctx.dpi(4))
            contentDescription = bg.label
            addView(inner, android.widget.FrameLayout.LayoutParams(ctx.dpi(44), ctx.dpi(44)))
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                style = QuoteCard.Style(bg, style.format, style.font, style.withNote)
                redraw()
            }
        }
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(box, LinearLayout.LayoutParams(size, size))
            addView(TextView(ctx).apply {
                text = bg.label
                setTextAppearance(R.style.Text_Footnote)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(0, ctx.dpi(2), 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(ctx.dpi(66), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        swatches += bg to box
        return holder
    }

    private fun refreshSwatches() {
        swatches.forEach { (bg, box) ->
            val sel = bg == style.bg
            box.background = if (sel) roundRect(0, ctx.dp(26f), ctx.color(R.color.accent), ctx.dpi(2)) else null
            box.contentDescription = bg.label + if (sel) ", seleccionado" else ""
        }
    }

    private fun caption(text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(R.style.Text_Caption)
        setPadding(ctx.dpi(4), ctx.dpi(20), 0, ctx.dpi(8))
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else ctx.color(R.color.accent))
        background = roundRect(if (primary) ctx.color(R.color.accent) else ctx.color(R.color.fill), ctx.dp(24f))
        foreground = TypedValue().let { tv ->
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            ctx.getDrawable(tv.resourceId)
        }
        // Respuesta al pulsar: se encoge un poco
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
        setOnClickListener { onClick() }
    }
}
