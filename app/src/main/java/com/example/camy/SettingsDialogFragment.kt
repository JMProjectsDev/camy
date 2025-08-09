package com.example.camy

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment

class SettingsDialogFragment : DialogFragment() {

    private lateinit var chkRecordAudio: CheckBox
    private lateinit var spinnerStorage: Spinner
    private lateinit var btnSave: Button

    var listener: OnSettingsSavedListener? = null

    interface OnSettingsSavedListener {
        fun onSettingsSaved(recordAudio: Boolean, storageOption: String)
    }

    private lateinit var sdFolderLauncher: ActivityResultLauncher<Intent>
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = requireContext().getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)

        sdFolderLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    val finalUri = if (uri.toString().contains("document/")) {
                        uri
                    } else {
                        uri
                    }
                    requireContext().contentResolver.takePersistableUriPermission(
                        finalUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    prefs.edit().putString("sd_tree_uri", finalUri.toString()).apply()
                    listener?.onSettingsSaved(chkRecordAudio.isChecked, "external")
                    dismiss()
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_settings, container, false)

        chkRecordAudio = view.findViewById(R.id.chkRecordAudio)
        spinnerStorage = view.findViewById(R.id.spinnerStorage)
        btnSave = view.findViewById(R.id.btnSave)

        val storageOptions = listOf("Almacenamiento Interno", "Almacenamiento Externo")
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, storageOptions)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerStorage.adapter = adapter

        val recordAudio = prefs.getBoolean("recordAudio", true)
        val storageChoice = prefs.getString("storageChoice", "internal")
        chkRecordAudio.isChecked = recordAudio
        spinnerStorage.setSelection(if (storageChoice == "external") 1 else 0)

        btnSave.setOnClickListener {
            val newRecordAudio = chkRecordAudio.isChecked
            val selectedStoragePos = spinnerStorage.selectedItemPosition
            val newStorageChoice = if (selectedStoragePos == 1) "external" else "internal"
            val editor = prefs.edit()
            editor.putBoolean("recordAudio", newRecordAudio)
            editor.putString("storageChoice", newStorageChoice)
            editor.apply()

            if (newStorageChoice == "internal") {
                editor.remove("sd_tree_uri").apply()
                listener?.onSettingsSaved(newRecordAudio, newStorageChoice)
                dismiss()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    listener?.onSettingsSaved(newRecordAudio, newStorageChoice)
                    dismiss()
                } else {
                    val savedTreeUri = prefs.getString("sd_tree_uri", null)
                    if (savedTreeUri.isNullOrEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            "Selecciona la carpeta de la SD para guardar el contenido.",
                            Toast.LENGTH_LONG
                        ).show()
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            )
                        }
                        sdFolderLauncher.launch(intent)
                    } else {
                        listener?.onSettingsSaved(newRecordAudio, newStorageChoice)
                        dismiss()
                    }
                }
            }
        }
        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}