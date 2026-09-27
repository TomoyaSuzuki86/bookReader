package com.tomoya.rsvpreader

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var apiKeyInput: EditText
    private var ocrReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        setContentView(buildUi())
        requestNotificationPermissionIfNeeded()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun buildUi(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(40), dp(28), dp(28))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        root.addView(TextView(this).apply {
            text = "RSVP Reader"
            textSize = 32f
            setTextColor(Color.rgb(20, 25, 35))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "本文直接取得を優先し、取れない画面はCloud Vision OCRで読み取ります。\nRSVPは日本語を1語ずつ、文字数に応じた速度で表示します。"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(22))
        })

        root.addView(Button(this).apply {
            text = "1. RSVP Readerを有効にする"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(12)
        })

        apiKeyInput = EditText(this).apply {
            hint = "Google Cloud Vision APIキー"
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(
                getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE)
                    .getString(PREF_API_KEY, "")
                    .orEmpty()
            )
        }
        root.addView(apiKeyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(8)
        })

        root.addView(Button(this).apply {
            text = "2. Cloud Vision APIキーを保存"
            textSize = 15f
            setOnClickListener {
                val key = apiKeyInput.text.toString().trim()
                getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_API_KEY, key)
                    .apply()
                refreshStatus()
                Toast.makeText(
                    this@MainActivity,
                    if (key.isBlank()) "APIキーを削除しました" else "APIキーを保存しました",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            bottomMargin = dp(12)
        })

        root.addView(Button(this).apply {
            text = "3. 画面キャプチャを開始"
            textSize = 16f
            setOnClickListener {
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(18)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(55, 60, 70))
            gravity = Gravity.CENTER
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "Cloud Visionが失敗した場合だけ端末内ML Kitへ自動フォールバックします。\nKindleへ戻ると右端に ▶ が表示されます。"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        return root
    }

    private fun refreshStatus() {
        val accessibilityOk = isReaderAccessibilityEnabled()
        val hasCloudKey = getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE)
            .getString(PREF_API_KEY, "")
            .orEmpty()
            .isNotBlank()

        status.text = buildString {
            append(if (accessibilityOk) "✓ 本文直接取得：有効" else "△ 本文直接取得：未設定")
            append("\n")
            append(if (hasCloudKey) "✓ Cloud Vision：設定済み" else "△ Cloud Vision：APIキー未設定")
            append("\n")
            append(if (ocrReady) "✓ 画面キャプチャ：有効" else "画面キャプチャ：この起動中は未設定")
        }
    }

    private fun isReaderAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, ReaderAccessibilityService::class.java)
        val expected = component.flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "画面キャプチャは有効になりませんでした", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(serviceIntent)
        ocrReady = true
        refreshStatus()
        Toast.makeText(this, "画面キャプチャを開始しました", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        }
    }

    companion object {
        const val CLOUD_PREFS = "cloud_vision"
        const val PREF_API_KEY = "api_key"
        private const val REQUEST_CAPTURE = 1001
        private const val REQUEST_NOTIFICATION = 1002
    }
}
