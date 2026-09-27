package com.tomoya.rsvpreader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
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
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class CaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

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
        when (intent?.action) {
            ACTION_CAPTURE_ONCE -> {
                if (projection != null) captureCurrentScreen()
                else sendOcrResult(null, "OCR")
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Int.MIN_VALUE || resultData == null) {
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
                    teardownProjection()
                    stopSelf()
                }
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            3
        )
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
    }

    private fun readScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi
    }

    private fun captureCurrentScreen() {
        mainHandler.postDelayed({ acquireImageWithRetry(0) }, 140)
    }

    private fun acquireImageWithRetry(attempt: Int) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            if (attempt < 5) {
                mainHandler.postDelayed(
                    { acquireImageWithRetry(attempt + 1) },
                    80
                )
            } else {
                sendOcrResult(null, "OCR")
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
            val padded = Bitmap.createBitmap(
                paddedWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(
                padded,
                0,
                0,
                screenWidth,
                screenHeight
            )
            if (cropped !== padded) padded.recycle()
            cropped
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            sendOcrResult(null, "OCR")
            return
        }

        val prepared = prepareForOcr(bitmap)
        val apiKey = getSharedPreferences(
            MainActivity.CLOUD_PREFS,
            MODE_PRIVATE
        ).getString(MainActivity.PREF_API_KEY, "")
            .orEmpty()
            .trim()

        if (apiKey.isNotBlank()) {
            runCloudVision(
                bitmap = prepared,
                apiKey = apiKey,
                onSuccess = { text ->
                    releaseBitmaps(bitmap, prepared)
                    sendOcrResult(text, "Cloud Vision", null)
                },
                onFailure = { error ->
                    runMlKit(bitmap, prepared, error)
                }
            )
        } else {
            runMlKit(bitmap, prepared)
        }
    }

    private fun runCloudVision(
        bitmap: Bitmap,
        apiKey: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        networkExecutor.execute {
            try {
                val encoded = encodeJpegBase64(bitmap)
                val request = JSONObject().apply {
                    put(
                        "requests",
                        JSONArray().put(
                            JSONObject().apply {
                                put(
                                    "image",
                                    JSONObject().put("content", encoded)
                                )
                                put(
                                    "features",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "type",
                                            "DOCUMENT_TEXT_DETECTION"
                                        )
                                    )
                                )
                                // Let Vision auto-detect the language.
                            }
                        )
                    )
                }

                val connection = (
                    URL(
                        "https://vision.googleapis.com/v1/images:annotate"
                    ).openConnection() as HttpURLConnection
                ).apply {
                    requestMethod = "POST"
                    connectTimeout = CLOUD_CONNECT_TIMEOUT_MS
                    readTimeout = CLOUD_READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/json; charset=utf-8"
                    )
                    setRequestProperty(
                        "x-goog-api-key",
                        apiKey
                    )
                }

                try {
                    connection.outputStream.use { stream ->
                        stream.write(
                            request.toString()
                                .toByteArray(StandardCharsets.UTF_8)
                        )
                    }

                    val status = connection.responseCode
                    val responseText = (
                        if (status in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }
                    )?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()

                    if (status !in 200..299) {
                        throw IllegalStateException(
                            parseVisionError(
                                status,
                                responseText
                            )
                        )
                    }

                    val responseError = parseVisionEmbeddedError(
                        responseText
                    )
                    if (responseError != null) {
                        throw IllegalStateException(responseError)
                    }

                    val text = parseCloudVisionText(responseText)
                    if (text.isBlank()) {
                        throw IllegalStateException(
                            "Cloud Vision returned no text"
                        )
                    }

                    mainHandler.post {
                        onSuccess(
                            TextChunker.normalizeSource(text)
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (t: Throwable) {
                val message = t.message
                    ?.take(220)
                    ?.ifBlank { null }
                    ?: t.javaClass.simpleName
                mainHandler.post {
                    onFailure(message)
                }
            }
        }
    }

    private fun parseVisionEmbeddedError(
        responseText: String
    ): String? {
        return runCatching {
            val error = JSONObject(responseText)
                .optJSONArray("responses")
                ?.optJSONObject(0)
                ?.optJSONObject("error")
                ?: return@runCatching null

            val code = error.optInt("code")
            val status = error.optString("status")
            val message = error.optString("message")

            buildString {
                append("Vision ")
                if (code > 0) append(code)
                if (status.isNotBlank()) {
                    if (code > 0) append(" ")
                    append(status)
                }
                if (message.isNotBlank()) {
                    append(": ")
                    append(message)
                }
            }.take(220)
        }.getOrNull()
    }

    private fun parseVisionError(
        httpStatus: Int,
        responseText: String
    ): String {
        val apiMessage = runCatching {
            val error = JSONObject(responseText)
                .optJSONObject("error")
            val status = error?.optString("status").orEmpty()
            val message = error?.optString("message").orEmpty()
            buildString {
                append("Vision HTTP ")
                append(httpStatus)
                if (status.isNotBlank()) {
                    append(" ")
                    append(status)
                }
                if (message.isNotBlank()) {
                    append(": ")
                    append(message)
                }
            }
        }.getOrNull()

        return apiMessage
            ?.takeIf { it.length > "Vision HTTP $httpStatus".length }
            ?.take(220)
            ?: "Vision HTTP $httpStatus"
    }

    private fun parseCloudVisionText(responseText: String): String {
        val root = JSONObject(responseText)
        val response = root.optJSONArray("responses")
            ?.optJSONObject(0)
            ?: return ""

        if (response.has("error")) return ""

        val fullText = response
            .optJSONObject("fullTextAnnotation")
            ?.optString("text")
            .orEmpty()

        if (fullText.isNotBlank()) return fullText

        return response
            .optJSONArray("textAnnotations")
            ?.optJSONObject(0)
            ?.optString("description")
            .orEmpty()
    }

    private fun encodeJpegBase64(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            CLOUD_JPEG_QUALITY,
            output
        )
        return Base64.encodeToString(
            output.toByteArray(),
            Base64.NO_WRAP
        )
    }

    private fun runMlKit(
        original: Bitmap,
        prepared: Bitmap,
        visionError: String? = null
    ) {
        recognizer.process(
            InputImage.fromBitmap(prepared, 0)
        )
            .addOnSuccessListener { result ->
                sendOcrResult(
                    extractReadingText(
                        result = result,
                        imageWidth = prepared.width,
                        imageHeight = prepared.height
                    ),
                    "ML Kit",
                    visionError
                )
            }
            .addOnFailureListener {
                sendOcrResult(null, "ML Kit", visionError)
            }
            .addOnCompleteListener {
                releaseBitmaps(original, prepared)
            }
    }

    private fun releaseBitmaps(
        original: Bitmap,
        prepared: Bitmap
    ) {
        if (prepared !== original && !prepared.isRecycled) {
            prepared.recycle()
        }
        if (!original.isRecycled) {
            original.recycle()
        }
    }

    private fun prepareForOcr(source: Bitmap): Bitmap {
        val top = (source.height * 0.035f).toInt()
        val bottom = (source.height * 0.965f).toInt()
        val cropHeight = (bottom - top).coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(
            source,
            0,
            top.coerceAtLeast(0),
            source.width,
            cropHeight.coerceAtMost(
                source.height - top.coerceAtLeast(0)
            )
        )

        val scale = minOf(
            1.6f,
            1800f / cropped.width.toFloat()
        ).coerceAtLeast(1f)

        if (scale <= 1.01f) return cropped

        val scaled = Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).toInt(),
            (cropped.height * scale).toInt(),
            true
        )
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    private fun extractReadingText(
        result: Text,
        imageWidth: Int,
        imageHeight: Int
    ): String {
        data class Item(
            val text: String,
            val box: Rect
        )

        val topCut = (imageHeight * 0.015f).toInt()
        val bottomCut = (imageHeight * 0.985f).toInt()

        val items = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox
                    ?: return@mapNotNull null
                val value = TextChunker.normalizeSource(
                    line.text
                )
                if (value.isBlank()) {
                    return@mapNotNull null
                }
                if (
                    box.bottom < topCut ||
                    box.top > bottomCut
                ) {
                    return@mapNotNull null
                }
                if (
                    box.right < 0 ||
                    box.left > imageWidth
                ) {
                    return@mapNotNull null
                }
                Item(value, box)
            }

        if (items.isEmpty()) {
            return TextChunker.normalizeSource(
                result.text
            )
        }

        val verticalRatio = items.count {
            it.box.height() >
                it.box.width() * 1.25f
        }.toFloat() / items.size

        val ordered = if (verticalRatio >= 0.45f) {
            items.sortedWith(
                compareByDescending<Item> {
                    it.box.centerX()
                }.thenBy {
                    it.box.top
                }
            )
        } else {
            items.sortedWith(
                compareBy<Item> {
                    it.box.top
                }.thenBy {
                    it.box.left
                }
            )
        }

        return TextChunker.stitchFragments(
            ordered.map { it.text }
        )
    }

    private fun sendOcrResult(
        text: String?,
        source: String,
        error: String? = null
    ) {
        sendBroadcast(
            Intent(ACTION_OCR_RESULT).apply {
                setPackage(packageName)
                putExtra(EXTRA_OCR_TEXT, text)
                putExtra(EXTRA_OCR_SOURCE, source)
                putExtra(EXTRA_OCR_ERROR, error)
            }
        )
    }

    private fun buildNotification(): android.app.Notification {
        val launchIntent = Intent(
            this,
            MainActivity::class.java
        )
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )
        return android.app.Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "RSVP Reader OCR待機中"
            )
            .setContentText(
                "Cloud Vision優先・ML Kitフォールバック"
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_camera
            )
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "RSVP Reader OCR",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
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
        teardownProjection()
        recognizer.close()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_CAPTURE_ONCE =
            "com.tomoya.rsvpreader.CAPTURE_ONCE"
        const val ACTION_OCR_RESULT =
            "com.tomoya.rsvpreader.OCR_RESULT"
        const val EXTRA_OCR_TEXT = "ocr_text"
        const val EXTRA_OCR_SOURCE = "ocr_source"
        const val EXTRA_OCR_ERROR = "ocr_error"

        private const val CHANNEL_ID = "rsvp_capture"
        private const val NOTIFICATION_ID = 4107
        private const val CLOUD_JPEG_QUALITY = 88
        private const val CLOUD_CONNECT_TIMEOUT_MS = 8_000
        private const val CLOUD_READ_TIMEOUT_MS = 15_000
    }
}
