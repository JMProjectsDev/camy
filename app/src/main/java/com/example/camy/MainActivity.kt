package com.example.camy

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // === Vistas de la UI ===
    private lateinit var previewView: PreviewView
    private lateinit var btnFlash: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var btnCapture: ImageView
    private lateinit var btnToggleMode: ImageView

    // === Estados ===
    private var isPhotoMode = true
    private var isRecording = false
    private var isFlashOn = false

    // === CameraX en la Activity para FOTOS ===
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null

    // === Permisos ===
    private val REQUEST_CODE_PERMISSIONS = 10
    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1) Referencias a vistas
        previewView = findViewById(R.id.previewView)
        btnFlash = findViewById(R.id.btnFlash)
        btnGallery = findViewById(R.id.btnGallery)
        btnCapture = findViewById(R.id.btnCapture)
        btnToggleMode = findViewById(R.id.btnToggleMode)

        // 2) Verificar permisos
        val requiredPermissions = getRequiredPermissions()
        if (!allPermissionsGranted(requiredPermissions)) {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE_PERMISSIONS)
        } else {
            // Si ya tenemos permisos, iniciamos la preview para FOTOS
            startPreviewInActivity()
        }

        // === Botón de Flash ===
        btnFlash.setOnClickListener {
            isFlashOn = !isFlashOn

            // Actualizar icono de UI
            val iconRes = if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            val iconBack = if (isFlashOn) R.drawable.circle_yellow else R.drawable.circle_button_small
            btnFlash.setBackgroundResource(iconBack)
            btnFlash.setImageResource(iconRes)

            if (isPhotoMode) {
                // Si estamos en MODO FOTO, encendemos/apagamos flash localmente
                toggleFlashForPhoto(isFlashOn)
            } else {
                // Si estamos en MODO VIDEO (background Service), avisamos al servicio
                val intent = Intent(this, CameraService::class.java).apply {
                    action = CameraService.CameraServiceActions.ACTION_TOGGLE_FLASH
                }
                startService(intent)
            }
        }

        // === Botón de galería ===
        btnGallery.setOnClickListener {
            openGallery()
        }

        // === Botón central de captura (FOTO o VIDEO) ===
        btnCapture.setOnClickListener {
            if (isPhotoMode) {
                // == Tomar foto en la Activity ==
                capturePhoto()

            } else {
                // == Grabar video en el Service ==
                if (!isRecording) {
                    // 1) Soltamos la cámara en la Activity (no podemos usarla en paralelo)
                    stopPreviewInActivity()

                    // 2) Iniciar grabación en el Service
                    val intent = Intent(this, CameraService::class.java).apply {
                        action = CameraService.CameraServiceActions.ACTION_START_RECORDING
                    }
                    ContextCompat.startForegroundService(this, intent)

                    // 3) Actualizar UI local
                    isRecording = true
                    btnCapture.setImageResource(R.drawable.ic_stop)
                    animateIconSize(btnCapture, 22)
                    btnCapture.setBackgroundResource(R.drawable.circle_red)

                } else {
                    // Detener grabación
                    val intent = Intent(this, CameraService::class.java).apply {
                        action = CameraService.CameraServiceActions.ACTION_STOP_RECORDING
                    }
                    startService(intent)

                    // Restablecer icono UI
                    isRecording = false
                    btnCapture.setImageResource(R.drawable.ic_video)
                    animateIconSize(btnCapture, 38)
                    btnCapture.setBackgroundResource(R.drawable.circle_button_large)
                }
            }
        }

        // === Botón para cambiar modo (foto/video) ===
        btnToggleMode.setOnClickListener {
            // Si está grabando en video, lo detenemos primero
            if (isRecording) {
                val intent = Intent(this, CameraService::class.java).apply {
                    action = CameraService.CameraServiceActions.ACTION_STOP_RECORDING
                }
                startService(intent)
                isRecording = false
            }

            // Toggle local (FOTO <-> VIDEO)
            isPhotoMode = !isPhotoMode
            updateUIForMode()
        }

        // Arranque inicial de la UI
        updateUIForMode()
    }

    // === Ciclo de vida: Registrar y des-registrar el BroadcastReceiver ===
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(CameraService.ACTION_SERVICE_STOPPED)
        registerReceiver(serviceStoppedReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(serviceStoppedReceiver)
    }

    // ============= PHOTO MODE: PREVIEW, CAPTURE & FLASH =============

    /** Inicia la preview para tomar fotos en la Activity. */
    private fun startPreviewInActivity() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            cameraProvider?.unbindAll()

            // 1) Creamos el Preview
            val preview = Preview.Builder().build()
            previewUseCase = preview

            // 2) Creamos el ImageCapture
            val imgCapture = ImageCapture.Builder().build()
            imageCapture = imgCapture

            // 3) Bind a la cámara trasera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                val camera = cameraProvider?.bindToLifecycle(
                    this, // LifecycleOwner = Activity
                    cameraSelector,
                    preview,
                    imgCapture
                )
                // 4) Manejamos cameraControl para flash local
                cameraControl = camera?.cameraControl

                // 5) Conectamos el preview al PreviewView
                preview.setSurfaceProvider(previewView.surfaceProvider)

                Log.d("MainActivity", "Preview de fotos iniciada.")
            } catch (e: Exception) {
                Log.e("MainActivity", "No se pudo iniciar preview: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Para la preview si vamos a ceder la cámara al Service. */
    private fun stopPreviewInActivity() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            previewUseCase = null
            imageCapture = null
            cameraControl = null
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al soltar la cámara de preview: ${e.message}")
        }
    }

    /** Toma una foto localmente (modo foto). */
    private fun capturePhoto() {
        val imgCapture = imageCapture ?: return
        Log.d("MainActivity", "Capturando foto en la Activity...")

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "photo_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Photos")
            }
        }
        val outputUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, outputUri, contentValues)
            .build()

        imgCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("MainActivity", "Foto guardada correctamente")
                    Toast.makeText(
                        this@MainActivity,
                        "Foto guardada correctamente",
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("MainActivity", "Error al guardar foto: ${exception.message}", exception)
                    Toast.makeText(
                        this@MainActivity,
                        "Error al capturar foto: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    /** Activa/desactiva flash localmente mientras estamos en modo foto. */
    private fun toggleFlashForPhoto(enable: Boolean) {
        // Solo si la cámara está en la Activity
        if (cameraControl == null) {
            Log.w("MainActivity", "toggleFlashForPhoto: cameraControl es null (no hay cámara en la Activity).")
            return
        }
        cameraControl?.enableTorch(enable)
        Log.d("MainActivity", "Flash foto -> $enable")
    }

    // ============= VIDEO MODE: BROADCAST RECEIVER =============

    /**
     * Recibe la notificación de que el Service se detuvo (ACTION_SERVICE_STOPPED).
     * Con ello, volvemos a tomar la cámara localmente para el modo foto.
     */
    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CameraService.ACTION_SERVICE_STOPPED) {
                Log.d("MainActivity", "Recibido ACTION_SERVICE_STOPPED del Service.")
                // Volver a tomar la cámara en la Activity (por si queremos fotos)
                startPreviewInActivity()
            }
        }
    }

    // ============= GALERÍA, PERMISOS, UI, ETC. =============

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    /** Actualiza la UI según estemos en modo foto o video */
    private fun updateUIForMode() {
        if (isPhotoMode) {
            btnCapture.setImageResource(R.drawable.ic_photo)
            btnToggleMode.setImageResource(R.drawable.ic_video)
            animateIconSize(btnCapture, 38)
            animateIconSize(btnToggleMode, 22)
            btnCapture.setBackgroundResource(R.drawable.circle_button_large)
        } else {
            btnCapture.setImageResource(R.drawable.ic_video)
            btnToggleMode.setImageResource(R.drawable.ic_photo)
            animateIconSize(btnCapture, 38)
            animateIconSize(btnToggleMode, 22)
            btnCapture.setBackgroundResource(R.drawable.circle_button_large)
        }
    }

    private fun animateIconSize(view: ImageView, iconSizeDp: Int) {
        val iconSizePx = (iconSizeDp * resources.displayMetrics.density).toInt()
        val viewSize = view.layoutParams.width
        if (viewSize > 0) {
            val padding = (viewSize - iconSizePx) / 2
            view.setPadding(padding, padding, padding, padding)
        }
    }

    // ============= PERMISOS Y RESULTADOS =============

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val someDenied = grantResults.any { it == PackageManager.PERMISSION_DENIED }
            if (someDenied) {
                Toast.makeText(
                    this,
                    "Es necesario conceder permisos para usar la app",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                // Permisos concedidos
                startPreviewInActivity()
            }
        }
    }

    private fun allPermissionsGranted(perms: Array<String>) =
        perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
