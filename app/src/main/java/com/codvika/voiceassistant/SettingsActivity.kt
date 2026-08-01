package com.codvika.voiceassistant

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    /** Common OpenRouter model slugs; the field stays free-text for anything else. */
    private val modelPresets = arrayOf(
        "deepseek/deepseek-v4-flash",
        "amazon/nova-micro-v1"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val switchOnline = findViewById<Switch>(R.id.switchOnline)
        val inputBaseUrl = findViewById<EditText>(R.id.inputBaseUrl)
        val inputApiKey = findViewById<EditText>(R.id.inputApiKey)
        val inputModel = findViewById<AutoCompleteTextView>(R.id.inputModel)
        inputModel.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modelPresets)
        )
        inputModel.setOnClickListener { inputModel.showDropDown() }
        inputModel.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) inputModel.showDropDown() }

        switchOnline.isChecked = Settings.onlineEnabled(this)
        inputBaseUrl.setText(Settings.baseUrl(this))
        inputApiKey.setText(Settings.apiKey(this))
        // filter=false: populate without popping the suggestion dropdown open.
        inputModel.setText(Settings.model(this), false)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val online = switchOnline.isChecked
            val baseUrl = inputBaseUrl.text.toString()
            val model = inputModel.text.toString()
            if (online && (baseUrl.isBlank() || model.isBlank())) {
                Toast.makeText(this, "Base URL and model are required to enable online mode", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Settings.save(this, online, baseUrl, inputApiKey.text.toString(), model)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
