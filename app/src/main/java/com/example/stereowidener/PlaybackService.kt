package com.example.stereowidener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

/**
 * Owns the single AudioWidenerEngine instance and runs it as a foreground
 * service so playback (and its notification / lock-screen controls)
 * survive the user leaving MainActivity or turning the screen off.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "stereo_widener_playback"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.example.stereowidener.ACTION_STOP"
    }

    interface PlaybackStateListener {
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    val engine: AudioWidenerEngine by lazy { AudioWidenerEngine(this) }

    var stateListener: PlaybackStateListener? = null

    private var mediaSession: MediaSessionCompat? = null
    private var currentTrackName: String = "Stereo Widener"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "StereoWidenerSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPause() = stopPlayback()
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
        engine.onCompletion = {
            stateListener?.onPlaybackStateChanged(false)
            updateMediaSessionState(false)
            stopForegroundCompat()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun play(uri: Uri, trackName: String) {
        currentTrackName = trackName
        engine.play(uri)
        updateMediaSessionMetadata()
        updateMediaSessionState(true)
        startForeground(NOTIF_ID, buildNotification())
        stateListener?.onPlaybackStateChanged(true)
    }

    fun stopPlayback() {
        engine.stop()
        updateMediaSessionState(false)
        stateListener?.onPlaybackStateChanged(false)
        stopForegroundCompat()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTrackName)
            .setContentText("Stereo Widener — playing")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setOngoing(true)
            .build()
    }

    private fun updateMediaSessionMetadata() {
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTrackName)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Stereo Widener")
                .build()
        )
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
                .setState(state, 0, 1f)
                .build()
        )
    }

    override fun onDestroy() {
        engine.stop()
        mediaSession?.release()
        super.onDestroy()
    }
}
