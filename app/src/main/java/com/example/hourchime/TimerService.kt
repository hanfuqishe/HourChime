package com.example.hourchime

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class TimerService : Service() {

    private var timer: Timer? = null
    private var intervalMillis: Long = 0

    private var nextHalfHourMillis: Long = 0
    private var hasPlayed10Min = false

    private val TAG = "TimerService"

    companion object {
        var isRunning = false

        /** 当前剩余毫秒，由本服务的 Timer 线程写入、界面线程读取 */
        @Volatile
        var remainingMillis: Long = 0L

        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "TimerServiceChannel"
    }

    private lateinit var wakeLock: PowerManager.WakeLock

    @SuppressLint("DefaultLocale")
    private fun createNotification(remaining: Long): Notification {
        val channelId = CHANNEL_ID

        val hours = remaining / (60 * 60 * 1000)
        val minutes = (remaining / (60 * 1000)) % 60
        val seconds = (remaining / 1000) % 60
        val timeText = String.format("%2d:%02d:%02d", hours, minutes, seconds)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("悟空倒计时")
            .setContentText("剩余时间：$timeText")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }



    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HourChime::WakeLock"
        )
        wakeLock.acquire()

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with intent: $intent")

        if (isRunning) {
            Log.d(TAG, "Service already running, ignoring duplicate start")
            return START_STICKY
        }

        val minutes = intent?.getIntExtra("selectedMinutes", 0) ?: 0
        if (minutes <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        intervalMillis = minutes * 60 * 1000L
        remainingMillis = intervalMillis
        nextHalfHourMillis = intervalMillis - 30 * 60 * 1000L

        startForeground(NOTIFICATION_ID, createNotification(minutes * 60 * 1000L))

        startCountdown()

        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        if (this::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
            Log.d("TimerService", "WakeLock released")
        }
        remainingMillis = 0L
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCountdown() {
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                remainingMillis -= 1000

                // 更新通知
                startForeground(NOTIFICATION_ID, createNotification(remainingMillis))

                // 每 30 分钟播放一次半小时提示音
                if (remainingMillis <= nextHalfHourMillis && nextHalfHourMillis > 0) {
                    SoundSettingsHelper.playSound(this@TimerService, "uri_half", "volume_half")
                    nextHalfHourMillis -= 30 * 60 * 1000L  // 更新下一次播放时间点
                }

                // 10 分钟提示音（只播一次）
                if (!hasPlayed10Min && remainingMillis <= 10 * 60 * 1000L) {
                    SoundSettingsHelper.playSound(this@TimerService, "uri_10min", "volume_10min")
                    hasPlayed10Min = true
                }

                // 结束
                if (remainingMillis <= 0L) {
                    Log.d(TAG, "倒计时结束（remaining=$remainingMillis）-> 播放结束音")
                    SoundSettingsHelper.playSound(this@TimerService, "uri_end", "volume_end")
                    stopSelf()
                }
            }
        }, 1000, 1000)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "倒计时服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
