package com.emmanuel.epubfold

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Mantiene viva la lectura en voz alta con la pantalla apagada y muestra sus
 * controles en la notificación, la pantalla de bloqueo y los auriculares.
 * La lógica sigue en ReaderActivity, que se registra como [controller].
 */
class ReadAloudService : Service() {

    interface Controller {
        fun isPlaying(): Boolean
        fun play()
        fun pause()
        fun skip()
        fun stop()
        fun title(): String
        fun subtitle(): String
        fun cover(): Bitmap?
    }

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_UPDATE = "update"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_SKIP = "skip"
        private const val ACTION_STOP = "stop"

        var controller: Controller? = null

        /** Muestra o actualiza la notificación según el estado del lector. */
        fun update(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java).setAction(ACTION_UPDATE))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReadAloudService::class.java))
        }
    }

    private lateinit var session: MediaSessionCompat

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Lectura en voz alta", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        session = MediaSessionCompat(this, "EpubFold").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { ReadAloudService.controller?.play(); refresh() }
                override fun onPause() { ReadAloudService.controller?.pause(); refresh() }
                override fun onSkipToNext() { ReadAloudService.controller?.skip() }
                override fun onStop() { ReadAloudService.controller?.stop(); stopSelf() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = controller
        when (intent?.action) {
            ACTION_TOGGLE -> c?.let { if (it.isPlaying()) it.pause() else it.play() }
            ACTION_SKIP -> c?.skip()
            ACTION_STOP -> {
                c?.stop()
                finish()
                return START_NOT_STICKY
            }
        }
        if (c == null) {
            // El lector se cerró: no hay nada que controlar
            startAsForeground(baseNotification().build())
            finish()
            return START_NOT_STICKY
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(n: android.app.Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        }.onFailure {
            // Android no deja pasar a primer plano desde segundo plano: al menos actualizamos la notificación
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val playing = c.isPlaying()
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, c.title())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, c.subtitle())
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, c.cover())
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )
        val n = baseNotification()
            .setContentTitle(c.title())
            .setContentText(c.subtitle())
            .setLargeIcon(c.cover())
            .setOngoing(playing)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pausar" else "Reproducir", action(ACTION_TOGGLE, 1)
            )
            .addAction(R.drawable.ic_skip, "Frase siguiente", action(ACTION_SKIP, 2))
            .addAction(R.drawable.ic_close, "Detener", action(ACTION_STOP, 3))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
        startAsForeground(n)
        // En pausa se puede descartar la notificación
        if (!playing) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    private fun baseNotification(): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ?: Intent(this, LibraryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle("EpubFold")
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setDeleteIntent(action(ACTION_STOP, 4))
    }

    private fun action(a: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, ReadAloudService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onDestroy() {
        session.isActive = false
        session.release()
        super.onDestroy()
    }
}
