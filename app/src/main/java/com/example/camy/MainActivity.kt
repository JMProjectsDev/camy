package com.example.camy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SettingsDialogFragment.OnSettingsSavedListener {

    private lateinit var previewView: PreviewView
    private lateinit var btnFlash: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var btnCapture: ImageView
    private lateinit var btnToggleMode: ImageView
    private lateinit var btnSettings: ImageButton

    // Estados
    private var isPhotoMode = false
    private var isRecording = false
    private var isFlashOn = false

    // CameraX en la Activity para FOTOS
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null

    private lateinit var llTimerContainer: LinearLayout
    private lateinit var tvTimer: TextView

    // Para el cronómetro
    private var timerSeconds = 0
    private var timerHandler = Handler(Looper.getMainLooper())
    private var isTimerRunning = false

    private lateinit var orientationListener: OrientationEventListener

    // Permisos
    private val REQUEST_CODE_PERMISSIONS = 10
    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    // Runnable que se ejecuta cada segundo
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isTimerRunning) {
                timerSeconds++
                updateTimerUI(timerSeconds)
                timerHandler.postDelayed(this, 1000L) // Delay de 1 segundo y lanzar
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ahora dentro de onCreate, ya se puede usar this para getSystemService
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val newRotation = orientationToSurfaceRotation(orientation)

                // Enviar la rotación al Service para que NO reinicie la cámara
                val intent = Intent(this@MainActivity, CameraService::class.java).apply {
                    action = "com.example.camy.ACTION_ROTATION_CHANGED"
                    putExtra("VIDEO_ROTATION", newRotation)
                }
                startService(intent)
            }
        }
        orientationListener.enable()

        previewView = findViewById(R.id.previewView)
        btnFlash = findViewById(R.id.btnFlash)
        btnGallery = findViewById(R.id.btnGallery)
        btnCapture = findViewById(R.id.btnCapture)
        btnToggleMode = findViewById(R.id.btnToggleMode)
        tvTimer = findViewById(R.id.tvTimer)
        llTimerContainer = findViewById(R.id.llTimerContainer)
        btnSettings = findViewById(R.id.btnSettings)

        val requiredPermissions = getRequiredPermissions()
        if (!allPermissionsGranted(requiredPermissions)) {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE_PERMISSIONS)
        } else {
            startPreviewInActivity()
        }

        btnFlash.setOnClickListener {
            if (isPhotoMode) {
                isFlashOn = !isFlashOn
                updateFlashUI(isFlashOn)
                toggleFlashForPhoto(isFlashOn)
            } else {
                if (!isRecording) {
                    Toast.makeText(
                        this, "Debes iniciar la grabación para usar la linterna", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Linterna en modo video GRABANDO
                    isFlashOn = !isFlashOn
                    updateFlashUI(isFlashOn)

                    val intent = Intent(this, CameraService::class.java).apply {
                        action = CameraService.CameraServiceActions.ACTION_TOGGLE_FLASH
                    }
                    startService(intent)
                }
            }
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnGallery.setOnClickListener {
            openGallery()
        }

        btnCapture.setOnClickListener {
            if (isPhotoMode) {
                capturePhoto()
            } else {
                if (!isRecording) {
                    lockCurrentOrientation() // Bloquea la orientación actual
                    stopPreviewInActivity()
                    startTimer()

                    val intent = Intent(this, CameraService::class.java).apply {
                        action = CameraService.CameraServiceActions.ACTION_START_RECORDING
                    }
                    ContextCompat.startForegroundService(this, intent)

                    isRecording = true
                    btnFlash.isEnabled = true
                    btnCapture.setImageResource(R.drawable.ic_stop)
                    animateIconSize(btnCapture, 22)
                    btnCapture.setBackgroundResource(R.drawable.circle_red)
                } else {
                    val intent = Intent(this, CameraService::class.java).apply {
                        action = CameraService.CameraServiceActions.ACTION_STOP_RECORDING
                    }
                    startService(intent)
                    stopTimer()

                    // Forzar linterna apagada
                    isFlashOn = false
                    updateFlashUI(false)

                    // Libera la orientación para permitir giro de nuevo
                    unlockOrientation()

                    isRecording = false
                    btnCapture.setImageResource(R.drawable.ic_video)
                    animateIconSize(btnCapture, 38)
                    btnCapture.setBackgroundResource(R.drawable.circle_button_large)
                }
            }
        }

        btnToggleMode.setOnClickListener {
            if (isRecording) {
                val intent = Intent(this, CameraService::class.java).apply {
                    action = CameraService.CameraServiceActions.ACTION_STOP_RECORDING
                }
                startService(intent)
                isRecording = false
            }

            if (isFlashOn) {
                isFlashOn = false
                if (isPhotoMode) {
                    toggleFlashForPhoto(false)
                }
                updateFlashUI(false)
            }
            // Cambio de modo
            isPhotoMode = !isPhotoMode
            updateUIForMode()

            btnFlash.isEnabled = true
        }

        // Estado inicial de la UI
        updateUIForMode()
    }

    // Ciclo de vida: registrar y des-registrar el BroadcastReceiver
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(CameraService.ACTION_SERVICE_STOPPED)
        registerReceiver(serviceStoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        // No poner anotaciones ya que restringira la funcion a la version de la API.
        orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(serviceStoppedReceiver)
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            stopService(Intent(this, CameraService::class.java))
        }
    }


    /** Inicia la preview para tomar fotos en la Activity. */
    private fun startPreviewInActivity() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            cameraProvider?.unbindAll()

            // Se crea el Preview y el ImageCapture
            val preview = Preview.Builder().build()
            val imgCapture = ImageCapture.Builder()
                .setJpegQuality(50)
                .build()
            previewUseCase = preview
            imageCapture = imgCapture

            // Bind a la camara trasera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                val camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imgCapture
                )
                // Si no se pone esto el flash no funciona
                cameraControl = camera?.cameraControl

                // Conectamos el preview al PreviewView
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

    private fun capturePhoto() {
        val imgCapture = imageCapture ?: return
        Log.d("MainActivity", "Capturando foto en la Activity...")
        val date = getCurrentDate()
        val contentValues = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                "photo_${System.currentTimeMillis()}_${date}.jpg"
            )
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camy-Pictures")
            }
        }
        val outputUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val outputOptions =
            ImageCapture.OutputFileOptions.Builder(contentResolver, outputUri, contentValues)
                .build()

        imgCapture.takePicture(outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("MainActivity", "Foto guardada correctamente")
                    Toast.makeText(
                        this@MainActivity, "Foto guardada correctamente", Toast.LENGTH_LONG
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
            })
    }

    /** Activa/desactiva flash localmente mientras estamos en modo foto. */
    private fun toggleFlashForPhoto(enable: Boolean) {
        // Solo si la cámara está en la Activity
        if (cameraControl == null) {
            Log.w(
                "MainActivity",
                "toggleFlashForPhoto: cameraControl es null (no hay cámara en la Activity)."
            )
            return
        }
        cameraControl?.enableTorch(enable)
        Log.d("MainActivity", "Flash foto -> $enable")
    }


    /**
     * Recibe la notificación de que el Service se detuvo (ACTION_SERVICE_STOPPED).
     * Con ello, volvemos a tomar la cámara para el modo foto.
     */
    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CameraService.ACTION_SERVICE_STOPPED) {
                Log.d("MainActivity", "Recibido ACTION_SERVICE_STOPPED del Service.")
                startPreviewInActivity()
            }
        }
    }

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

    private fun orientationToSurfaceRotation(orientation: Int): Int {
        return when (orientation) {
            in 315..359, in 0..44 -> Surface.ROTATION_0
            in 45..134 -> Surface.ROTATION_90
            in 135..314 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
    }

    private fun updateFlashUI(isOn: Boolean) {
        val iconRes = if (isOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        val iconBack = if (isOn) R.drawable.circle_yellow else R.drawable.circle_button_small
        btnFlash.setBackgroundResource(iconBack)
        btnFlash.setImageResource(iconRes)
    }

    private fun lockCurrentOrientation() {
        // Detecta la orientación actual (portrait u horizontal)
        val orientation = resources.configuration.orientation

        // Si está en portrait (vertical)
        requestedOrientation = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun unlockOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun startTimer() {
        timerSeconds = 0
        isTimerRunning = true
        llTimerContainer.visibility = View.VISIBLE
        timerHandler.post(timerRunnable)
    }

    private fun stopTimer() {
        isTimerRunning = false
        timerHandler.removeCallbacks(timerRunnable)
        llTimerContainer.visibility = View.GONE
    }

    private fun updateTimerUI(seconds: Int) {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        val timeStr = if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, secs) // Formato con horas
        } else {
            String.format("%02d:%02d", minutes, secs) // Formato sin horas
        }
        tvTimer.text = timeStr
    }

    /** Permisos y resultados **/
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val someDenied = grantResults.any { it == PackageManager.PERMISSION_DENIED }
            if (someDenied) {
                Toast.makeText(
                    this, "Es necesario conceder permisos para usar la app", Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                // Permisos concedidos
                startPreviewInActivity()
            }
        }
    }

    private fun allPermissionsGranted(perms: Array<String>) = perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun showSettingsDialog() {
        val dialog = SettingsDialogFragment()
        dialog.listener = this  // para recibir onSettingsSaved
        dialog.show(supportFragmentManager, "settingsDialog")
    }

    override fun onSettingsSaved(recordAudio: Boolean, storageOption: String) {
        if (!recordAudio && storageOption == "internal") {
            Toast.makeText(
                this, "Micrófono desactivado. \n Alm. interno seleccionado.", Toast.LENGTH_SHORT
            ).show()
        } else if (!recordAudio && storageOption == "external") {
            Toast.makeText(
                this, "Micrófono desactivado. \n Alm. externo seleccionado.", Toast.LENGTH_SHORT
            ).show()
        } else if (recordAudio && storageOption == "internal") {
            Toast.makeText(this, "Micrófono activado. \n Alm. interno seleccionado.", Toast.LENGTH_SHORT)
                .show()
        } else {
            Toast.makeText(this, "Micrófono activado. \n Alm. externo seleccionado.", Toast.LENGTH_SHORT)
                .show()
        }
    }
}
