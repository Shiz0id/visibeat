package com.visibeat.musicui.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Session-extras key carrying the audio session id the visualiser must attach to. */
internal const val KEY_AUDIO_SESSION_ID = "audio_session_id"

class MediaPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    companion object {
        /**
         * The audio session the visualiser must attach to.
         *
         * Published here as well as in the session extras because the extras
         * never actually arrived: PlaybackManager read `sessionExtras` and got
         * 0 back, fell through to `Visualizer(0)`, and that throws outright —
         * Android does not let ordinary apps capture the global output mix. The
         * service is declared with no `android:process`, so it shares a process
         * with the UI and this needs no IPC and has no delivery window to miss.
         *
         * [C.AUDIO_SESSION_ID_UNSET] until the service has been created.
         */
        @Volatile
        var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // Allocate the audio session id ourselves instead of waiting for ExoPlayer
        // to mint one when it first opens an AudioTrack.
        //
        // This is what the visualiser attaches to. Left to ExoPlayer, the id is
        // C.AUDIO_SESSION_ID_UNSET until playback actually starts, so the id
        // published in the session extras was 0 — and PlaybackManager, reading it
        // at the moment playback began, fell back to capturing the global output
        // mix. Ordinary apps cannot capture that on Android 10+: the Visualizer
        // constructs happily and then delivers nothing but zeroes forever, which
        // is why the cube froze the instant it stopped animating into position.
        //
        // Generating the id up front means it is valid and published from the
        // moment the session exists, before any controller has connected.
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val generatedSessionId = audioManager.generateAudioSessionId()
            .takeIf { it != AudioManager.ERROR }
            ?: C.AUDIO_SESSION_ID_UNSET

        val player = ExoPlayer.Builder(this)
            // Take audio focus like a music app, and duck out of the way of
            // notifications instead of talking over them.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Pause when headphones are pulled rather than blasting the speaker.
            .setHandleAudioBecomingNoisy(true)
            .build()

        if (generatedSessionId != C.AUDIO_SESSION_ID_UNSET) {
            player.audioSessionId = generatedSessionId
        }
        audioSessionId = player.audioSessionId
        android.util.Log.i(
            "VisiBeatViz",
            "service audio session: generated=$generatedSessionId player=${player.audioSessionId}"
        )

        val extras = Bundle().apply {
            putInt(KEY_AUDIO_SESSION_ID, player.audioSessionId)
        }

        mediaSession = MediaSession.Builder(this, player)
            .setExtras(extras)
            // What tapping the notification opens.
            //
            // media3's notification provider uses the session activity as the
            // notification's content intent. Without one there is no content
            // intent at all, so the media control in the shade — and on the
            // lockscreen — was inert: the transport buttons worked, but tapping
            // the body of it did nothing.
            .apply { launchIntent()?.let { setSessionActivity(it) } }
            .build()

        // Belt and braces: if the platform hands the player a different id anyway,
        // republish so connected controllers can re-attach.
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    Companion.audioSessionId = audioSessionId
                    android.util.Log.i("VisiBeatViz", "service audio session changed: $audioSessionId")
                    mediaSession?.setSessionExtras(Bundle().apply {
                        putInt(KEY_AUDIO_SESSION_ID, audioSessionId)
                    })
                }
            }
        })
    }

    /**
     * A PendingIntent that brings the app back, or null if it somehow has no
     * launcher entry.
     *
     * Resolved through the package manager rather than by naming the activity:
     * this service lives in `music-ui`, which the app module depends on and not
     * the other way round, so `MainActivity` is not visible from here. The
     * launcher intent carries `FLAG_ACTIVITY_NEW_TASK` and matches the one the
     * home screen fires, which means tapping the notification resumes the
     * existing task instead of stacking a second copy of the activity.
     */
    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            // IMMUTABLE is mandatory from API 31 and harmless before it.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Swiping the app away while paused should not leave a stuck notification
     * and an idle service behind.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
