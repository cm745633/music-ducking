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
import kotlin.math.pow

/**
 * 診斷版的播放器。
 *
 * 與正式版的差別：**完全不使用 LoudnessEnhancer**，播放端只做衰減。
 * 那個效果帶自己的動態壓縮，會在判斷「雜音是哪來的」時多加一個變因。
 * 這裡音量一律 <= 0 dB，訊號路徑上除了音量乘法之外沒有任何處理。
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

    fun applyGain(totalDb: Float) {
        try {
            val mp = player
            if (mp != null) {
                val v = volumeFor(totalDb)
                mp.setVolume(v, v)
            }
        } catch (t: Throwable) {
            // 已 release，忽略
        }
    }

    suspend fun play(clip: Clip, mode: Int, extraDb: Float, duck: Boolean) =
        withContext(Dispatchers.Main) {
            val v = clip.variant(mode) ?: clip.variants.firstOrNull()
            if (v != null) {
                // 音量對齊：把這個版本衰減到四個版本裡最小聲的那個水準。恆為負值。
                playVariant(v, (extraDb + (clip.refLufs - v.lufs)).coerceIn(-60f, 0f), duck)
            }
        }

    fun stop() {
        cleanup(true)
    }

    private suspend fun playVariant(v: Variant, totalDb: Float, duck: Boolean) {
        val done = CompletableDeferred<Unit>()
        try {
            if (duck) requestFocus()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(attrs)
            appCtx.assets.openFd(v.blob).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset + v.offset, v.length)
            }
            val vol = volumeFor(totalDb)
            mp.setVolume(vol, vol)
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
        withTimeoutOrNull(30_000L) { done.await() }
        cleanup(duck)
    }

    private fun volumeFor(totalDb: Float): Float =
        if (totalDb >= 0f) 1f else 10.0.pow(totalDb / 20.0).toFloat().coerceIn(0f, 1f)

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
