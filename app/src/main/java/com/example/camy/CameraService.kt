package com.example.camy

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.Recording
import androidx.camera.video.QualitySelector
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraService : Service(), LifecycleOwner {

    // Instancia del LifecycleOwner “manual”
    private val serviceLifecycleOwner = ServiceLifecycleOwner()

    // Variables de CameraX
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null

    // Wakelock para evitar que el CPU duerma
    private var wakeLock: PowerManager.WakeLock? = null

    // Thread pool para CameraX (opcional si necesitas control explícito)
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate() {
        super.onCreate()
        // 1) El Lifecycle pasa a CREARED
        serviceLifecycleOwner.handleOnCreate()

        // 2) Iniciamos el Foreground Service con notificación
        val notification = createNotification() // Implementar tu notificación
        startForeground(NOTIFICATION_ID, notification)

        // 3) WakeLock parcial
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp:CameraWakelock")
        wakeLock?.acquire()

        // 4) Crear un executor para la cámara (opcional)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 5) Aquí o en onStartCommand podemos iniciar la cámara
        startCameraAndRecording()
    }

    /**
     * Se llama cuando se inicia el servicio con startService(...) o startForegroundService(...).
     * Podemos mover la parte de iniciar la cámara aquí si quieres un flujo más lineal.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // El Lifecycle pasa a STARTED
        serviceLifecycleOwner.handleOnStart()
        // return START_STICKY si deseas que el servicio se reinicie al ser matado, etc.
        return START_STICKY
    }

    private fun startCameraAndRecording() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Si quieres ImageCapture
            imageCapture = ImageCapture.Builder().build()

            // Elegimos la cámara trasera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // (Opcional) no necesitas un Preview en un Service
            // val preview = Preview.Builder().build()

            try {
                cameraProvider.unbindAll()

                // Bind con EL PROPIO LifecycleOwner manual
                val camera = cameraProvider.bindToLifecycle(
                    serviceLifecycleOwner,
                    cameraSelector,
                    // preview,  // solo si quieres un preview (poco útil en un servicio)
                    videoCapture,
                    imageCapture
                )
                cameraControl = camera.cameraControl

                // Podrías empezar la grabación de video directamente
                // startRecordingVideo()
            } catch (e: Exception) {
                Log.e(TAG, "Error al abrir la cámara: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Ejemplo de cómo iniciar grabación:
    private fun startRecordingVideo() {
        // Podrías hacer algo parecido a tu Activity: con MediaStoreOutput, etc.
        // ...
    }

    // Para parar la grabación
    private fun stopRecordingVideo() {
        activeRecording?.stop()
        activeRecording = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // 1) Lifecycle pasa a DESTROYED
        serviceLifecycleOwner.handleOnDestroy()

        // 2) Parar grabación
        stopRecordingVideo()

        // 3) Liberar wakelock
        wakeLock?.release()
        wakeLock = null

        // 4) Apagar el executor
        cameraExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========== IMPLEMENTACIÓN DE LIFECYCLEOWNER MANUAL ==========
    override fun getLifecycle(): Lifecycle {
        return serviceLifecycleOwner.lifecycle
    }

    // ========== NOTIFICACIÓN DE FOREGROUND ==========

    private fun createNotification(): Notification {
        // 1) Si API >= 26, crear NotificationChannel
        // 2) Retornar el Notification con NotificationCompat.Builder
        TODO("Implementa tu notificación")
    }

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 123 // ID cualquiera para el foreground
    }
}
