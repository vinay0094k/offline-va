package com.codvika.voiceassistant

import android.os.Bundle
import android.text.InputType
import android.view.View
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
        val btnTest = findViewById<Button>(R.id.btnTest)
        val btnToggleKey = findViewById<Button>(R.id.btnToggleKey)
        inputModel.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modelPresets)
        )
        inputModel.setOnClickListener { inputModel.showDropDown() }

        switchOnline.isChecked = Settings.onlineEnabled(this)
        inputBaseUrl.setText(Settings.baseUrl(this))
        inputApiKey.setText(Settings.apiKey(this))
        // filter=false: populate without popping the suggestion dropdown open.
        inputModel.setText(Settings.model(this), false)

        // The saved key is masked; let the user reveal it to verify what's stored.
        btnToggleKey.setOnClickListener {
            val visible = inputApiKey.inputType == InputType.TYPE_CLASS_TEXT
            inputApiKey.inputType = if (visible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            btnToggleKey.text = if (visible) "Show" else "Hide"
            inputApiKey.setSelection(inputApiKey.text.length)
        }

        // The test button is only useful/available when online mode is on.
        btnTest.visibility = if (switchOnline.isChecked) View.VISIBLE else View.GONE
        switchOnline.setOnCheckedChangeListener { _, isChecked ->
            btnTest.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        btnTest.setOnClickListener { runConnectionTest(btnTest, inputBaseUrl, inputApiKey, inputModel) }

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

    /** Verifies the configured endpoint/auth/model with one tiny request. */
    private fun runConnectionTest(
        btnTest: Button, inputBaseUrl: EditText, inputApiKey: EditText, inputModel: AutoCompleteTextView
    ) {
        val baseUrl = inputBaseUrl.text.toString()
        val model = inputModel.text.toString()
        if (baseUrl.isBlank() || model.isBlank()) {
            Toast.makeText(this, "Base URL and model are required to test", Toast.LENGTH_LONG).show()
            return
        }
        val apiKey = inputApiKey.text.toString()

        btnTest.isEnabled = false
        btnTest.text = "Testing…"
        Thread {
            val failure = try {
                RemoteLlm.test(baseUrl, apiKey, model)
                null
            } catch (e: Exception) {
                e.message ?: "Connection failed"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnTest.isEnabled = true
                btnTest.text = "Test connection"
                Toast.makeText(
                    this,
                    if (failure == null) "Connected ✓" else "Failed: $failure",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}
