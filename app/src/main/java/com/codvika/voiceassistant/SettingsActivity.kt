package com.codvika.voiceassistant

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val switchOnline = findViewById<Switch>(R.id.switchOnline)
        val inputBaseUrl = findViewById<EditText>(R.id.inputBaseUrl)
        val inputApiKey = findViewById<EditText>(R.id.inputApiKey)
        val inputModel = findViewById<EditText>(R.id.inputModel)

        switchOnline.isChecked = Settings.onlineEnabled(this)
        inputBaseUrl.setText(Settings.baseUrl(this))
        inputApiKey.setText(Settings.apiKey(this))
        inputModel.setText(Settings.model(this))

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
