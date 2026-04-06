package io.aether.wrapper

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class FramePipelineService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val engine = AetherCascadeEngine()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var lastProcessTime = 0L
    @Volatile private var lastDelta = 0.0

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "AETHER_FRAME_PIPELINE"
        private const val FRAME_THROTTLE_MS = 500L
        
        // Statische Variablen entfernt - Token wird per Intent übergeben
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aether Frame-Pipeline")
            .setContentText("Echtzeit-Analyse der Datenströme (RGBA Raw)...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra("result_code", 0) ?: 0
        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("projection_data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("projection_data")
        }

        if (resultCode != 0 && projectionData != null) {
            startProjection(resultCode, projectionData)
        }
        
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AetherScan", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessTime < FRAME_THROTTLE_MS) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastProcessTime = currentTime

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rawSize = buffer.remaining()

                // Subsample: max 8192 Bytes für die Cascade-Analyse.
                // WICHTIG: Buffer VOR image.close() lesen – danach ist der
                // Backing-Speicher freigegeben und der Buffer ungültig.
                val sampleSize = 8192
                val step = maxOf(1, rawSize / sampleSize)
                val sample = ByteArray(minOf(sampleSize, rawSize / step)) { i ->
                    buffer.get(i * step)
                }
                image.close()

                val capturedDelta = lastDelta
                serviceScope.launch(Dispatchers.Default) {
                    val metricsResult = engine.cascade(sample, capturedDelta)
                    lastDelta = metricsResult.deltaConvergence
                    sendMetricsBroadcast(metricsResult)
                }
            } catch (_: Exception) {
                image.close()
            }
        }, null)
    }

    private fun sendMetricsBroadcast(m: AetherCascadeEngine.Metrics) {
        val intent = Intent("AETHER_METRICS_UPDATE").apply {
            putExtra("entropy",          m.entropy)
            putExtra("boltzmann",        m.boltzmann)
            putExtra("zipf",             m.zipf)
            putExtra("benford",          m.benford)
            putExtra("fourier",          m.fourier)
            putExtra("katz",             m.katz)
            putExtra("permEntropy",      m.permEntropy)
            putExtra("deltaConvergence", m.deltaConvergence)
            putExtra("noether",          m.noetherConsistency)
            putExtra("trust",            m.trustScore)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, "Aether Scan Channel", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        sendBroadcast(Intent("AETHER_SERVICE_STOPPED"))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
