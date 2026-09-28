package com.example.hourchime

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

object SoundSettingsHelper {

    private const val PREFS_NAME = "HourChimePrefs"

    /** 音量滑条最大值，需与 activity_settings.xml 中 SeekBar 的 android:max 保持一致 */
    const val MAX_VOLUME = 15

    /** 音量默认值，需与 SettingsActivity 中滑条默认值保持一致 */
    private const val DEFAULT_VOLUME = 10

    /** 正在播放的 MediaPlayer，必须持有引用，否则可能被 GC 提前回收导致声音中断 */
    private val activePlayers = mutableSetOf<MediaPlayer>()

    // 读取音频URI，key示例："uri_half", "uri_10min", "uri_end"
    fun getSoundUri(context: Context, key: String): Uri? {
        val uriStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
        return if (uriStr.isNullOrEmpty()) null else Uri.parse(uriStr)
    }

    // 读取音量，范围0~15，key示例："volume_half", "volume_10min", "volume_end"
    fun getVolume(context: Context, key: String): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key, DEFAULT_VOLUME)
            .coerceIn(0, MAX_VOLUME)
    }

    /** 把滑条值（0~15）换算成 MediaPlayer 需要的 0f~1f */
    fun getVolumeScale(context: Context, key: String): Float {
        return getVolume(context, key) / MAX_VOLUME.toFloat()
    }

    // 播放指定key的音效，音量取自 volumeKey 对应的滑条值
    fun playSound(context: Context, uriKey: String, volumeKey: String) {
        val uri = getSoundUri(context, uriKey)
        val scale = getVolumeScale(context, volumeKey)
        Log.d("SoundSettingsHelper", "playSound $uriKey volume=$scale uri=$uri")
        if (uri == null) return
        playUri(context, uri, scale)
    }

    // 播放任意音频，volume 取值 0f~1f
    fun playUri(context: Context, uri: Uri, volume: Float) {
        val safeVolume = volume.coerceIn(0f, 1f)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(context.applicationContext, uri)
            player.isLooping = false
            // 音量要在 prepare 完成之后设置，部分 ROM 在 prepare 之前设置会被忽略
            player.setOnPreparedListener { mp ->
                mp.setVolume(safeVolume, safeVolume)
                mp.start()
            }
            player.setOnCompletionListener { mp ->
                activePlayers.remove(mp)
                mp.release()
            }
            player.setOnErrorListener { mp, _, _ ->
                activePlayers.remove(mp)
                mp.release()
                true
            }
            activePlayers.add(player)
            // 异步 prepare，避免阻塞调用线程（主线程调用时会 ANR）
            player.prepareAsync()
        } catch (e: Exception) {
            activePlayers.remove(player)
            player.release()
            e.printStackTrace()
        }
    }

    /** 释放所有还在播放的音频（例如服务被销毁时） */
    fun stopAll() {
        activePlayers.toList().forEach { player ->
            activePlayers.remove(player)
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {
            }
            player.release()
        }
    }
}
