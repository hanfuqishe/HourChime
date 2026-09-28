package com.example.hourchime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_HALF = 1001
        private const val REQUEST_CODE_10MIN = 1002
        private const val REQUEST_CODE_END = 1003
    }

    private lateinit var btnSelectHalf: Button
    private lateinit var btnSelect10Min: Button
    private lateinit var btnSelectEnd: Button

    private lateinit var tvHalfName: TextView
    private lateinit var tv10MinName: TextView
    private lateinit var tvEndName: TextView

    private lateinit var seekVolumeHalf: SeekBar
    private lateinit var seekVolume10Min: SeekBar
    private lateinit var seekVolumeEnd: SeekBar

    private val prefs by lazy { getSharedPreferences("HourChimePrefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        btnSelectHalf = findViewById(R.id.selectHalfBtn)
        btnSelect10Min = findViewById(R.id.selectTenMinBtn)
        btnSelectEnd = findViewById(R.id.selectEndBtn)

        tvHalfName = findViewById(R.id.selectHalfName)
        tv10MinName = findViewById(R.id.selectTenMinName)
        tvEndName = findViewById(R.id.selectEndName)

        seekVolumeHalf = findViewById(R.id.halfVolume)
        seekVolume10Min = findViewById(R.id.tenMinVolume)
        seekVolumeEnd = findViewById(R.id.endVolume)

        // 绑定按钮点击，打开系统铃声选择器
        btnSelectHalf.setOnClickListener {
            openRingtonePicker(REQUEST_CODE_HALF, getUri("uri_half"))
        }
        btnSelect10Min.setOnClickListener {
            openRingtonePicker(REQUEST_CODE_10MIN, getUri("uri_10min"))
        }
        btnSelectEnd.setOnClickListener {
            openRingtonePicker(REQUEST_CODE_END, getUri("uri_end"))
        }

        // 试听：用该档自己的音频 + 自己的音量，方便验证滑条是否真的生效
        findViewById<Button>(R.id.previewHalfBtn).setOnClickListener {
            previewSound("uri_half", "volume_half")
        }
        findViewById<Button>(R.id.previewTenMinBtn).setOnClickListener {
            previewSound("uri_10min", "volume_10min")
        }
        findViewById<Button>(R.id.previewEndBtn).setOnClickListener {
            previewSound("uri_end", "volume_end")
        }

        // 初始化滑条位置
        seekVolumeHalf.progress = prefs.getInt("volume_half", 10)
        seekVolume10Min.progress = prefs.getInt("volume_10min", 10)
        seekVolumeEnd.progress = prefs.getInt("volume_end", 10)

        // 监听滑条，保存音量
        seekVolumeHalf.setOnSeekBarChangeListener(createVolumeChangeListener("volume_half"))
        seekVolume10Min.setOnSeekBarChangeListener(createVolumeChangeListener("volume_10min"))
        seekVolumeEnd.setOnSeekBarChangeListener(createVolumeChangeListener("volume_end"))

        // 显示已保存的铃声名称
        updateRingtoneName("uri_half", tvHalfName)
        updateRingtoneName("uri_10min", tv10MinName)
        updateRingtoneName("uri_end", tvEndName)
    }

    private fun openRingtonePicker(requestCode: Int, currentUri: Uri?) {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择报时音")
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        }
        startActivityForResult(intent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            val pickedUri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val key = getKeyForRequestCode(requestCode)
            if (pickedUri == null) {
                saveUri(key, null)
                showToast("已设置为无声")
            } else {
                saveUri(key, pickedUri)
                showToast("音频已保存")
            }
            // 更新界面文本
            when (requestCode) {
                REQUEST_CODE_HALF -> updateRingtoneName("uri_half", tvHalfName)
                REQUEST_CODE_10MIN -> updateRingtoneName("uri_10min", tv10MinName)
                REQUEST_CODE_END -> updateRingtoneName("uri_end", tvEndName)
            }
        }
    }

    private fun previewSound(uriKey: String, volumeKey: String) {
        if (SoundSettingsHelper.getSoundUri(this, uriKey) == null) {
            showToast("未选择音频（或已设为无声）")
            return
        }
        SoundSettingsHelper.playSound(this, uriKey, volumeKey)
    }

    private fun createVolumeChangeListener(prefKey: String) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            prefs.edit().putInt(prefKey, progress).apply()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun saveUri(key: String, uri: Uri?) {
        // 存空串而不是 null：putString(key, null) 等价于 remove(key)，
        // 下次启动会被 initializeDefaultSounds 当成“没配过”重新写回默认铃声，
        // 导致“无声”这个选择存不住。
        prefs.edit().putString(key, uri?.toString() ?: "").apply()
    }

    private fun getUri(key: String): Uri? {
        val uriString = prefs.getString(key, null)
        return if (uriString.isNullOrEmpty()) null else Uri.parse(uriString)
    }

    private fun updateRingtoneName(prefKey: String, textView: TextView) {
        val uriString = prefs.getString(prefKey, null)
        when {
            uriString == null -> textView.text = "未选择"
            uriString.isEmpty() -> textView.text = "无声"
            else -> {
                val ringtone = RingtoneManager.getRingtone(this, Uri.parse(uriString))
                textView.text = ringtone?.getTitle(this) ?: "未知音频"
            }
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun getKeyForRequestCode(requestCode: Int): String {
        return when (requestCode) {
            REQUEST_CODE_HALF -> "uri_half"
            REQUEST_CODE_10MIN -> "uri_10min"
            REQUEST_CODE_END -> "uri_end"
            else -> ""
        }
    }
}
