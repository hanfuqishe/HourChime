package com.example.hourchime

import android.content.*
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private companion object {
        /** 无操作多少毫秒后侧边栏自动滑出 */
        const val AUTO_HIDE_DELAY_MS = 30_000L

        /** 界面刷新倒计时的间隔 */
        const val UI_TICK_INTERVAL_MS = 1000L

        /** 侧边栏滑入/滑出动画时长 */
        const val SIDEBAR_ANIM_MS = 250L

        /** 倒计时文字最暗（alpha 下限，越小越暗） */
        const val MIN_BRIGHTNESS = 0.04f

        /** 倒计时文字亮度持久化的 key */
        const val KEY_BRIGHTNESS = "brightness"

        /** 选中的倒计时时长（分钟）持久化的 key 与默认值 */
        const val KEY_DURATION_MINUTES = "duration_minutes"
        const val DEFAULT_DURATION_MINUTES = 120

        /** 轻触的最长持续时间 */
        const val TAP_TIMEOUT_MS = 400L

        /** 秒相对时/分的字号比例（半高） */
        const val SECONDS_SCALE = 0.5f

        const val TAG = "HourChime"
    }

    private var startButton: Button? = null
    private var resetButton: Button? = null
    private var durationSpinner: Spinner? = null
    private var countdownTextView: TextView? = null
    private var controlsPanel: View? = null

    /** 侧边栏当前是否在可视区域内 */
    private var controlsVisible = true

    /** 倒计时文字亮度，MIN_BRIGHTNESS~1.0 */
    private var brightness = 1f

    /** Spinner 是否已完成“恢复上次选择”，避免初始化阶段的回调把默认值写进去 */
    private var spinnerReady = false

    private val prefs by lazy { getSharedPreferences("HourChimePrefs", Context.MODE_PRIVATE) }

    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private val durations = listOf(30, 60, 90, 120, 150, 180)

    /**
     * 手势自己判：横向滑动（超过 touchSlop 且横向分量更大）→ 调大字亮度，
     * 手指右移变亮；短促轻触 → 唤出侧边栏。
     * 不用 GestureDetector：它的 distanceX 是“上一次 - 当前”，符号和 tap/scroll
     * 的边界在这种“全屏大字 + 左侧浮层”结构下容易误判。
     */
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var swipingHorizontally = false

    /**
     * 每秒把服务里的剩余时间读出来刷新到界面上。
     *
     * 不用广播：Android 14 起动态注册的非系统广播必须声明导出标志，而
     * RECEIVER_NOT_EXPORTED 收不到本应用自己发的隐式广播（实测 onReceive 从不触发），
     * 所以直接读 TimerService 的静态状态更稳，也顺便避免了回到前台先闪一下 0:00:00。
     */
    private val uiTickHandler = Handler(Looper.getMainLooper())
    private val uiTickRunnable = object : Runnable {
        override fun run() {
            countdownTextView?.text = formatTime(TimerService.remainingMillis)
            updateKeepScreenOn()
            uiTickHandler.postDelayed(this, UI_TICK_INTERVAL_MS)
        }
    }

    /** 当前是否已给窗口加上“不炋屏”标志 */
    private var keepScreenOn = false

    fun initializeDefaultSounds(context: Context) {
        val prefs = context.getSharedPreferences("HourChimePrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        val ringtoneManager = RingtoneManager(context)
        ringtoneManager.setType(RingtoneManager.TYPE_ALL)
        val cursor = ringtoneManager.cursor

        fun findRingtoneUriByName(name: String): String? {
            cursor.moveToPosition(-1)  // 确保从头开始
            while (cursor.moveToNext()) {
                val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                if (title.equals(name, ignoreCase = true)) {
                    return ringtoneManager.getRingtoneUri(cursor.position).toString()
                }
            }
            return null
        }

        if (!prefs.contains("uri_half")) {
            //val cuckoo = findRingtoneUriByName("Cuckoo")
            var cuckoo = "content://media/internal/audio/media/31"
            if (cuckoo != null) editor.putString("uri_half", cuckoo)
        }

        if (!prefs.contains("uri_10min")) {
            val bell = findRingtoneUriByName("Bell")
            if (bell != null) editor.putString("uri_10min", bell)
        }

        if (!prefs.contains("uri_end")) {
            val birdsong = findRingtoneUriByName("Birdsong")
            if (birdsong != null) editor.putString("uri_end", birdsong)
        }

        editor.apply()
    }




    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 应用已固定横屏（见 AndroidManifest），这里始终进入沉浸式全屏
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        initializeDefaultSounds(this)

        setContentView(R.layout.activity_main)

        controlsPanel = findViewById(R.id.controlsPanel)
        countdownTextView = findViewById(R.id.countdownTextView)
        Log.d(TAG, "onCreate durations=$durations prefs=${prefs.all}")
        startButton = findViewById(R.id.startButton)
        resetButton = findViewById(R.id.resetButton)
        durationSpinner = findViewById(R.id.durationSpinner)

//        countdownTextView?.visibility = View.VISIBLE



        findViewById<Button>(R.id.settingsBtn)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.testSoundButton)?.setOnClickListener {
            // 播放的是 10 分钟档的铃声，音量必须读 10 分钟档的 key
            SoundSettingsHelper.playSound(this, "uri_10min", "volume_10min")
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            durations.map { "$it 分钟" }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        durationSpinner?.adapter = adapter

        // 恢复上次选中的时长；选完自动存
        val savedMinutes = prefs.getInt(KEY_DURATION_MINUTES, DEFAULT_DURATION_MINUTES)
        val savedIndex = durations.indexOf(savedMinutes)
            .takeIf { it >= 0 } ?: durations.indexOf(DEFAULT_DURATION_MINUTES)
        durationSpinner?.setSelection(savedIndex)
        durationSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!spinnerReady) return
                prefs.edit().putInt(KEY_DURATION_MINUTES, durations[position]).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinnerReady = true

        resetButton?.isEnabled = true
        startButton?.isEnabled = true

        startButton?.setOnClickListener {
            val selectedMinutes = durations[durationSpinner?.selectedItemPosition ?: 0]
            if (!TimerService.isRunning) {
                val intent = Intent(this, TimerService::class.java)
                intent.putExtra("selectedMinutes", selectedMinutes)
                startForegroundService(intent)
            }
        }

        resetButton?.setOnClickListener {
            // 停止后台的计时服务，并掐掉可能还在播的提示音
            SoundSettingsHelper.stopAll()
            val stopIntent = Intent(this, TimerService::class.java)
            stopService(stopIntent)

            // 重置 UI
            countdownTextView?.text = formatTime(0L)
        }

        // 恢复上次设置的亮度；初始显示侧边栏并开始 30 秒无操作计时
        setBrightness(prefs.getFloat(KEY_BRIGHTNESS, 1f))
        showControls()
    }

    override fun onResume() {
        super.onResume()
        // 立即显示当前剩余时间，之后每秒刷新
        uiTickHandler.post(uiTickRunnable)
        updateKeepScreenOn()
        showControls()
    }

    override fun onPause() {
        super.onPause()
        uiTickHandler.removeCallbacks(uiTickRunnable)
        autoHideHandler.removeCallbacks(hideControlsRunnable)
        prefs.edit().putFloat(KEY_BRIGHTNESS, brightness).apply()
    }

    /**
     * 手势在派发前先自己看一遂，但不消费事件，所以按钮点击照常。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                downTime = ev.eventTime
                swipingHorizontally = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!swipingHorizontally &&
                    abs(ev.x - downX) > touchSlop &&
                    abs(ev.x - downX) > abs(ev.y - downY)
                ) {
                    swipingHorizontally = true
                }
                if (swipingHorizontally) {
                    // 手指右移（ev.x > lastX）→ 变亮；满屏宽 * 0.7 对应满量程
                    setBrightness(brightness + (ev.x - lastX) / (screenWidth() * 0.7f))
                    if (controlsVisible) restartAutoHide()
                }
                lastX = ev.x
            }

            MotionEvent.ACTION_UP -> {
                if (swipingHorizontally) {
                    Log.d(TAG, "横向滑动结束 -> 亮度=$brightness")
                    // 滑动结束立即落盘，不依赖 onPause（被强杀也不会丢）
                    prefs.edit().putFloat(KEY_BRIGHTNESS, brightness).apply()
                } else if (ev.eventTime - downTime <= TAP_TIMEOUT_MS) {
                    if (isTapInsideSidebar(ev.x)) {
                        // 点在侧边栏上：交给里面的按钮自己处理，只把自动隐藏计时往后推
                        restartAutoHide()
                    } else if (controlsVisible) {
                        Log.d(TAG, "轻触 -> 收起侧边栏")
                        hideControls()
                    } else {
                        Log.d(TAG, "轻触 -> 唤出侧边栏")
                        showControls()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 侧边栏滑入可视区域，并重新开始 30 秒无操作计时 */
    private fun showControls() {
        val panel = controlsPanel ?: return
        if (!controlsVisible) {
            controlsVisible = true
            panel.animate()
                .translationX(0f)
                .setDuration(SIDEBAR_ANIM_MS)
                .start()
        }
        restartAutoHide()
    }

    /** 30 秒无操作：侧边栏整体向左滑出可视区域 */
    private fun hideControls() {
        val panel = controlsPanel ?: return
        if (!controlsVisible) return
        controlsVisible = false
        panel.animate()
            .translationX(-panel.width.toFloat())
            .setDuration(SIDEBAR_ANIM_MS)
            .start()
    }

    private fun restartAutoHide() {
        autoHideHandler.removeCallbacks(hideControlsRunnable)
        autoHideHandler.postDelayed(hideControlsRunnable, AUTO_HIDE_DELAY_MS)
    }

    /**
     * 这一次轻触是否落在侧边栏上。
     * 面板是 match_parent 高，所以只看横向；隐藏时它被平移到屏幕左侧之外，自然返回 false。
     */
    private fun isTapInsideSidebar(x: Float): Boolean {
        val panel = controlsPanel ?: return false
        return x >= panel.translationX && x <= panel.translationX + panel.width
    }

    /** 设置倒计时文字亮度（0.1~1.0）；持久化在 onPause 统一做 */
    private fun setBrightness(value: Float) {
        brightness = value.coerceIn(MIN_BRIGHTNESS, 1f)
        countdownTextView?.alpha = brightness
    }

    /**
     * 倒计时进行中不给屏幕自动熄屏。
     * 用窗口标志而不是唤醒锁：只在本界面可见时生效，切后台/进设置页时不会强占屏幕。
     */
    private fun updateKeepScreenOn() {
        val shouldKeep = TimerService.isRunning
        if (shouldKeep == keepScreenOn) return
        keepScreenOn = shouldKeep
        if (shouldKeep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        Log.d(TAG, "keepScreenOn=$shouldKeep")
    }

    private fun screenWidth(): Float = resources.displayMetrics.widthPixels.toFloat()

    /** 格式化剩余时间；最后两位（秒）用半高字号，和时/分共用基线 */
    private fun formatTime(millis: Long): CharSequence {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val head = String.format("%d:%02d", hours, minutes)
        val tail = ":" + String.format("%02d", seconds)
        return SpannableString(head + tail).apply {
            setSpan(
                RelativeSizeSpan(SECONDS_SCALE),
                head.length,
                head.length + tail.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

}
