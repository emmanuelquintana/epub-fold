package com.emmanuel.epubfold

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Ajustes de imagen de cómics y PDF: contraste, brillo, blanco y negro y recorte de márgenes. */
class ComicImageSettings(private val sp: android.content.SharedPreferences) {
    var contrast: Float
        get() = sp.getFloat("comicContrast", 1f)
        set(v) = sp.edit().putFloat("comicContrast", v).apply()
    var brightness: Float
        get() = sp.getFloat("comicBrightness", 0f)
        set(v) = sp.edit().putFloat("comicBrightness", v).apply()
    var grayscale: Boolean
        get() = sp.getBoolean("comicGray", false)
        set(v) = sp.edit().putBoolean("comicGray", v).apply()
    var crop: Boolean
        get() = sp.getBoolean("comicCrop", false)
        set(v) = sp.edit().putBoolean("comicCrop", v).apply()

    /** 0 páginas, 1 viñeta a viñeta, 2 vertical (webtoon). */
    var mode: Int
        get() = sp.getInt("comicMode", 0)
        set(v) = sp.edit().putInt("comicMode", v).apply()

    val isDefault get() = contrast == 1f && brightness == 0f && !grayscale

    /** Filtro de color equivalente (null si no hay ajustes). */
    fun filter(): ColorFilter? {
        if (isDefault) return null
        val c = contrast
        val t = brightness + 128f * (1f - c)
        val cm = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ))
        if (grayscale) cm.preConcat(ColorMatrix().apply { setSaturation(0f) })
        return ColorMatrixColorFilter(cm)
    }
}

/**
 * Lectura vertical continua (webtoon / manhwa): las páginas una debajo de otra al
 * ancho de la pantalla, sin separación.
 */
@SuppressLint("NotifyDataSetChanged", "ViewConstructor")
class WebtoonView(context: Context) : RecyclerView(context) {

    var pageCount = 0
    var aspect: (Int) -> Float = { 0.7f }
    /** Página ya dibujada (de la caché) o null. */
    var cached: (Int) -> Bitmap? = { null }
    /** Pide dibujar la página al ancho dado; el resultado llega por el callback (hilo principal). */
    var render: (page: Int, width: Int, done: (Bitmap?) -> Unit) -> Unit = { _, _, done -> done(null) }
    var onPageVisible: ((Int) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null
    var imageFilter: ColorFilter? = null
        set(value) {
            field = value
            adapter?.notifyDataSetChanged()
        }

    private val lm = LinearLayoutManager(context)
    private var lastReported = -1

    init {
        layoutManager = lm
        adapter = PagesAdapter()
        setItemViewCacheSize(2)
        setBackgroundColor(0xFF151517.toInt())
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // La página que ocupa el centro de la pantalla es la que se está leyendo
                val center = findChildViewUnder(width / 2f, height / 2f) ?: return
                val p = getChildAdapterPosition(center)
                if (p >= 0 && p != lastReported) {
                    lastReported = p
                    onPageVisible?.invoke(p)
                }
            }
        })
        val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val third = height / 3f
                when {
                    e.y < third -> smoothScrollBy(0, -(height * 0.85f).toInt())
                    e.y > height - third -> smoothScrollBy(0, (height * 0.85f).toInt())
                    else -> onCenterTap?.invoke()
                }
                return true
            }
        })
        addOnItemTouchListener(object : SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                taps.onTouchEvent(e)
                return false
            }
        })
    }

    fun reload() {
        adapter?.notifyDataSetChanged()
    }

    fun scrollToPage(p: Int) {
        lm.scrollToPositionWithOffset(p.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), 0)
    }

    /** Avanza o retrocede casi una pantalla (teclas de volumen, manos libres). */
    fun step(forward: Boolean) = smoothScrollBy(0, ((if (forward) 1 else -1) * height * 0.85f).toInt())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) post { reload() }
    }

    private inner class Holder(val image: ImageView) : ViewHolder(image) {
        var page = -1
    }

    private inner class PagesAdapter : Adapter<Holder>() {
        override fun getItemCount() = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(0xFF2A2A2E.toInt())
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
        })

        override fun onBindViewHolder(h: Holder, position: Int) {
            val w = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val a = aspect(position).coerceAtLeast(0.05f)
            h.image.layoutParams = h.image.layoutParams.apply { height = (w / a).toInt().coerceAtLeast(1) }
            h.image.colorFilter = imageFilter
            h.page = position
            val bmp = cached(position)
            if (bmp != null) {
                h.image.setImageBitmap(bmp)
            } else {
                h.image.setImageDrawable(null)
                render(position, w) { b -> if (h.page == position && b != null) h.image.setImageBitmap(b) }
            }
        }

        override fun onViewRecycled(h: Holder) {
            h.page = -1
            h.image.setImageDrawable(null)
        }
    }
}
