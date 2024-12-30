package com.example.camy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraService : Service(), LifecycleOwner {

    private val serviceLifecycleOwner = ServiceLifecycleOwner()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null

    private var isRecording = false
    private var isFlashOn = false

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var cameraExecutor: ExecutorService

    object CameraServiceActions {
        const val ACTION_START_RECORDING = "com.example.camy.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.camy.action.STOP_RECORDING"
        const val ACTION_TOGGLE_FLASH = "com.example.camy.action.TOGGLE_FLASH"
        const val ACTION_CAPTURE_PHOTO = "com.example.camy.action.CAPTURE_PHOTO"
    }

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleOwner.handleOnCreate()

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // WakeLock
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp:CameraWakelock")
        wakeLock?.acquire()

        // Executor
        cameraExecutor = Executors.newSingleThreadExecutor()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceLifecycleOwner.handleOnStart()

        when (intent?.action) {
            CameraServiceActions.ACTION_START_RECORDING -> {
                if (!isRecording) {
                    startCameraAndRecording()
                }
            }
            CameraServiceActions.ACTION_STOP_RECORDING -> {
                if (isRecording) {
                    stopRecordingVideo()
                }
                // Podríamos parar el servicio
                stopSelf()
            }
            CameraServiceActions.ACTION_TOGGLE_FLASH -> {
                toggleFlash()
            }
            CameraServiceActions.ACTION_CAPTURE_PHOTO -> {
                capturePhoto()
            }
            else -> {
                // Si no hay acción, no hacemos nada o podríamos iniciar la cámara
            }
        }

        return START_STICKY
    }

    private fun startCameraAndRecording() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            // Preparamos un Recorder
            val recorder =
                Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Preparamos un ImageCapture (opcional)
            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                val camera = cameraProvider.bindToLifecycle(
                    serviceLifecycleOwner, cameraSelector, videoCapture, imageCapture
                )
                cameraControl = camera.cameraControl

                // Arrancamos la grabación
                startRecordingVideo()
            } catch (e: Exception) {
                Log.e(TAG, "Error al abrir la cámara: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecordingVideo() {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "video_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Videos")
            }
        }
        val videoUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val outputOptions = MediaStoreOutputOptions.Builder(contentResolver, videoUri)
            .setContentValues(contentValues).build()

        try {
            activeRecording =
                videoCapture?.output?.prepareRecording(this, outputOptions)?.withAudioEnabled()
                    ?.start(ContextCompat.getMainExecutor(this)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                isRecording = true
                                Log.d(TAG, "Grabación iniciada (Service)")
                            }
                            is VideoRecordEvent.Finalize -> {
                                isRecording = false
                                Log.d(TAG, "Grabación finalizada: ${event.outputResults.outputUri}")
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
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
        }
    }

    private fun capturePhoto() {
        if (imageCapture == null) return
        Log.d(TAG, "Capturando foto...")

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "photo_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Photos")
            }
        }
        val outputUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val outputOptions =
            ImageCapture.OutputFileOptions.Builder(contentResolver, outputUri, contentValues)
                .build()

        imageCapture?.takePicture(outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Foto guardada en: $outputUri")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Error al guardar foto: ${exception.message}", exception)
                }
            })
    }

    private fun createNotification(): Notification {
        val channelId = "camera_service_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Camera Service Channel",
                NotificationManager.IMPORTANCE_LOW // o IMPORTANCE_DEFAULT
            ).apply {
                // Control de visibilidad si quieres forzar lo público en lock screen
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Grabando video...")
            .setContentText("La aplicación está usando la cámara en segundo plano.")
            .setSmallIcon(R.drawable.ic_video)
            .setOngoing(true) // Notificación persistente
            .setPriority(NotificationCompat.PRIORITY_LOW) // Equivale a IMPORTANCE_LOW en Oreo-

            // Solo funciona en Android 5.0+ (Lollipop) y si lo permites:
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

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
        super.onDestroy()
        serviceLifecycleOwner.handleOnDestroy()

        stopRecordingVideo()

        // Emitir broadcast de “Service detenido”
        val bcIntent = Intent(ACTION_SERVICE_STOPPED)
        sendBroadcast(bcIntent)

        wakeLock?.release()
        wakeLock = null
        cameraExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun getLifecycle() = serviceLifecycleOwner.lifecycle

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 123
        const val ACTION_SERVICE_STOPPED = "com.example.camy.ACTION_SERVICE_STOPPED"
    }
}
