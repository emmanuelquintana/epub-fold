package com.emmanuel.epubfold

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

/** Recordatorio diario a la hora elegida, con la racha y lo que falta para la meta. */
object Reminder {

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction("epubfold.REMINDER"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Programa (o quita) la alarma según las preferencias. */
    fun schedule(context: Context) {
        val prefs = ReaderPrefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context))
        if (!prefs.reminderOn) return
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.reminderMin / 60)
            set(Calendar.MINUTE, prefs.reminderMin % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Inexacta: no necesita permisos especiales y gasta menos batería (puede llegar unos minutos tarde)
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.timeInMillis, AlarmManager.INTERVAL_DAY, pending(context))
    }

    fun show(context: Context) {
        val prefs = ReaderPrefs(context)
        val stats = ReadingStats(context)
        val goal = prefs.dailyGoalMin
        val today = stats.todaySeconds() / 60
        if (today >= goal) return // meta cumplida: no molestamos
        val streak = stats.streak(goal)
        val left = goal - today
        val title = if (streak > 0) "🔥 $streak ${if (streak == 1) "día" else "días"} de racha: ¡no la pierdas!" else "📖 Un rato de lectura"
        val text = if (today == 0) "Lee $goal minutos hoy para cumplir tu meta." else "Te faltan $left minutos para tu meta de hoy."
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("reminder", "Recordatorio diario", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val book = Library(context).all().firstOrNull { it.collection == Shelf.READING }
        runCatching {
            nm.notify(43, NotificationCompat.Builder(context, "reminder")
                .setSmallIcon(R.drawable.ic_book)
                .setContentTitle(title)
                .setContentText(text + (book?.let { " Sigue con «${it.title}»." } ?: ""))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text + (book?.let { "\nSigue con «${it.title}»." } ?: "")))
                .setContentIntent(Widgets.openIntent(context, book, 300))
                .setAutoCancel(true)
                .build())
        }
    }
}

/** Recibe la alarma diaria y, al reiniciar el teléfono, la vuelve a programar. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Reminder.schedule(context)
                Inbox.schedule(context)
            }
            else -> Reminder.show(context)
        }
    }
}
