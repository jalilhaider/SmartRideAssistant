package com.smartride.assistant.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.smartride.assistant.MainActivity
import com.smartride.assistant.R
import com.smartride.assistant.SmartRideApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the MediaProjection session. Lifecycle rules followed here:
 *  - started only from the foreground UI right after the user accepted the system capture dialog;
 *  - startForeground() is called BEFORE getMediaProjection() (required on Android 14+);
 *  - MediaProjection.Callback is registered BEFORE createVirtualDisplay() (required on Android 14+);
 *  - the consent token is single-use: when capture stops for any reason the service stops and the user must
 *    press Start Monitoring again. It never silently restarts and never reuses an old consent;
 *  - START_NOT_STICKY and no boot receiver: nothing restarts after a reboot or a kill.
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var scope: CoroutineScope? = null
    private var frames: Channel<Bitmap>? = null
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile private var lastSampleAt = 0L
    @Volatile private var intervalMs = 1_000L
    @Volatile private var active = false

    private var baseIntervalMs = 1_000L
    private val diff = FrameDiff(threshold = 2.0)
    private var irrelevantStreak = 0
    private var lastOcrAt = 0L
    private var analysed = 0
    private var skipped = 0
    private var detected = 0

    private val container get() = (application as SmartRideApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMonitoring("Stopped by you.")
            ACTION_START -> if (!active) startMonitoring(intent) else Unit
            else -> if (!active) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---- start ---------------------------------------------------------------------------------

    private fun startMonitoring(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val consent = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || consent == null) {
            fail("Screen-capture consent missing. Press Start Monitoring and accept the system dialog.")
            return
        }

        try {
            enterForeground()
        } catch (e: Exception) {
            fail("Android refused to start the foreground service: ${e.message}")
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            manager.getMediaProjection(resultCode, consent)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            fail("Android did not accept the capture consent. Start monitoring again.")
            return
        }
        projection = p
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                // Android or the user (status-bar chip) ended capture. We do NOT restart: new consent is required.
                mainHandler.post { stopMonitoring("Capture was ended by Android or by you. Press Start Monitoring to grant consent again.") }
            }
        }
        projectionCallback = cb
        p.registerCallback(cb, mainHandler)

        try {
            setupCapture(p)
        } catch (e: Exception) {
            stopMonitoring("Could not start screen capture: ${e.message}")
            return
        }

        container.coordinator.resetSession()
        container.location.start()
        baseIntervalMs = 1_000L
        intervalMs = baseIntervalMs
        active = true
        analysed = 0; skipped = 0; detected = 0; irrelevantStreak = 0; lastOcrAt = 0L
        diff.reset()
        MonitorBus.results.value = emptyList()
        MonitorBus.update {
            MonitorUiState(running = true, captureStatus = "Capturing", ocrStatus = "Waiting for a ride screen",
                note = if (container.location.hasPermission()) null else "Location permission missing: rides will be NEEDS REVIEW.")
        }
        startWorker()
        // pick up a changed sampling interval from Settings
        scope?.launch { baseIntervalMs = container.settingsRepo.current().sampleIntervalMs.coerceIn(500L, 5_000L); intervalMs = baseIntervalMs }
    }

    private fun enterForeground() {
        createChannel()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (container.location.hasPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun setupCapture(p: MediaProjection) {
        val wm = getSystemService(WindowManager::class.java)
        val fullW: Int
        val fullH: Int
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            fullW = b.width(); fullH = b.height()
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(size)
            fullW = size.x; fullH = size.y
        }
        val scale = min(1f, MAX_CAPTURE_WIDTH / fullW.toFloat())
        val w = max(2, (fullW * scale).toInt())
        val h = max(2, (fullH * scale).toInt())
        val dpi = resources.displayMetrics.densityDpi

        val thread = HandlerThread("SmartRideImages").also { it.start() }
        imageThread = thread
        val handler = Handler(thread.looper)

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ onImage(it) }, handler)

        virtualDisplay = p.createVirtualDisplay(
            "SmartRideCapture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
    }

    // ---- frame pipeline ------------------------------------------------------------------------

    private fun startWorker() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val ch = Channel<Bitmap>(capacity = Channel.CONFLATED, onUndeliveredElement = { it.recycle() })
        frames = ch
        s.launch {
            for (bmp in ch) {
                try {
                    handleFrame(bmp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MonitorBus.update { it.copy(ocrStatus = "OCR error: ${e.javaClass.simpleName}") }
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /** Runs on the image thread: throttle, copy the frame, hand it to the worker. */
    private fun onImage(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (!active || now - lastSampleAt < intervalMs) return
            lastSampleAt = now
            val bmp = imageToBitmap(image)
            if (frames?.trySend(bmp)?.isSuccess != true) bmp.recycle()
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
    }

    private suspend fun handleFrame(bmp: Bitmap) {
        // 1) cheap change detection on a tiny copy; skip OCR if the screen did not change
        val small = Bitmap.createScaledBitmap(bmp, DIFF_W, DIFF_H, true)
        val px = IntArray(DIFF_W * DIFF_H)
        small.getPixels(px, 0, DIFF_W, 0, 0, DIFF_W, DIFF_H)
        small.recycle()
        val now = SystemClock.elapsedRealtime()
        val stale = irrelevantStreak == 0 && lastOcrAt != 0L && now - lastOcrAt > REFRESH_MS
        if (!diff.hasChanged(px) && !stale) {
            skipped++
            MonitorBus.update { it.copy(framesSkipped = skipped) }
            return
        }

        // 2) OCR + analysis
        MonitorBus.update { it.copy(ocrStatus = "Reading screen...") }
        val outcome = container.coordinator.processBitmap(bmp)
        lastOcrAt = SystemClock.elapsedRealtime()
        analysed++

        if (outcome.relevant) {
            irrelevantStreak = 0
            intervalMs = baseIntervalMs
            detected += outcome.newOffers
        } else {
            irrelevantStreak++
            // back off on screens that are not ride offers: fewer wake-ups, less battery
            if (irrelevantStreak > 5) intervalMs = max(baseIntervalMs, 3_000L)
            MonitorBus.results.value = emptyList()
        }
        MonitorBus.update {
            it.copy(
                ocrStatus = if (outcome.relevant) "Ride screen detected (${outcome.analyses.size} offer(s))" else "Waiting for a ride screen",
                framesAnalysed = analysed, framesSkipped = skipped, detectedRides = detected,
                lastAnalysisAt = System.currentTimeMillis(), lastOcrMs = outcome.ocrMs,
            )
        }
    }

    // ---- stop ----------------------------------------------------------------------------------

    @Synchronized
    private fun stopMonitoring(reason: String) {
        val wasActive = active
        active = false
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projectionCallback = null
        try { virtualDisplay?.release() } catch (e: Exception) { /* already released */ }
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null); imageReader?.close() } catch (e: Exception) { /* closed */ }
        imageReader = null
        try { projection?.stop() } catch (e: Exception) { /* already stopped */ }
        projection = null
        frames?.close(); frames = null
        scope?.cancel(); scope = null
        imageThread?.quitSafely(); imageThread = null
        container.location.stop()
        MonitorBus.update {
            it.copy(running = false, captureStatus = "Stopped", ocrStatus = "Idle", note = if (wasActive) reason else it.note)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        MonitorBus.update { it.copy(running = false, captureStatus = "Stopped", ocrStatus = "Idle", note = message) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (active) stopMonitoring("Service was stopped by Android.")
        super.onDestroy()
    }

    // ---- notification --------------------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.smartride.assistant.START"
        const val ACTION_STOP = "com.smartride.assistant.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "consent_data"
        private const val CHANNEL_ID = "live_analysis"
        private const val NOTIF_ID = 1001
        private const val MAX_CAPTURE_WIDTH = 1080f
        private const val DIFF_W = 72
        private const val DIFF_H = 128
        private const val REFRESH_MS = 30_000L
    }
}
