package com.tomoya.rsvpreader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
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

        val title = TextView(this).apply {
            text = "RSVP Reader"
            textSize = 32f
            setTextColor(Color.rgb(20, 25, 35))
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val subtitle = TextView(this).apply {
            text = "いま画面に見えている文章をOCRし、\nその場で速読表示します。"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        }
        root.addView(subtitle)

        val overlayButton = Button(this).apply {
            text = "1. 画面上への表示を許可"
            textSize = 16f
            setOnClickListener { openOverlayPermission() }
        }
        root.addView(overlayButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(14)
        })

        val captureButton = Button(this).apply {
            text = "2. 画面キャプチャを開始"
            textSize = 16f
            setOnClickListener { beginProjectionRequest() }
        }
        root.addView(captureButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(22)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(55, 60, 70))
            gravity = Gravity.CENTER
        }
        root.addView(status)

        val help = TextView(this).apply {
            text = "開始後はKindleなどへ戻り、画面右端の ▶ を押してください。\nOCRは端末内で処理します。"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(help)

        return root
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        status.text = if (overlayOk) {
            "✓ フローティング表示の準備完了"
        } else {
            "先に「画面上への表示」を許可してください"
        }
    }

    private fun openOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "すでに許可されています", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun beginProjectionRequest() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先に画面上への表示を許可してください", Toast.LENGTH_LONG).show()
            openOverlayPermission()
            return
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "画面キャプチャが許可されませんでした", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(serviceIntent)
        status.text = "✓ 実行中。Kindleへ戻って ▶ を押してください"
        Toast.makeText(this, "RSVP Readerを開始しました", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        }
    }

    companion object {
        private const val REQUEST_CAPTURE = 1001
        private const val REQUEST_NOTIFICATION = 1002
    }
}
