package com.example.camy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.camy.utils.copyStream
import com.example.camy.utils.createMediaContentValues
import com.example.camy.utils.getOrCreateDefaultFolder
import com.example.camy.utils.getVideosVolumeUriForSd
import com.google.common.util.concurrent.ListenableFuture
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Volatile
private var broadcastSent = false

class CameraService : LifecycleService() {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var isRecording = false
    private var isFlashOn = false
    private var shouldStopSelfAfterFinalize = false

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

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp:CameraWakelock")
        wakeLock?.acquire()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

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
                    safeDisableTorch()
                    shouldStopSelfAfterFinalize = true
                    stopRecordingVideo()
                }
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
            // Obtener provider y soltar cualquier use case previo
            cameraProvider = cameraProviderFuture.get().also { prov ->
                try {
                    prov.unbindAll()
                } catch (e: Exception) {
                    Log.w(TAG, "unbindAll() lanzó excepción previa al bind: ${e.message}")
                }
            }

            val recorder = Recorder.Builder().setQualitySelector(
                QualitySelector.from(
                    Quality.FHD, FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
                )
            ).build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // IMPORTANTE: bindToLifecycle con este Service como LifecycleOwner
                val camera = cameraProvider?.bindToLifecycle(this, cameraSelector, videoCapture)
                cameraControl = camera?.cameraControl

                // Fijar rotación objetivo ANTES de iniciar la grabación
                videoCapture?.targetRotation = rotation

                Log.d(TAG, "CameraX vinculada en Service. targetRotation=$rotation")
                startRecordingVideo()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalState al bindear camera: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error al abrir la cámara: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecordingVideo() {
        val prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE)
        val recordAudio = prefs.getBoolean("recordAudio", true)
        val storageChoice = prefs.getString("storageChoice", "internal") ?: "internal"

        val contentValues = createMediaContentValues("video")
        val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (storageChoice == "external") getVideosVolumeUriForSd(this)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val outputOptions = MediaStoreOutputOptions.Builder(contentResolver, videoUri)
            .setContentValues(contentValues).build()

        try {
            val output = videoCapture?.output ?: run {
                Log.e(TAG, "videoCapture.output es null; ¿falló el bind?")
                return
            }

            var recording = output.prepareRecording(this, outputOptions)
            if (recordAudio) recording = recording.withAudioEnabled()

            activeRecording = recording.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        broadcastSent = false
                        Log.d(TAG, "Grabación iniciada. audio=$recordAudio, storage=$storageChoice")
                    }

                    is VideoRecordEvent.Status -> {
                        val bytesSoFar = event.recordingStats.numBytesRecorded
                        checkFileSize(bytesSoFar)
                    }

                    is VideoRecordEvent.Finalize -> {
                        if (isRecording) isRecording = false
                        val outUri = event.outputResults.outputUri
                        Log.d(TAG, "Grabación finalizada: $outUri (error=${event.error})")

                        if (storageChoice == "external" && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            // Solo PRE-Q: copiar a SD por SAF
                            copyVideoFromMediaStoreToSd(
                                outUri, "video/mp4", "video_${System.currentTimeMillis()}.mp4"
                            )
                        } else {
                            val where =
                                if (storageChoice == "external") "SD" else "almacenamiento interno"
                            Toast.makeText(this, "Vídeo guardado en $where", Toast.LENGTH_SHORT)
                                .show()
                        }

                        if (pendingSplit) {
                            pendingSplit = false
                            startRecordingVideo()
                        } else {
                            if (shouldStopSelfAfterFinalize) {
                                hardReleaseAndStopSelf()
                            }
                        }
                    }
                }
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException al grabar: ${se.message}", se)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalState en startRecordingVideo: ${e.message}", e)
        }
    }

    private fun hardReleaseAndStopSelf() {
        shouldStopSelfAfterFinalize = false
        safeDisableTorch()

        val provider = cameraProvider
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll() en hardRelease lanzó: ${e.message}")
        }
        videoCapture = null
        imageCapture = null
        cameraControl = null

        val provFuture = tryCallProviderShutdownReflectively(provider)
        if (provFuture != null) {
            attachCompletion(provFuture) {
                cameraProvider = null
                sendStoppedBroadcastOnce()
                stopSelf()
            }
            return
        }

        val camxFuture = tryCallCameraXShutdownReflectively()
        if (camxFuture != null) {
            attachCompletion(camxFuture) {
                cameraProvider = null
                sendStoppedBroadcastOnce()
                stopSelf()
            }
            return
        }

        cameraProvider = null
        sendStoppedBroadcastOnce()
        stopSelf()
    }

    private fun sendStoppedBroadcastOnce() {
        if (broadcastSent) return
        broadcastSent = true
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
    }

    private fun tryCallProviderShutdownReflectively(provider: ProcessCameraProvider?): ListenableFuture<*>? {
        if (provider == null) return null
        return try {
            val m =
                provider.javaClass.methods.firstOrNull { it.name == "shutdown" && it.parameterCount == 0 }
            val future = m?.invoke(provider)
            future as? ListenableFuture<*>
        } catch (e: Throwable) {
            Log.w(TAG, "provider.shutdown() no disponible: ${e.message}")
            null
        }
    }

    private fun tryCallCameraXShutdownReflectively(): ListenableFuture<*>? {
        return try {
            val clazz = Class.forName("androidx.camera.core.CameraX")
            val method =
                clazz.methods.firstOrNull { it.name == "shutdown" && it.parameterCount == 0 }
            val future = method?.invoke(null)
            future as? ListenableFuture<*>
        } catch (e: Throwable) {
            Log.w(TAG, "CameraX.shutdown() no disponible: ${e.message}")
            null
        }
    }

    private fun attachCompletion(future: ListenableFuture<*>, onDone: () -> Unit) {
        try {
            future.addListener({
                try {
                    onDone()
                } catch (e: Exception) {
                    Log.e(TAG, "Error en onDone(): ${e.message}", e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adjuntar listener al future: ${e.message}")
            onDone()
        }
    }

    private fun copyVideoFromMediaStoreToSd(
        sourceUri: Uri, mimeType: String, displayName: String
    ) {
        val prefs = getSharedPreferences("MyAppPrefs", MODE_PRIVATE)
        val treeUriString = prefs.getString("sd_tree_uri", null)
        if (treeUriString.isNullOrEmpty()) {
            Toast.makeText(this, "No se ha seleccionado carpeta en la SD", Toast.LENGTH_LONG).show()
            return
        }
        val treeUri = Uri.parse(treeUriString)

        val folder = getOrCreateDefaultFolder(this, treeUri, "Camy-Videos")
        if (folder == null) {
            Toast.makeText(
                this, "No se pudo crear la carpeta Camy-Videos en la SD", Toast.LENGTH_LONG
            ).show()
            return
        }

        val newFile = folder.createFile(mimeType, displayName)
        if (newFile == null) {
            Toast.makeText(this, "Error al crear el archivo en la SD", Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                contentResolver.openFileDescriptor(newFile.uri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                        copyStream(inputStream, outputStream)
                    }
                }
            }
            MediaScannerConnection.scanFile(
                this, arrayOf(newFile.uri.toString()), arrayOf(mimeType), null
            )

            Toast.makeText(
                this, "Vídeo guardado en la SD en la carpeta Camy-Videos", Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al copiar el vídeo: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun safeDisableTorch() {
        try {
            if (isFlashOn) {
                cameraControl?.enableTorch(false)
                isFlashOn = false
                Log.d(TAG, "Torch forzada a OFF")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error apagando torch: ${e.message}", e)
        }
    }

    private fun cleanupCamera() {
        safeDisableTorch()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll lanzó excepción: ${e.message}", e)
        }
        videoCapture = null
        imageCapture = null
        cameraControl = null
        cameraProvider = null
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
        super.onDestroy()

        stopRecordingVideo()
        cleanupCamera()

        if (!broadcastSent) {
            sendStoppedBroadcastOnce()
        }

        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            cameraExecutor.shutdown()
        } catch (_: Exception) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopRecordingVideo()
        stopSelf()
    }

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 123
        const val ACTION_SERVICE_STOPPED = "com.example.camy.ACTION_SERVICE_STOPPED"
    }
}