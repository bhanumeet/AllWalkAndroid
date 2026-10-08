package com.google.ar.core.codelabs.hellogeospatial

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.codelabs.hellogeospatial.helpers.AppSettings
import com.google.ar.core.codelabs.hellogeospatial.helpers.DestinationProvider

class SettingsActivity : AppCompatActivity() {
  private lateinit var settings: AppSettings

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)

    settings = AppSettings(this)
    val group = findViewById<RadioGroup>(R.id.destination_provider_group)
    val geoapify = findViewById<RadioButton>(R.id.provider_geoapify)
    val places = findViewById<RadioButton>(R.id.provider_google_places)
    val status = findViewById<TextView>(R.id.settings_status)
    val done = findViewById<Button>(R.id.settings_done_button)

    when (settings.destinationProvider) {
      DestinationProvider.GEOAPIFY -> geoapify.isChecked = true
      DestinationProvider.GOOGLE_PLACES -> places.isChecked = true
    }
    status.text = "Current: ${settings.destinationProvider.label}"

    group.setOnCheckedChangeListener { _, checkedId ->
      val provider = when (checkedId) {
        R.id.provider_google_places -> DestinationProvider.GOOGLE_PLACES
        else -> DestinationProvider.GEOAPIFY
      }
      settings.destinationProvider = provider
      status.text = "Saved: ${provider.label}"
      Toast.makeText(this, "Using ${provider.label}", Toast.LENGTH_SHORT).show()
      // Also write to the live nav log if main activity process still has one — prefs are enough.
      android.util.Log.i("SettingsActivity", "DEST_PROVIDER_SAVED=${provider.storageValue}")
    }

    done.setOnClickListener { finish() }
  }
}
