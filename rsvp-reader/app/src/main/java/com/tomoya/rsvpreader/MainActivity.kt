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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var cloudKeyInput: EditText
    private lateinit var geminiKeyInput: EditText
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

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(34), dp(28), dp(28))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "RSVP Reader"
            textSize = 32f
            setTextColor(Color.rgb(20, 25, 35))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "本文直接取得を優先し、OCR時はCloud Vision＋Gemini画像照合で誤字を補正します。"
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

        cloudKeyInput = EditText(this).apply {
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
        root.addView(cloudKeyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(6)
        })

        root.addView(Button(this).apply {
            text = "2. Cloud Vision APIキーを保存"
            textSize = 15f
            setOnClickListener {
                val key = cloudKeyInput.text.toString().trim()
                getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_API_KEY, key)
                    .apply()
                refreshStatus()
                Toast.makeText(
                    this@MainActivity,
                    if (key.isBlank()) "Cloud Vision APIキーを削除しました" else "Cloud Vision APIキーを保存しました",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            bottomMargin = dp(10)
        })

        geminiKeyInput = EditText(this).apply {
            hint = "Gemini APIキー（AI Studio）"
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(
                getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE)
                    .getString(PREF_GEMINI_API_KEY, "")
                    .orEmpty()
            )
        }
        root.addView(geminiKeyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(6)
        })

        root.addView(Button(this).apply {
            text = "3. Gemini APIキーを保存"
            textSize = 15f
            setOnClickListener {
                val key = geminiKeyInput.text.toString().trim()
                getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_GEMINI_API_KEY, key)
                    .apply()
                refreshStatus()
                Toast.makeText(
                    this@MainActivity,
                    if (key.isBlank()) "Gemini APIキーを削除しました" else "Gemini APIキーを保存しました",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            bottomMargin = dp(12)
        })

        root.addView(Button(this).apply {
            text = "4. 画面キャプチャを開始"
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
            text = "Geminiは元画像とOCR結果を照合し、誤字だけを補正します。補正結果が原文から変わりすぎた場合は自動で破棄します。"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        })

        return scroll
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE)
        val accessibilityOk = isReaderAccessibilityEnabled()
        val hasCloudKey = prefs.getString(PREF_API_KEY, "").orEmpty().isNotBlank()
        val hasGeminiKey = prefs.getString(PREF_GEMINI_API_KEY, "").orEmpty().isNotBlank()

        status.text = buildString {
            append(if (accessibilityOk) "✓ 本文直接取得：有効" else "△ 本文直接取得：未設定")
            append("\n")
            append(if (hasCloudKey) "✓ Cloud Vision：設定済み" else "△ Cloud Vision：APIキー未設定")
            append("\n")
            append(if (hasGeminiKey) "✓ Gemini画像照合：設定済み" else "△ Gemini画像照合：APIキー未設定")
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
        const val PREF_GEMINI_API_KEY = "gemini_api_key"
        private const val REQUEST_CAPTURE = 1001
        private const val REQUEST_NOTIFICATION = 1002
    }
}
