package com.emmanuel.epubfold

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aviso de actualizaciones: el CI publica el último APK y latest.json en el repo
 * público epub-fold-releases; aquí se consulta y se ofrece descargarlo e instalarlo.
 */
object Updater {

    private const val LATEST_URL =
        "https://raw.githubusercontent.com/emmanuelquintana/epub-fold-releases/main/latest.json"
    private const val AUTO_INTERVAL = 6 * 60 * 60_000L

    data class Release(val code: Int, val name: String, val notes: String, val apk: String)

    private fun prefs(context: Context) = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun fetch(): Release {
        // raw.githubusercontent cachea unos minutos: el parámetro evita respuestas viejas
        val conn = URL("$LATEST_URL?t=${System.currentTimeMillis() / 60_000}").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            return Release(o.getInt("versionCode"), o.optString("versionName"), o.optString("notes"), o.getString("apk"))
        } finally {
            conn.disconnect()
        }
    }

    /** Comprueba si hay versión nueva. En modo automático, como mucho cada 6 horas y sin insistir con versiones omitidas. */
    fun check(activity: AppCompatActivity, manual: Boolean) {
        // En Google Play las actualizaciones llegan por la tienda
        if (!BuildConfig.SELF_UPDATE) return
        val sp = prefs(activity)
        if (!manual && System.currentTimeMillis() - sp.getLong("lastCheck", 0L) < AUTO_INTERVAL) return
        activity.lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { fetch() } }
            sp.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            val rel = r.getOrNull()
            if (rel == null) {
                if (manual) toast(activity, "No se pudo comprobar: ${r.exceptionOrNull()?.message}")
                return@launch
            }
            when {
                rel.code <= BuildConfig.VERSION_CODE ->
                    if (manual) toast(activity, "Tienes la última versión (${BuildConfig.VERSION_NAME})")
                !manual && sp.getInt("skipped", 0) == rel.code -> {}
                activity.isFinishing -> {}
                else -> offer(activity, rel)
            }
        }
    }

    private fun offer(activity: AppCompatActivity, rel: Release) {
        val notes = rel.notes.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Nueva versión ${rel.name}")
            .setMessage(
                "Tienes la ${BuildConfig.VERSION_NAME}." +
                    (if (notes.isNotEmpty()) "\n\nNovedades: $notes" else "") +
                    "\n\nSe descargará el APK (≈30 MB) y se abrirá el instalador."
            )
            .setPositiveButton("Actualizar") { _, _ -> download(activity, rel) }
            .setNegativeButton("Más tarde", null)
            .setNeutralButton("Omitir") { _, _ -> prefs(activity).edit().putInt("skipped", rel.code).apply() }
            .show()
    }

    private fun download(activity: AppCompatActivity, rel: Release) {
        val app = activity.applicationContext
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        dir.listFiles { f -> f.name.endsWith(".apk") }?.forEach { it.delete() }
        val name = "EpubFold-${rel.code}.apk"
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = runCatching {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(rel.apk))
                    .setTitle("EpubFold ${rel.name}")
                    .setDescription("Descargando actualización")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name)
            )
        }.getOrElse {
            // Sin gestor de descargas: que lo descargue el navegador
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apk)))
            return
        }
        toast(activity, "Descargando la actualización…")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val ok = dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                    cur.moveToFirst() &&
                        cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                } ?: false
                if (ok) install(app, File(dir, name)) else toast(app, "La descarga falló")
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun install(context: Context, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            toast(context, "Permite «Instalar apps desconocidas» para EpubFold y vuelve a pulsar Actualizar")
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}
