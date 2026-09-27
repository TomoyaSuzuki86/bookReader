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
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var projectionManager: MediaProjectionManager
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
            setPadding(dp(28), dp(48), dp(28), dp(28))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        root.addView(TextView(this).apply {
            text = "RSVP Reader"
            textSize = 32f
            setTextColor(Color.rgb(20, 25, 35))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "Kindleなどの本文を直接取得して速読します。\n取得できない画面だけOCRを使います。"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        })

        root.addView(Button(this).apply {
            text = "1. RSVP Readerを有効にする"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(14)
        })

        root.addView(Button(this).apply {
            text = "2. OCR補助を有効にする（推奨）"
            textSize = 16f
            setOnClickListener {
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(22)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(55, 60, 70))
            gravity = Gravity.CENTER
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "Kindleへ戻ると画面右端に ▶ が表示されます。\n本文はAccessibilityから直接取得し、読了すると自動で次ページへ進みます。"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })

        return root
    }

    private fun refreshStatus() {
        val accessibilityOk = isReaderAccessibilityEnabled()
        status.text = buildString {
            append(if (accessibilityOk) "✓ 本文直接取得：有効" else "△ 本文直接取得：未設定")
            append("\n")
            append(if (ocrReady) "✓ OCR補助：有効" else "OCR補助：この起動中は未設定")
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
            Toast.makeText(this, "OCR補助は有効になりませんでした", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(serviceIntent)
        ocrReady = true
        refreshStatus()
        Toast.makeText(this, "OCR補助を有効にしました", Toast.LENGTH_SHORT).show()
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
        private const val REQUEST_CAPTURE = 1001
        private const val REQUEST_NOTIFICATION = 1002
    }
}
