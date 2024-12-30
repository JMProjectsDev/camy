package com.example.camy

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnFlash: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var btnCapture: ImageView
    private lateinit var btnToggleMode: ImageView

    private var isPhotoMode = true // Modo inicial: Foto
    private var isRecording = false;
    private var activeRecording: Recording? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraControl: CameraControl? = null
    private var isFlashOn = false // Estado inicial del flash
    private var imageCapture: ImageCapture? = null

    private val permissionsToRequest = getRequiredPermissions()

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        // Si es Android 9 o anterior, hay que pedir WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    private val REQUEST_CODE_PERMISSIONS = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        previewView = findViewById(R.id.previewView)
        btnFlash = findViewById(R.id.btnFlash)
        btnGallery = findViewById(R.id.btnGallery)
        btnCapture = findViewById(R.id.btnCapture)
        btnToggleMode = findViewById(R.id.btnToggleMode)

        // Para que los botones tengan las dimensiones correctas
        initializeButtonIcons()

        // Verificar permisos
        if (allPermissionsGranted(permissionsToRequest)) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, permissionsToRequest, REQUEST_CODE_PERMISSIONS
            )
        }

        // Alternar entre foto y video
        btnToggleMode.setOnClickListener {
            if (isRecording) {
                stopRecordingVideo()
            }
            isPhotoMode = !isPhotoMode
            updateUIForMode()
        }

        // Capturar foto o grabar video
        btnCapture.setOnClickListener {
            if (isPhotoMode) {
                capturePhoto()
            } else {
                if (isRecording) {
                    stopRecordingVideo()
                } else {
                    startRecordingVideo()
                }
            }
        }

        // Configurar linterna
        btnFlash.setOnClickListener {
            toggleFlash()
        }

        // Abrir galería
        btnGallery.setOnClickListener {
            openGallery()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002 && resultCode == RESULT_OK) {
            val selectedImageUri = data?.data
            Log.d("Gallery", "Imagen seleccionada: $selectedImageUri")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted(permissionsToRequest)) {
                Log.d("PermissionsCheck", "Permisos otorgados, iniciando cámara.")
                startCamera()
            } else {
                Log.d("PermissionsCheck", "Permisos rechazados.")
                // Verifica si el usuario denegó permisos permanentemente
                val somePermissionsDeniedForever = permissions.indices.any { i ->
                    grantResults[i] == PackageManager.PERMISSION_DENIED &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(
                                this,
                                permissions[i]
                            )
                }

                if (somePermissionsDeniedForever) {
                    Log.d("PermissionsCheck", "Permisos denegados permanentemente.")
                    showPermissionDeniedDialog()
                } else {
                    Log.d("PermissionsCheck", "Permisos rechazados temporalmente.")
                    Toast.makeText(
                        this,
                        "Los permisos son necesarios para usar la aplicación.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Configura el Preview
            val preview = androidx.camera.core.Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .build()

            // Configura el Recorder y el VideoCapture
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // Selecciona la cámara trasera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Desvincula cualquier caso de uso anterior y vincula los nuevos
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCapture,
                    imageCapture
                )
                // Asignar cameraControl sino el flash no funcionará
                cameraControl = camera.cameraControl

            } catch (e: Exception) {
                Log.e("CameraX", "Error al abrir la cámara", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        Log.d("CameraX", "Capturando foto...")

        // 1) Contenido y metadatos para la foto
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "photo_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Photos")
            }
        }


        // 2) Uri de destino en MediaStore
        val outputUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // 3) Configurar las opciones de salida para ImageCapture
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, outputUri!!, contentValues)
            .build()

        // 4) Tomar la foto
        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraX", "Foto guardada en: $outputUri")
                    Toast.makeText(
                        this@MainActivity,
                        "Foto guardada: $outputUri",
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Error al guardar foto: ${exception.message}", exception)
                    Toast.makeText(
                        this@MainActivity,
                        "Error al capturar foto: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun startRecordingVideo() {
        Log.d("CameraX", "Iniciando grabación de video...")
        val intent = Intent(this, CameraService::class.java)
        ContextCompat.startForegroundService(this, intent);

        // 1) Preparamos los metadatos
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "video_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Movies/CameraX-Videos")
            }
        }

        // 2) Generamos MediaStoreOutputOptions
        val videoUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, videoUri)
            .setContentValues(contentValues)
            .build()

        // 3) Iniciamos grabación con .prepareRecording(...) y .start(...)
        try {
            activeRecording = videoCapture?.output
                ?.prepareRecording(this, mediaStoreOutputOptions)
                ?.withAudioEnabled()
                ?.start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d("CameraX", "Grabación iniciada")
                            isRecording = true
                            btnCapture.setImageResource(R.drawable.ic_stop)
                            animateIconSize(btnCapture, 22)
                            btnCapture.setBackgroundResource(R.drawable.circle_red)
                        }
                        is VideoRecordEvent.Finalize -> {
                            Log.d(
                                "CameraX",
                                "Grabación finalizada: ${event.outputResults.outputUri}"
                            )
                            isRecording = false
                            btnCapture.setImageResource(R.drawable.ic_video)
                            animateIconSize(btnCapture, 38)
                            btnCapture.setBackgroundResource(R.drawable.circle_button_large)

                            Toast.makeText(
                                this,
                                "Video guardado: ${event.outputResults.outputUri}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is VideoRecordEvent.Status -> {
                            // eventos intermedios
                        }
                        else -> Log.w("CameraX", "Evento desconocido: $event")
                    }
                }
        } catch (e: SecurityException) {
            Log.e("CameraX", "Permiso rechazado: ${e.message}")
        }
    }

    private fun stopRecordingVideo() {
        Log.d("CameraX", "Deteniendo grabación de video...")
        isRecording = false
        stopService(Intent(this, CameraService::class.java))

        // Cambiar icono y tamaño de botón
        btnCapture.setImageResource(R.drawable.ic_video)
        animateIconSize(btnCapture, 38)
        btnCapture.setBackgroundResource(R.drawable.circle_button_large)

        activeRecording?.stop() // Finaliza la grabación pendiente
        activeRecording = null
    }

    private fun toggleFlash() {
        if (cameraControl != null) {
            isFlashOn = !isFlashOn
            cameraControl?.enableTorch(isFlashOn)
            btnFlash.apply {
                if (isFlashOn) {
                    setBackgroundResource(R.drawable.circle_yellow)
                } else {
                    setBackgroundResource(R.drawable.circle_button_small)
                }
                setImageResource(
                    if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
                )
            }
        } else {
            Log.w("CameraX", "La cámara no está inicializada.")
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.type = "image/*" // MIME type para imágenes
        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK // Asegura que la galería se abra como una nueva tarea
        startActivity(intent)
    }

    private fun updateUIForMode() {
        // Si se está grabando, detener la grabación
        if (isRecording) {
            stopRecordingVideo()
        }

        if (isPhotoMode) {
            btnCapture.setImageResource(R.drawable.ic_photo)
            btnToggleMode.setImageResource(R.drawable.ic_video)

            // Cambiar el tamaño del icono dinámicamente
            animateIconSize(btnCapture, 38)
            animateIconSize(btnToggleMode, 22)
            btnCapture.setBackgroundResource(R.drawable.circle_button_large)
        } else {
            btnCapture.setImageResource(R.drawable.ic_video)
            btnToggleMode.setImageResource(R.drawable.ic_photo)

            // Cambiar el tamaño del icono dinámicamente
            animateIconSize(btnCapture, 38)
            animateIconSize(btnToggleMode, 22)
            btnCapture.setBackgroundResource(R.drawable.circle_button_large)
        }
    }

    private fun animateIconSize(view: ImageView, iconSizeDp: Int) {
        val iconSizePx = (iconSizeDp * resources.displayMetrics.density).toInt()
        val viewSize = view.layoutParams.width

        // Verificar que el tamaño del botón sea válido
        if (viewSize > 0) {
            val padding = (viewSize - iconSizePx) / 2
            view.setPadding(padding, padding, padding, padding)
        }
    }

    /** FUNCIONES PARA INICIALIZAR LA APLICACION CORRECTAMENTE **/
    private fun initializeButtonIcons() {
        animateIconSize(btnCapture, 38) // Tamaño inicial grande
        animateIconSize(btnToggleMode, 22) // Tamaño inicial pequeño
    }

    private fun allPermissionsGranted(perms: Array<String>): Boolean {
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permisos necesarios")
            .setMessage("Los permisos son necesarios para usar la cámara y grabar audio. Por favor, actívalos manualmente en la configuración de la aplicación.")
            .setPositiveButton("Abrir configuración") { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("Salir") { _, _ ->
                finish()
            }
            .show()
    }
}
