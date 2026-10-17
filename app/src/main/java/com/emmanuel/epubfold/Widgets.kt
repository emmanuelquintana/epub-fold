package com.emmanuel.epubfold

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews

/** Widgets de la pantalla de inicio (también en la pantalla exterior del Fold). */
object Widgets {

    /** Refresca los widgets en segundo plano (leen la biblioteca y las notas). */
    fun updateAll(context: Context) {
        val app = context.applicationContext
        val m = AppWidgetManager.getInstance(app) ?: return
        val cont = m.getAppWidgetIds(ComponentName(app, ContinueWidget::class.java))
        val mem = m.getAppWidgetIds(ComponentName(app, MemoryWidget::class.java))
        if (cont.isEmpty() && mem.isEmpty()) return
        Thread {
            runCatching {
                ContinueWidget.update(app, m, cont)
                MemoryWidget.update(app, m, mem)
            }
        }.start()
    }

    /** Abre el libro con la biblioteca debajo, para que «atrás» vuelva a ella. */
    fun openIntent(context: Context, entry: BookEntry?, code: Int, extra: (Intent) -> Unit = {}): PendingIntent {
        val library = Intent(context, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intents = if (entry == null) arrayOf(library)
        else arrayOf(library, LibraryActivity.readerIntent(context, entry).also(extra))
        return PendingIntent.getActivities(context, code, intents, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun cover(path: String?, maxW: Int = 220): Bitmap? {
        path ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= maxW) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}

/** «Seguir leyendo»: portada, título y progreso del último libro. */
class ContinueWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    companion object {
        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val all = Library(context).all()
            val book = all.firstOrNull { it.collection == Shelf.READING } ?: all.firstOrNull()
            val v = RemoteViews(context.packageName, R.layout.widget_continue)
            if (book == null) {
                v.setTextViewText(R.id.wTitle, "Tu biblioteca está vacía")
                v.setTextViewText(R.id.wAuthor, "Toca para añadir un libro")
                v.setViewVisibility(R.id.wProgress, View.GONE)
                v.setTextViewText(R.id.wPercent, "")
                v.setImageViewResource(R.id.wCover, R.drawable.cover_placeholder)
            } else {
                v.setTextViewText(R.id.wTitle, book.title)
                v.setTextViewText(R.id.wAuthor, book.author.ifEmpty { book.format.ext.uppercase() })
                v.setViewVisibility(R.id.wProgress, View.VISIBLE)
                v.setProgressBar(R.id.wProgress, 1000, (book.progress * 1000).toInt(), false)
                v.setTextViewText(R.id.wPercent, if (book.progress <= 0f) "Sin empezar" else "${(book.progress * 100).toInt()} % leído")
                val bmp = Widgets.cover(book.coverPath)
                if (bmp != null) v.setImageViewBitmap(R.id.wCover, bmp) else v.setImageViewResource(R.id.wCover, R.drawable.cover_placeholder)
            }
            v.setOnClickPendingIntent(R.id.wRoot, Widgets.openIntent(context, book, 100))
            manager.updateAppWidget(ids, v)
        }
    }
}

/** «Recuerdo del día»: un subrayado de tus libros, distinto cada día. */
class MemoryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    companion object {
        fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val m = Review.ofTheDay(context)
            val v = RemoteViews(context.packageName, R.layout.widget_memory)
            if (m == null) {
                v.setTextViewText(R.id.wQuote, "Subraya frases mientras lees y aquí aparecerá una cada día.")
                v.setTextViewText(R.id.wSource, "EpubFold")
                v.setOnClickPendingIntent(R.id.wRoot, Widgets.openIntent(context, null, 200))
            } else {
                v.setTextViewText(R.id.wQuote, "“${m.highlight.text.trim()}”")
                v.setTextViewText(R.id.wSource, m.book.title + if (m.book.author.isNotEmpty()) " · ${m.book.author}" else "")
                v.setOnClickPendingIntent(R.id.wRoot, Widgets.openIntent(context, m.book, 201) {
                    it.putExtra(ReaderActivity.EXTRA_CHAPTER, m.highlight.chapter)
                        .putExtra(ReaderActivity.EXTRA_START, m.highlight.start)
                        .putExtra(ReaderActivity.EXTRA_END, m.highlight.end)
                })
            }
            manager.updateAppWidget(ids, v)
        }
    }
}
