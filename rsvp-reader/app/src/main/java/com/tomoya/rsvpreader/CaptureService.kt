package com.tomoya.rsvpreader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max

class CaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bubble: TextView? = null
    private var readerOverlay: View? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "画面上への表示権限が必要です", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        teardownProjection()
        readScreenMetrics()

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    removeAllOverlays()
                    teardownProjection()
                    stopSelf()
                }
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        virtualDisplay = projection?.createVirtualDisplay(
            "RSVPReaderCapture",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
        showFloatingBubble()
    }

    private fun readScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi
    }

    private fun showFloatingBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }

        val size = dp(58)
        val view = TextView(this).apply {
            text = "▶"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(22, 28, 38))
            alpha = 0.94f
            elevation = dp(8).toFloat()
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(250)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragged = true
                    params.x = max(0, startX - dx)
                    params.y = max(0, startY + dy)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) captureCurrentScreen()
                    true
                }
                else -> false
            }
        }

        bubble = view
        windowManager.addView(view, params)
    }

    private fun captureCurrentScreen() {
        if (readerOverlay != null) return
        val b = bubble ?: return
        b.visibility = View.INVISIBLE
        mainHandler.postDelayed({ acquireImageWithRetry(0) }, 180)
    }

    private fun acquireImageWithRetry(attempt: Int) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            if (attempt < 4) {
                mainHandler.postDelayed({ acquireImageWithRetry(attempt + 1) }, 90)
            } else {
                bubble?.visibility = View.VISIBLE
                Toast.makeText(this, "画面を取得できませんでした。もう一度押してください", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val bitmap = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
            if (cropped !== padded) padded.recycle()
            cropped
        } catch (t: Throwable) {
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            bubble?.visibility = View.VISIBLE
            Toast.makeText(this, "画像化に失敗しました", Toast.LENGTH_SHORT).show()
            return
        }

        recognize(bitmap)
    }

    private fun recognize(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = extractReadingText(result)
                val chunks = TextChunker.chunk(text)
                if (chunks.isEmpty()) {
                    bubble?.visibility = View.VISIBLE
                    Toast.makeText(this, "読める文章が見つかりませんでした", Toast.LENGTH_LONG).show()
                } else {
                    showReader(chunks)
                }
            }
            .addOnFailureListener {
                bubble?.visibility = View.VISIBLE
                Toast.makeText(this, "OCRに失敗しました", Toast.LENGTH_LONG).show()
            }
            .addOnCompleteListener {
                bitmap.recycle()
            }
    }

    private fun extractReadingText(result: Text): String {
        data class Item(val text: String, val box: Rect)

        val topCut = (screenHeight * 0.06f).toInt()
        val bottomCut = (screenHeight * 0.94f).toInt()

        val items = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text.trim()
                if (text.isBlank()) return@mapNotNull null
                if (box.bottom < topCut || box.top > bottomCut) return@mapNotNull null
                Item(text, box)
            }

        if (items.isEmpty()) return result.text

        val verticalRatio = items.count { it.box.height() > it.box.width() * 1.25f }.toFloat() / items.size
        val ordered = if (verticalRatio >= 0.45f) {
            items.sortedWith(compareByDescending<Item> { it.box.centerX() }.thenBy { it.box.top })
        } else {
            items.sortedWith(compareBy<Item> { it.box.top }.thenBy { it.box.left })
        }

        return ordered.joinToString(separator = "") { it.text }
    }

    private fun showReader(chunks: List<String>) {
        bubble?.visibility = View.GONE

        val prefs = getSharedPreferences("reader", Context.MODE_PRIVATE)
        var speed = prefs.getInt("chars_per_minute", 700).coerceIn(200, 1400)
        var index = 0
        var playing = true

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(238, 8, 10, 14))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        val progress = TextView(this).apply {
            setTextColor(Color.rgb(155, 165, 180))
            textSize = 14f
            gravity = Gravity.CENTER
        }

        val word = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 48f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(60), dp(12), dp(60))
        }

        val speedLabel = TextView(this).apply {
            setTextColor(Color.rgb(190, 198, 210))
            textSize = 14f
            gravity = Gravity.CENTER
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun control(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 16f
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { action() }
        }

        lateinit var tick: Runnable

        fun refreshLabels() {
            index = index.coerceIn(0, chunks.lastIndex)
            word.text = chunks[index]
            progress.text = "${index + 1} / ${chunks.size}"
            speedLabel.text = "$speed 文字/分  ·  タップで一時停止"
        }

        fun scheduleNext() {
            mainHandler.removeCallbacks(tick)
            if (!playing || index >= chunks.lastIndex) return
            val current = chunks[index]
            val base = (60_000.0 * current.length.coerceAtLeast(2) / speed).toLong()
            val punctuationBonus = when {
                current.endsWith('。') || current.endsWith('！') || current.endsWith('？') -> 260L
                current.endsWith('、') -> 120L
                else -> 0L
            }
            mainHandler.postDelayed(tick, (base + punctuationBonus).coerceIn(130L, 1800L))
        }

        tick = Runnable {
            if (playing) {
                if (index < chunks.lastIndex) {
                    index++
                    refreshLabels()
                    scheduleNext()
                } else {
                    playing = false
                    speedLabel.text = "$speed 文字/分  ·  読了"
                }
            }
        }

        word.setOnClickListener {
            playing = !playing
            refreshLabels()
            if (playing) scheduleNext() else mainHandler.removeCallbacks(tick)
        }

        val slower = control("−") {
            speed = (speed - 100).coerceAtLeast(200)
            prefs.edit().putInt("chars_per_minute", speed).apply()
            refreshLabels()
            scheduleNext()
        }
        val prev = control("◀") {
            index = (index - 1).coerceAtLeast(0)
            refreshLabels()
            scheduleNext()
        }
        val playPause = control("⏯") {
            playing = !playing
            refreshLabels()
            if (playing) scheduleNext() else mainHandler.removeCallbacks(tick)
        }
        val next = control("▶") {
            index = (index + 1).coerceAtMost(chunks.lastIndex)
            refreshLabels()
            scheduleNext()
        }
        val faster = control("＋") {
            speed = (speed + 100).coerceAtMost(1400)
            prefs.edit().putInt("chars_per_minute", speed).apply()
            refreshLabels()
            scheduleNext()
        }
        val close = control("×") {
            mainHandler.removeCallbacks(tick)
            hideReader()
        }

        listOf(slower, prev, playPause, next, faster, close).forEach { button ->
            controls.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }

        content.addView(progress, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        content.addView(word, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(speedLabel, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
        content.addView(controls, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        readerOverlay = root
        windowManager.addView(root, params)
        refreshLabels()
        scheduleNext()
    }

    private fun hideReader() {
        readerOverlay?.let { runCatching { windowManager.removeView(it) } }
        readerOverlay = null
        bubble?.visibility = View.VISIBLE
    }

    private fun buildNotification(): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RSVP Reader 実行中")
            .setContentText("画面端の ▶ でOCR速読を開始")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RSVP Reader", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun removeAllOverlays() {
        readerOverlay?.let { runCatching { windowManager.removeView(it) } }
        readerOverlay = null
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
    }

    private fun teardownProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        removeAllOverlays()
        teardownProjection()
        recognizer.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "rsvp_capture"
        private const val NOTIFICATION_ID = 4107
    }
}
