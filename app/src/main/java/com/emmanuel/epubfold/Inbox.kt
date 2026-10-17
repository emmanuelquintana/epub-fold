package com.emmanuel.epubfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * «Enviar a EpubFold»: una carpeta que la app revisa sola (al abrirla y cada pocas
 * horas) e importa los libros nuevos. Sirve con cualquier carpeta sincronizada:
 * Descargas, una carpeta que llenas desde el PC o con Quick Share, o una de
 * Drive/OneDrive sincronizada con una app de sincronización.
 */
object Inbox {

    private fun prefs(context: Context) = context.getSharedPreferences("inbox", Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = prefs(context).getString("tree", null)?.let { Uri.parse(it) }

    fun setFolder(context: Context, tree: Uri?) {
        prefs(context).edit().apply {
            if (tree == null) remove("tree") else putString("tree", tree.toString())
            // Lo que ya había en la carpeta al elegirla también se importa
            remove("seen")
        }.apply()
        if (tree == null) WorkManager.getInstance(context).cancelUniqueWork("inbox")
        else schedule(context)
    }

    fun schedule(context: Context) {
        if (folder(context) == null) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "inbox", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<InboxWorker>(6, TimeUnit.HOURS).build(),
        )
    }

    /** Importa los libros nuevos de la carpeta. Devuelve sus títulos. Bloqueante. */
    fun scan(context: Context): List<String> {
        val tree = folder(context) ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, tree) ?: return emptyList()
        if (!root.canRead()) return emptyList()
        val sp = prefs(context)
        val seen = HashSet(sp.getStringSet("seen", emptySet()) ?: emptySet())
        val found = ArrayList<DocumentFile>()
        fun walk(d: DocumentFile, depth: Int) {
            if (depth > 3) return
            d.listFiles().forEach { f ->
                val n = f.name?.lowercase() ?: ""
                if (f.isDirectory) walk(f, depth + 1)
                else if (LibraryActivity.BOOK_EXT.any { n.endsWith(".$it") }) found += f
            }
        }
        walk(root, 0)
        val library = Library(context)
        val added = ArrayList<String>()
        found.forEach { f ->
            val id = "${f.uri}|${f.length()}"
            if (id in seen) return@forEach
            val before = library.all().size
            runCatching { library.import(f.uri) }.onSuccess { e ->
                if (library.all().size > before) added += e.title
            }
            seen += id
        }
        sp.edit().putStringSet("seen", seen).apply()
        return added
    }

    fun notifyImported(context: Context, titles: List<String>) {
        if (titles.isEmpty()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("inbox", "Libros nuevos", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, 7, Intent(context, LibraryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (titles.size == 1) "«${titles[0]}» está en tu biblioteca" else "${titles.size} libros nuevos: " + titles.take(3).joinToString(", ")
        runCatching {
            nm.notify(42, NotificationCompat.Builder(context, "inbox")
                .setSmallIcon(R.drawable.ic_book)
                .setContentTitle("Libros añadidos a EpubFold")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }
}

class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val added = runCatching { Inbox.scan(applicationContext) }.getOrDefault(emptyList())
        Inbox.notifyImported(applicationContext, added)
        return Result.success()
    }
}
