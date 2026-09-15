package com.xiufeigo.opencodeshell

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.xiufeigo.opencodeshell.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = settingsStore()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(settings.hasServerUrl)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.serverUrl.setText(settings.serverUrl)
        binding.username.setText(settings.username)
        binding.password.setText(settings.password)

        binding.save.setOnClickListener { save() }
    }

    private fun save() {
        val parsed = ServerUrl.parse(binding.serverUrl.text?.toString().orEmpty())
        val url = parsed.getOrElse { error ->
            binding.serverUrlLayout.error = error.message
            return
        }
        binding.serverUrlLayout.error = null

        settings.serverUrl = url.value
        settings.username = binding.username.text?.toString()?.trim().orEmpty()
        settings.password = binding.password.text?.toString().orEmpty()

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}
