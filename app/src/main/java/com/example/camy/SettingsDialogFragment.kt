package com.example.camy

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import androidx.fragment.app.DialogFragment

class SettingsDialogFragment : DialogFragment() {

    private lateinit var chkRecordAudio: CheckBox
    private lateinit var spinnerStorage: Spinner
    private lateinit var btnSave: Button

    interface OnSettingsSavedListener {
        fun onSettingsSaved(recordAudio: Boolean, storageOption: String)
    }

    var listener: OnSettingsSavedListener? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        // Haz el background del diálogo (ventana) transparente
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        // Infla tu layout
        val view = inflater.inflate(R.layout.dialog_settings, container, false)

        chkRecordAudio = view.findViewById(R.id.chkRecordAudio)
        spinnerStorage = view.findViewById(R.id.spinnerStorage)
        btnSave = view.findViewById(R.id.btnSave)

        // Configurar Spinner (almacenamiento)
        val storageOptions = listOf("Almacenamiento Interno", "Almacenamiento Externo")
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, storageOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStorage.adapter = adapter

        // Cargar valores guardados con SharedPreferences:
        val prefs = requireContext().getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)
        val recordAudio = prefs.getBoolean("recordAudio", true)
        val storageChoice = prefs.getString("storageChoice", "internal")

        chkRecordAudio.isChecked = recordAudio
        if (storageChoice == "external") {
            spinnerStorage.setSelection(1) // Almacenamiento externo
        } else {
            spinnerStorage.setSelection(0) // Almacenamiento interno
        }

        btnSave.setOnClickListener {
            val newRecordAudio = chkRecordAudio.isChecked
            val selectedStoragePos = spinnerStorage.selectedItemPosition
            val newStorageChoice = if (selectedStoragePos == 1) "external" else "internal"

            // Guardar en prefs
            val editor = prefs.edit()
            editor.putBoolean("recordAudio", newRecordAudio)
            editor.putString("storageChoice", newStorageChoice)
            editor.apply()

            listener?.onSettingsSaved(newRecordAudio, newStorageChoice)

            dismiss()
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
