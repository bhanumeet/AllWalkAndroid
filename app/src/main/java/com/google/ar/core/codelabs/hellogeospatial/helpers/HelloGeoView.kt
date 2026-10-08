package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.annotation.SuppressLint
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoActivity
import com.google.ar.core.codelabs.hellogeospatial.R
import com.google.ar.core.codelabs.hellogeospatial.SettingsActivity
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper
import java.io.IOException
import java.util.Locale

/** Contains UI elements for Hello Geo. */
class HelloGeoView(val activity: HelloGeoActivity) : DefaultLifecycleObserver {
  val root: View = View.inflate(activity, R.layout.activity_main, null)
  val surfaceView: GLSurfaceView = root.findViewById(R.id.surfaceview)
  val doorBoxOverlay: DoorBoxOverlay = root.findViewById(R.id.door_box_overlay)

  private val submitButton: Button = root.findViewById(R.id.submit_button)
  private val settingsButton: ImageButton = root.findViewById(R.id.settings_button)
  private val ocrButton: Button = root.findViewById(R.id.ocr_button)
  private val copyDestCoordsButton: Button = root.findViewById(R.id.copy_dest_coords_button)
  private val exportLogButton: Button = root.findViewById(R.id.export_log_button)
  val editText: EditText = root.findViewById(R.id.destination_text_input)

  val distance_text: TextView = root.findViewById(R.id.distance_textView)
  val find_text: TextView = root.findViewById(R.id.find_textview)
  private val bufferLabel: TextView = root.findViewById(R.id.buffer_label)
  private val providerStatus: TextView = root.findViewById(R.id.provider_status)
  private val bufferSlider: SeekBar = root.findViewById(R.id.buffer_slider)

  var ocrIsOn:Boolean = false

  val TAG = "HelloGeoView"
  private val appSettings = AppSettings(activity)
  private val destinationResolver = DestinationResolver(activity) { type, message ->
    activity.navLogger.logEvent(type, message)
  }
  private val session
    get() = activity.arCoreSessionHelper.session

  val snackbarHelper = SnackbarHelper()

  var mapView: MapView? = null
  val mapTouchWrapper = root.findViewById<MapTouchWrapper>(R.id.map_wrapper).apply {
    setup { screenLocation ->
      val latLng: LatLng =
        mapView?.googleMap?.projection?.fromScreenLocation(screenLocation) ?: return@setup
      activity.renderer.onMapClick(latLng)

      val geocoder = Geocoder(activity,Locale.getDefault())
      var address: List<Address>? = null
      try{
        address = geocoder.getFromLocation(latLng.latitude,latLng.longitude,1)
      }catch (e: IOException){
        Log.e(TAG,e.toString())
      }
    }
  }
  val mapFragment =
    (activity.supportFragmentManager.findFragmentById(R.id.map)!! as SupportMapFragment).also {
      it.getMapAsync { googleMap ->
        mapView = MapView(activity, googleMap).also { map ->
          map.setBufferMeters(activity.navGuide.corridorMeters, redraw = true)
        }
      }
    }

  init {
    setupBufferSlider()
    refreshProviderStatus()
  }

  fun refreshProviderStatus() {
    val provider = appSettings.destinationProvider
    providerStatus.text = when (provider) {
      DestinationProvider.GOOGLE_PLACES -> "Dest: Google Places"
      DestinationProvider.GEOAPIFY -> "Dest: Geoapify"
    }
  }

  private fun setupBufferSlider() {
    // SeekBar progress == meters. Min 1 so 0 never means "no buffer".
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      bufferSlider.min = ArrowAlignedGuide.MIN_BUFFER_METERS
    }
    bufferSlider.max = ArrowAlignedGuide.MAX_BUFFER_METERS

    val initial = appSettings.pathBufferMeters.toInt()
      .coerceIn(ArrowAlignedGuide.MIN_BUFFER_METERS, ArrowAlignedGuide.MAX_BUFFER_METERS)
    bufferSlider.progress = initial
    applyBufferMeters(initial.toDouble(), redrawMap = true, persist = false, log = false)

    bufferSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (!fromUser) return
        val meters = progress
          .coerceIn(ArrowAlignedGuide.MIN_BUFFER_METERS, ArrowAlignedGuide.MAX_BUFFER_METERS)
          .toDouble()
        // Live threshold for on-route checks; redraw map when finger lifts.
        applyBufferMeters(meters, redrawMap = false, persist = false, log = false)
      }

      override fun onStartTrackingTouch(seekBar: SeekBar?) {}

      override fun onStopTrackingTouch(seekBar: SeekBar?) {
        val meters = (seekBar?.progress ?: bufferSlider.progress)
          .coerceIn(ArrowAlignedGuide.MIN_BUFFER_METERS, ArrowAlignedGuide.MAX_BUFFER_METERS)
          .toDouble()
        applyBufferMeters(meters, redrawMap = true, persist = true, log = true)
      }
    })
  }

  private fun applyBufferMeters(
    meters: Double,
    redrawMap: Boolean,
    persist: Boolean,
    log: Boolean,
  ) {
    val clamped = meters.coerceIn(
      ArrowAlignedGuide.MIN_BUFFER_METERS.toDouble(),
      ArrowAlignedGuide.MAX_BUFFER_METERS.toDouble(),
    )
    val previous = activity.navGuide.corridorMeters
    activity.navGuide.corridorMeters = clamped
    if (previous != clamped) {
      activity.navGuide.onCorridorChanged()
    }
    mapView?.setBufferMeters(clamped, redraw = redrawMap)
    updateBufferLabel(clamped)
    if (persist) {
      appSettings.pathBufferMeters = clamped
    }
    if (log) {
      activity.navLogger.logBuffer(clamped)
    }
  }

  private fun updateBufferLabel(meters: Double) {
    bufferLabel.text = "Buffer: ${meters.toInt()} m"
  }

  fun setOnClickListener(){
    submitButton.setOnClickListener {
      val placeName = editText.text.toString().trim()
      val earth = session?.earth ?: return@setOnClickListener
      if (placeName.isEmpty()) {
        Toast.makeText(activity, "Enter a destination", Toast.LENGTH_SHORT).show()
        return@setOnClickListener
      }
      val cameraGeospatialPose = earth.cameraGeospatialPose
      val near = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
      val provider = appSettings.destinationProvider
      activity.navLogger.logEvent(
        "DEST_SEARCH",
        "settingsProvider=${provider.storageValue} label=${provider.label} query=$placeName " +
          "note=will_call_only_this_provider_no_fallback",
      )
      Toast.makeText(activity, "Searching with ${provider.label}…", Toast.LENGTH_SHORT).show()
      destinationResolver.resolve(
        provider = provider,
        placeName = placeName,
        near = near,
        onSuccess = { result ->
          activity.runOnUiThread {
            editText.setText(result.displayName)
            val msg =
              "VIA ${result.provider.label}: ${result.displayName} " +
                "(${"%.6f".format(Locale.US, result.latLng.latitude)}, " +
                "${"%.6f".format(Locale.US, result.latLng.longitude)})"
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            activity.renderer.onMapClick(result.latLng)
          }
        },
        onError = { message ->
          activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
          }
        },
      )
    }

    settingsButton.setOnClickListener {
      activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    ocrButton.setOnClickListener{
      ocrIsOn = !ocrIsOn
      if (ocrIsOn) {
        ocrButton.text = "OCR ON"
        ocrButton.setBackgroundResource(R.color.red)
      } else {
        ocrButton.text = "OCR OFF"
        ocrButton.setBackgroundResource(R.color.blue)
      }
    }

    copyDestCoordsButton.setOnClickListener {
      val dest = activity.renderer.destinationLatLng
      if (dest == null) {
        Toast.makeText(activity, "No destination set", Toast.LENGTH_SHORT).show()
        return@setOnClickListener
      }
      val coords = String.format(Locale.US, "%.8f, %.8f", dest.latitude, dest.longitude)
      val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText("destination_coords", coords))
      activity.navLogger.logEvent("COPY_DEST", "Copied destination coords: $coords")
      Toast.makeText(activity, "Copied: $coords", Toast.LENGTH_SHORT).show()
    }

    exportLogButton.setOnClickListener {
      exportNavigationLog()
    }

  }

  private fun exportNavigationLog() {
    try {
      val file = activity.navLogger.currentTableFile()
      if (!file.exists() || file.length() == 0L) {
        Toast.makeText(activity, "Log is empty", Toast.LENGTH_SHORT).show()
        return
      }
      activity.navLogger.logEvent(
        "EXPORT",
        "Exporting map table file=${file.name}",
      )
      val uri = FileProvider.getUriForFile(
        activity,
        "${activity.packageName}.fileprovider",
        file,
      )
      val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "BlindNav navigation log")
        putExtra(
          Intent.EXTRA_TEXT,
          "Columns: type, latitude, longitude. Types are path, walk, waypoint, and vps.",
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      activity.startActivity(Intent.createChooser(share, "Export navigation log"))
    } catch (e: Exception) {
      Log.e(TAG, "exportNavigationLog failed", e)
      Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }

  override fun onResume(owner: LifecycleOwner) {
    surfaceView.onResume()
    refreshProviderStatus()
  }

  override fun onPause(owner: LifecycleOwner) {
    surfaceView.onPause()
  }
}
