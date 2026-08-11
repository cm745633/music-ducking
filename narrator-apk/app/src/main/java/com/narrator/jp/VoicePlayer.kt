package com.narrator.jp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 播一句旁白。
 *
 * ducking 交給系統做：requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)，
 * 播完 abandon。不去動 STREAM_MUSIC 的系統音量——那會讓 Spotify 的音量條跳動，
 * 而且淡入淡出遠不如系統平滑。
 */
class VoicePlayer(ctx: Context) {

    private val appCtx: Context = ctx.applicationContext

    private val audioManager: AudioManager =
        appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var focusRequest: AudioFocusRequest? = null
    private var player: MediaPlayer? = null

    /** 音量滑桿拖動時即時套用到正在播的那一句。 */
    fun applyVolume(gain: Float) {
        val g = gain.coerceIn(0f, 1f)
        try {
            player?.setVolume(g, g)
        } catch (t: Throwable) {
            // 已 release，忽略
        }
    }

    suspend fun play(clip: Clip, gain: Float, duck: Boolean) = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Unit>()
        try {
            if (duck) requestFocus()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(attrs)
            appCtx.assets.openFd(VoiceIndex.BLOB).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset + clip.offset, clip.length)
            }
            val g = gain.coerceIn(0f, 1f)
            mp.setVolume(g, g)
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ ->
                done.complete(Unit)
                true
            }
            mp.setOnPreparedListener { p -> p.start() }
            mp.prepareAsync()
        } catch (t: Throwable) {
            done.complete(Unit)
        }
        // 單句約 2～6 秒；30 秒仍未回報完成就當作出事，避免整個迴圈卡死。
        withTimeoutOrNull(30_000L) { done.await() }
        cleanup(duck)
    }

    fun stop() {
        cleanup(true)
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { _ -> }
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun cleanup(duck: Boolean) {
        val mp = player
        player = null
        if (mp != null) {
            try {
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                mp.setOnPreparedListener(null)
                mp.reset()
                mp.release()
            } catch (t: Throwable) {
                // 忽略
            }
        }
        if (duck) {
            val req = focusRequest
            focusRequest = null
            if (req != null) {
                try {
                    audioManager.abandonAudioFocusRequest(req)
                } catch (t: Throwable) {
                    // 忽略
                }
            }
        }
    }
}
