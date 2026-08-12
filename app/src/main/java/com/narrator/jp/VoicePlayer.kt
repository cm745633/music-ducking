package com.narrator.jp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow

/**
 * 播一句旁白。
 *
 * ducking 交給系統做：requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)，
 * 播完 abandon。不去動 STREAM_MUSIC 的系統音量——那會讓 Spotify 的音量條跳動，
 * 而且淡入淡出遠不如系統平滑。
 *
 * 音量以 dB 表示，跨越 0 dB 分成兩條路徑：
 *   dB <= 0  →  MediaPlayer.setVolume()，單純衰減
 *   dB >  0  →  setVolume(1.0) + LoudnessEnhancer.setTargetGain()
 *
 * 之所以需要 LoudnessEnhancer：MediaPlayer.setVolume() 的上限就是 1.0，
 * 不可能把音量放大到超過音檔本身。LoudnessEnhancer 是系統內建的增幅效果，
 * 帶自己的動態壓縮，拉高感知音量而不會削波。
 */
class VoicePlayer(ctx: Context) {

    companion object {
        /** null = 還沒試過；false = 這台裝置不支援增幅。UI 用來提示使用者。 */
        @Volatile
        var boostSupported: Boolean? = null
            private set

        const val MAX_BOOST_MB = 1500
    }

    private val appCtx: Context = ctx.applicationContext

    private val audioManager: AudioManager =
        appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var focusRequest: AudioFocusRequest? = null
    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null

    /** 音量滑桿拖動時即時套用到正在播的那一句。 */
    fun applyGain(totalDb: Float) {
        try {
            val mp = player
            if (mp != null) {
                val v = volumeFor(totalDb)
                mp.setVolume(v, v)
            }
            enhancer?.setTargetGain(boostMbFor(totalDb))
        } catch (t: Throwable) {
            // 已 release，忽略
        }
    }

    suspend fun play(clip: Clip, totalDb: Float, duck: Boolean) = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Unit>()
        try {
            if (duck) requestFocus()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(attrs)
            appCtx.assets.openFd(VoiceIndex.BLOB).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset + clip.offset, clip.length)
            }
            val v = volumeFor(totalDb)
            mp.setVolume(v, v)
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ ->
                done.complete(Unit)
                true
            }
            mp.setOnPreparedListener { p ->
                attachEnhancer(p, totalDb)
                p.start()
            }
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

    private fun volumeFor(totalDb: Float): Float =
        if (totalDb >= 0f) 1f else 10.0.pow(totalDb / 20.0).toFloat().coerceIn(0f, 1f)

    private fun boostMbFor(totalDb: Float): Int =
        if (totalDb <= 0f) 0 else (totalDb * 100f).toInt().coerceIn(0, MAX_BOOST_MB)

    private fun attachEnhancer(mp: MediaPlayer, totalDb: Float) {
        val mb = boostMbFor(totalDb)
        if (mb <= 0) return
        try {
            val le = LoudnessEnhancer(mp.audioSessionId)
            le.setTargetGain(mb)
            le.enabled = true
            enhancer = le
            boostSupported = true
        } catch (t: Throwable) {
            // 少數裝置不提供這個效果，退回單純的 setVolume(1.0)
            enhancer = null
            boostSupported = false
        }
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
        val le = enhancer
        enhancer = null
        if (le != null) {
            try {
                le.enabled = false
                le.release()
            } catch (t: Throwable) {
                // 忽略
            }
        }
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
