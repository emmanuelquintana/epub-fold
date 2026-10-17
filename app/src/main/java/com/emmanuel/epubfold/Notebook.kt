package com.emmanuel.epubfold

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cuaderno de un libro: notas largas en texto (se sincronizan con la cuenta) y
 * una hoja para escribir o dibujar a mano con el S Pen (se guarda en el dispositivo).
 */
class NotebookView(
    context: Context,
    private val bookId: String,
    private val notes: Annotations,
) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val sketchFile = File(File(context.filesDir, "sketches").apply { mkdirs() }, "$bookId.json")
    private val tabs = SegmentedControl(context)
    private val editor = EditText(context)
    private val textBox = ScrollView(context)
    private val sketch = SketchView(context)
    private val sketchBox = ScrollView(context)
    private val sketchFrame = FrameLayout(context)
    private val clearButton = TextView(context)
    private var textColor = 0
    private var secondaryColor = 0

    init {
        orientation = VERTICAL
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dpi(16), 0, context.dpi(16), context.dpi(8))
        }
        tabs.setOptions(listOf("Texto", "A mano"), if (prefsMode() == 1) 1 else 0)
        tabs.onSelected = { setMode(it) }
        bar.addView(tabs, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clearButton.apply {
            text = "Borrar hoja"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(context.dpi(14), context.dpi(6), 0, context.dpi(6))
            setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle("¿Borrar todo lo dibujado?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Borrar") { _, _ -> sketch.clear() }
                    .show()
            }
        }
        bar.addView(clearButton)
        addView(bar)

        editor.apply {
            setText(notes.notebook)
            hint = "Escribe aquí tus ideas sobre el libro…\n\nSe guarda solo y se sincroniza con tu cuenta."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.2f)
            setPadding(context.dpi(20), context.dpi(4), context.dpi(20), context.dpi(40))
            doAfterTextChanged {
                handler.removeCallbacks(saveText)
                handler.postDelayed(saveText, 800)
            }
        }
        textBox.addView(editor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textBox.isFillViewport = true
        addView(textBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        loadSketch()
        sketch.onChanged = {
            saveSketch()
            growSketch()
        }
        sketchFrame.addView(sketch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sketchBox.addView(sketchFrame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(1400)))
        addView(sketchBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        sketchBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> growSketch() }

        setMode(tabs.selected)
    }

    private val saveText = Runnable { notes.setNotebook(editor.text.toString()) }

    private fun prefsMode() = context.getSharedPreferences("reader", Context.MODE_PRIVATE).getInt("notebookMode", 0)

    private fun setMode(m: Int) {
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putInt("notebookMode", m).apply()
        textBox.visibility = if (m == 0) View.VISIBLE else View.GONE
        sketchBox.visibility = if (m == 1) View.VISIBLE else View.GONE
        clearButton.visibility = if (m == 1) View.VISIBLE else View.GONE
    }

    /** Colores del tema de lectura activo. */
    fun setColors(paper: Int, text: Int, secondary: Int, accent: Int) {
        textColor = text
        secondaryColor = secondary
        editor.setTextColor(text)
        editor.setHintTextColor(secondary)
        clearButton.setTextColor(accent)
        sketch.penColor = text
        // Renglones suaves para escribir a mano
        sketchFrame.background = LinedPaper(ColorUtils.blendARGB(paper, text, 0.10f), context.dp(36f))
        sketch.invalidate()
    }

    /** Guarda lo pendiente (al cerrar el panel o salir del libro). */
    fun flush() {
        handler.removeCallbacks(saveText)
        if (editor.text.toString() != notes.notebook) saveText.run()
    }

    // Hoja más larga cuando se escribe cerca del final
    private fun growSketch() {
        val need = maxOf(sketch.contentBottom().toInt() + context.dpi(600), sketchBox.height, context.dpi(1400))
        val lp = sketchFrame.layoutParams
        if (lp != null && lp.height != need) {
            lp.height = need
            sketchFrame.layoutParams = lp
        }
    }

    private fun loadSketch() {
        if (!sketchFile.exists()) return
        runCatching {
            val arr = JSONObject(sketchFile.readText()).getJSONArray("strokes")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.getJSONArray("p")
                sketch.strokes += SketchView.Stroke(
                    o.optInt("c"), o.optDouble("w").toFloat(), FloatArray(p.length()) { p.getDouble(it).toFloat() }
                )
            }
        }
    }

    private fun saveSketch() {
        val arr = JSONArray()
        sketch.strokes.forEach { s ->
            arr.put(JSONObject().put("c", s.color).put("w", s.width.toDouble()).put("p", JSONArray().apply {
                // 4 decimales bastan y el archivo ocupa la mitad
                s.points.forEach { put(Math.round(it * 10000) / 10000.0) }
            }))
        }
        runCatching { sketchFile.writeText(JSONObject().put("strokes", arr).toString()) }
    }

    private class LinedPaper(private val lineColor: Int, private val gap: Float) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint().apply { color = lineColor; strokeWidth = 1.5f }
        override fun draw(canvas: android.graphics.Canvas) {
            var y = gap
            val b = bounds
            while (y < b.height()) {
                canvas.drawLine(b.left.toFloat(), b.top + y, b.right.toFloat(), b.top + y, paint)
                y += gap
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
