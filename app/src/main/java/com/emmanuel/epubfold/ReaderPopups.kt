package com.emmanuel.epubfold

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nota al pie en una ventana pequeña, con los colores del tema de lectura. */
object FootnoteSheet {
    fun show(activity: Activity, mark: String, text: String, colors: ThemeColors, onGo: () -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.paper)
            setPadding(activity.dpi(24), activity.dpi(18), activity.dpi(24), activity.dpi(20))
        }
        col.addView(TextView(activity).apply {
            this.text = if (mark.isNotEmpty() && mark.length <= 6) "Nota $mark" else "Nota"
            setTextColor(colors.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.06f
        })
        col.addView(NestedScrollView(activity).apply {
            addView(TextView(activity).apply {
                this.text = text
                setTextColor(colors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.25f)
                setTextIsSelectable(true)
                setPadding(0, activity.dpi(8), 0, activity.dpi(14))
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        })
        col.addView(TextView(activity).apply {
            this.text = "Ir a la nota en el libro"
            setTextColor(colors.text)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundRect(ColorUtils.blendARGB(colors.paper, colors.text, 0.08f), activity.dp(14f))
            setPadding(activity.dpi(12), activity.dpi(10), activity.dpi(12), activity.dpi(10))
            setOnClickListener {
                dialog.dismiss()
                onGo()
            }
        })
        dialog.setContentView(col)
        dialog.behavior.maxHeight = (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.maxWidth = activity.dpi(640)
        dialog.show()
    }
}

/** Imagen de un EPUB a pantalla completa con zoom (pellizcar o doble toque); un toque cierra. */
object ImageViewer {
    fun show(activity: Activity, book: EpubBook, path: String) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = SpreadView(activity).apply { bgColor = 0xFF000000.toInt() }
        val frame = FrameLayout(activity)
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(TextView(activity).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            background = roundRect(0x55000000, activity.dp(22f))
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(activity.dpi(44), activity.dpi(44), Gravity.TOP or Gravity.END).apply {
            setMargins(0, activity.dpi(36), activity.dpi(16), 0)
        })
        view.onCenterTap = { dialog.dismiss() }
        dialog.setContentView(frame)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
        (activity as? LifecycleOwner)?.lifecycleScope?.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = book.readBytes(path) ?: return@runCatching null
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= 2400 || o.outHeight / (sample * 2) >= 2400) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
            if (bmp == null) {
                dialog.dismiss()
                android.widget.Toast.makeText(activity, "No se pudo abrir la imagen", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                view.setSlot(0, listOf(bmp.width.toFloat() / bmp.height), listOf(bmp))
            }
        }
    }
}
