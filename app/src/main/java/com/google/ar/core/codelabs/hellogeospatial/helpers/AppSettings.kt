package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context

enum class DestinationProvider(val storageValue: String, val label: String) {
  GEOAPIFY("geoapify", "Geoapify Geocoding"),
  GOOGLE_PLACES("google_places", "Google Places API");

  companion object {
    fun fromStorage(value: String?): DestinationProvider {
      return entries.firstOrNull { it.storageValue == value } ?: GEOAPIFY
    }
  }
}

class AppSettings(context: Context) {
  companion object {
    private const val PREFS = "blindnav_settings"
    private const val KEY_DESTINATION_PROVIDER = "destination_provider"
    private const val KEY_PATH_BUFFER_METERS = "path_buffer_meters"
  }

  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  var destinationProvider: DestinationProvider
    get() = DestinationProvider.fromStorage(prefs.getString(KEY_DESTINATION_PROVIDER, null))
    set(value) {
      prefs.edit().putString(KEY_DESTINATION_PROVIDER, value.storageValue).apply()
    }

  var pathBufferMeters: Double
    get() = prefs.getFloat(
      KEY_PATH_BUFFER_METERS,
      ArrowAlignedGuide.DEFAULT_BUFFER_METERS.toFloat(),
    ).toDouble().coerceIn(
      ArrowAlignedGuide.MIN_BUFFER_METERS.toDouble(),
      ArrowAlignedGuide.MAX_BUFFER_METERS.toDouble(),
    )
    set(value) {
      val meters = value.coerceIn(
        ArrowAlignedGuide.MIN_BUFFER_METERS.toDouble(),
        ArrowAlignedGuide.MAX_BUFFER_METERS.toDouble(),
      )
      prefs.edit().putFloat(KEY_PATH_BUFFER_METERS, meters.toFloat()).apply()
    }
}
