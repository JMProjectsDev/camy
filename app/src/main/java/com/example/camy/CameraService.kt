package com.example.camy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.camy.utils.createMediaContentValues
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraService : Service(), LifecycleOwner {

    private val lifecycleRegistry: LifecycleRegistry by lazy { LifecycleRegistry(this) }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null

    private var isRecording = false
    private var isFlashOn = false

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var cameraExecutor: ExecutorService

    // Esto es para dividir el video en trozos de aprox 4GB
    private var chunkIndex = 0
    private var pendingSplit = false
    private val MAX_SIZE_4GB = 4L * 1024L * 1024L * 1024L

    private var pendingRotation = Surface.ROTATION_0

    object CameraServiceActions {
        const val ACTION_START_RECORDING = "com.example.camy.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.camy.action.STOP_RECORDING"
        const val ACTION_TOGGLE_FLASH = "com.example.camy.action.TOGGLE_FLASH"
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // WakeLock
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp:CameraWakelock")
        wakeLock?.acquire()

        // Executor
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        when (intent?.action) {
            "com.example.camy.ACTION_ROTATION_CHANGED" -> {

                val finalRotation =
                    when (intent.getIntExtra("VIDEO_ROTATION", Surface.ROTATION_0)) {
                        Surface.ROTATION_0 -> Surface.ROTATION_0
                        Surface.ROTATION_90 -> Surface.ROTATION_270
                        Surface.ROTATION_180 -> Surface.ROTATION_180
                        Surface.ROTATION_270 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    }

                pendingRotation = finalRotation
                videoCapture?.targetRotation = finalRotation
            }

            CameraServiceActions.ACTION_START_RECORDING -> {
                if (!isRecording) {
                    chunkIndex = 0
                    pendingSplit = false
                    startCameraAndRecording(pendingRotation)
                }
            }

            CameraServiceActions.ACTION_STOP_RECORDING -> {
                if (isRecording) {
                    stopRecordingVideo()
                }
                stopSelf()
            }

            CameraServiceActions.ACTION_TOGGLE_FLASH -> {
                toggleFlash()
            }

            else -> {}
        }

        return START_STICKY
    }

    private fun startCameraAndRecording(rotation: Int) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            // Preparamos un Recorder
            val recorder =
                Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
            videoCapture = VideoCapture.withOutput(recorder)

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, videoCapture, imageCapture
                )
                videoCapture?.targetRotation = rotation
                cameraControl = camera.cameraControl

                startRecordingVideo()
            } catch (e: Exception) {
                Log.e(TAG, "Error al abrir la cámara: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecordingVideo() {
        val prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE)
        val recordAudio = prefs.getBoolean("recordAudio", true)
        val storageChoice = prefs.getString("storageChoice", "internal") ?: "internal"

        // Crear ContentValues dinamicos
        val contentValues = createMediaContentValues("video", storageChoice)
        val videoUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val outputOptions = MediaStoreOutputOptions.Builder(contentResolver, videoUri)
            .setContentValues(contentValues).build()

        try {
            var recording = videoCapture?.output?.prepareRecording(this, outputOptions)

            if (recordAudio) {
                recording = recording?.withAudioEnabled()
            }
            activeRecording = recording?.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        Log.d(
                            TAG, "Grabación iniciada. audio=$recordAudio, storage=$storageChoice"
                        )
                    }

                    is VideoRecordEvent.Status -> {
                        val bytesSoFar = event.recordingStats.numBytesRecorded
                        checkFileSize(bytesSoFar)
                    }

                    is VideoRecordEvent.Finalize -> {
                        if (isRecording) {
                            isRecording = false
                        }
                        Log.d(TAG, "Grabación finalizada: ${event.outputResults.outputUri}")
                        if (pendingSplit) {
                            pendingSplit = false
                            startRecordingVideo()
                        }
                    }

                    else -> {}
                }
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException al grabar: ${se.message}", se)
        }
    }


    private fun stopRecordingVideo() {
        if (isRecording) {
            pendingSplit = false
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
        }
    }

    private fun checkFileSize(bytesSoFar: Long) {
        if (bytesSoFar >= MAX_SIZE_4GB && !pendingSplit) {
            Log.d(TAG, "Se alcanzó 4GB => forzar split")
            pendingSplit = true
            activeRecording?.stop()
            activeRecording = null
        }
    }

    private fun createNotification(): Notification {
        val channelId = "camera_service_channel"
        val channel = NotificationChannel(
            channelId,
            "Camera Service Channel",
            NotificationManager.IMPORTANCE_LOW // o IMPORTANCE_DEFAULT
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val builder =
            NotificationCompat.Builder(this, channelId).setOngoing(true) // Notificacion persistente
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        return builder.build()
    }

    private fun toggleFlash() {
        if (cameraControl == null) {
            Log.w(TAG, "toggleFlash: cameraControl es null")
            return
        }
        isFlashOn = !isFlashOn
        cameraControl?.enableTorch(isFlashOn)
        Log.d(TAG, "Flash -> $isFlashOn")
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()

        stopRecordingVideo()

        // Emitir broadcast de servicio detenido
        val bcIntent = Intent(ACTION_SERVICE_STOPPED)
        sendBroadcast(bcIntent)

        wakeLock?.release()
        wakeLock = null
        cameraExecutor.shutdown()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopRecordingVideo()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 123
        const val ACTION_SERVICE_STOPPED = "com.example.camy.ACTION_SERVICE_STOPPED"
    }
}
