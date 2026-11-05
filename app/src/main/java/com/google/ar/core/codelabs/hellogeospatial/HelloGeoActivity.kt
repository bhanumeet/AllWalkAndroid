/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.ArrowAlignedGuide
import com.google.ar.core.codelabs.hellogeospatial.helpers.GeoPermissionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.codelabs.hellogeospatial.helpers.HapticGuide
import com.google.ar.core.codelabs.hellogeospatial.helpers.NavigationLogger
import com.google.ar.core.codelabs.hellogeospatial.helpers.SpeechGuide
import com.google.ar.core.codelabs.hellogeospatial.helpers.SpeakPriority
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.ar.core.codelabs.hellogeospatial.R
import java.util.Locale

class HelloGeoActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "MyCheck"
    private const val SOS_COOLDOWN_MS = 1_500L
    private const val SOS_LLM_TIMEOUT_MS = 20_000L
    const val EXTRA_DEST_LAT = "dest_lat"
    const val EXTRA_DEST_LNG = "dest_lng"
    const val EXTRA_DEST_NAME = "dest_name"
    const val EXTRA_DEST_QUERY = "dest_query"
  }

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloGeoView
  lateinit var renderer: HelloGeoRenderer
  lateinit var navLogger: NavigationLogger
  lateinit var speechGuide: SpeechGuide
  lateinit var hapticGuide: HapticGuide
  lateinit var navGuide: ArrowAlignedGuide
  lateinit var doorAssist: com.google.ar.core.codelabs.hellogeospatial.helpers.DoorAssist

  private lateinit var speechRecognizer: SpeechRecognizer
  private var lastSosMs = 0L
  /** True from volume-down until the LLM reply is spoken. */
  private var sosLlmPending = false
  private val sosHandler = Handler(Looper.getMainLooper())
  private val sosTimeout = Runnable {
    renderer.sosCapture = null
    onSosHelp(null)
  }

  /** Phone GPS, used only to center the map until VPS locks. */
  @Volatile var deviceLatLng: LatLng? = null
  @Volatile var deviceHeadingDeg: Double = 0.0

  private val guidanceHandler = Handler(Looper.getMainLooper())
  private val guidanceTick = object : Runnable {
    override fun run() {
      if (::renderer.isInitialized) renderer.tickCoarseGuidance()
      guidanceHandler.postDelayed(this, 1000L)
    }
  }
  private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
  private val locationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      val loc = result.lastLocation ?: return
      onDeviceFix(loc)
    }
  }
  private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
  private val rotationMatrix = FloatArray(9)
  private val remappedMatrix = FloatArray(9)
  private val orientationAngles = FloatArray(3)
  private val headingListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
      if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
      SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
      val rotation = windowManager.defaultDisplay.rotation
      val (axisX, axisY) = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
      }
      SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
      SensorManager.getOrientation(remappedMatrix, orientationAngles)
      var degrees = Math.toDegrees(orientationAngles[0].toDouble())
      if (degrees < 0) degrees += 360.0
      deviceHeadingDeg = degrees
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    navLogger = NavigationLogger(this)
    speechGuide = SpeechGuide(this, navLogger)
    hapticGuide = HapticGuide(this, navLogger)
    navGuide = ArrowAlignedGuide(speechGuide, navLogger, hapticGuide)
    doorAssist = com.google.ar.core.codelabs.hellogeospatial.helpers.DoorAssist(this, navLogger)
    if (
      Build.VERSION.SDK_INT <= 28 &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 41)
    }

    // Setup ARCore session lifecycle helper and configuration.
    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
    // If Session creation or Session.resume() fails, display a message and log detailed
    // information.
    arCoreSessionHelper.exceptionCallback =
      { exception ->
        val message =
          when (exception) {
            is UnavailableUserDeclinedInstallationException ->
              "Please install Google Play Services for AR"
            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update this app"
            is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
            is CameraNotAvailableException -> "Camera not available. Try restarting the app."
            else -> "Failed to create AR session: $exception"
          }
        Log.e(TAG, "ARCore threw an exception", exception)
        view.snackbarHelper.showError(this, message)
      }

    // Configure session features.
    arCoreSessionHelper.beforeSessionResume = ::configureSession
    lifecycle.addObserver(arCoreSessionHelper)

    // Set up the Hello AR renderer.
    renderer = HelloGeoRenderer(this)
    lifecycle.addObserver(renderer)

    // Set up Hello AR UI.
    view = HelloGeoView(this)
    lifecycle.addObserver(view)
    setContentView(view.root)
    view.setOnClickListener()

    setUpSpeechToText()
    if (intent.hasExtra(EXTRA_DEST_LAT) && intent.hasExtra(EXTRA_DEST_LNG)) {
      renderer.pendingDestination = LatLng(
        intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0),
        intent.getDoubleExtra(EXTRA_DEST_LNG, 0.0),
      )
      val destination = intent.getStringExtra(EXTRA_DEST_NAME).orEmpty()
      view.editText.setText(destination)
      navGuide.destinationLabel = destination
      navLogger.logTitle = intent.getStringExtra(EXTRA_DEST_QUERY).orEmpty().ifBlank { destination }
    }

    // Sets up an example renderer using our HelloGeoRenderer.
    SampleRender(view.surfaceView, renderer, assets)
  }

  fun setUpSpeechToText(){
    val editText = findViewById<EditText>(R.id.destination_text_input)
    val micButton = findViewById<ImageButton>(R.id.listen_button)
    val resetButton: Button = findViewById(R.id.reset_button)
    val resetVpsButton: Button = findViewById(R.id.reset_vps_button)
    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
    val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
    }
    speechRecognizer.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(bundle: Bundle) {}
      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(v: Float) {}
      override fun onBufferReceived(bytes: ByteArray) {}
      override fun onEndOfSpeech() {}
      override fun onError(i: Int) {}
      override fun onResults(bundle: Bundle) {
        val data = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        editText.setText(data!![0])
      }
      override fun onPartialResults(bundle: Bundle) {}
      override fun onEvent(i: Int, bundle: Bundle) {}
    })
    micButton.setOnTouchListener(object : View.OnTouchListener {
      override fun onTouch(view: View?, motionEvent: MotionEvent): Boolean {
        if (motionEvent.action == MotionEvent.ACTION_UP) {
          speechRecognizer.stopListening()
        }
        if (motionEvent.action == MotionEvent.ACTION_DOWN) {
          speechRecognizer.startListening(speechRecognizerIntent)
        }
        return false
      }
    })

    resetButton.setOnClickListener {
      reset()
      if (navLogger.saveToDownloads()) {
        Toast.makeText(this, "Log saved to Downloads/AllWalk", Toast.LENGTH_SHORT).show()
        navLogger.clearAndStartNewSession()
      }
      startActivity(
        Intent(this, DestinationSearchActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
          putExtra(DestinationSearchActivity.EXTRA_START_FRESH, true)
        },
      )
    }

    resetVpsButton.setOnClickListener {
      resetVpsLocalization()
    }

  }

  /** Drop a stuck Geospatial fix by recreating the ARCore session. The route stays. */
  fun resetVpsLocalization() {
    navLogger.logEvent("VPS_RESET", "Recreating ARCore session")
    speechGuide.speak(
      "Resetting localization. Point the camera at buildings.",
      priority = SpeakPriority.CRITICAL,
      flush = true,
    )
    try {
      renderer.earthAnchor?.detach()
    } catch (_: Exception) {
    }
    renderer.earthAnchor = null
    // Stop session.update() on the GL thread before closing the session.
    view.surfaceView.onPause()
    try {
      arCoreSessionHelper.restartSession()
      renderer.prepareForNewSession()
      Toast.makeText(this, "Localization reset. Look at building fronts.", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
      Log.e(TAG, "VPS reset failed", e)
      navLogger.logEvent("VPS_RESET", "failed: ${e.message}")
      Toast.makeText(this, "Localization reset failed", Toast.LENGTH_LONG).show()
    } finally {
      view.surfaceView.onResume()
    }
  }

  fun reset(keepSpeaking: Boolean = false){
    navLogger.logEvent("RESET", "Navigation reset keepSpeaking=$keepSpeaking")
    doorAssist.stop()
    navGuide.reset(stopSpeech = !keepSpeaking)
    renderer.isClick = false
    renderer.viewPoints.clear()
    renderer.destinationLatLng = null
    renderer.earthAnchor?.detach()
    renderer.earthAnchor = null
    view.mapView?.earthMarker?.apply {
      isVisible = false
    }
    view.mapView?.eraseLines()
    view.mapView?.polyline?.remove()
    view.mapView?.clearBuffer()
    view.mapView?.erasePoints()
    view.mapView?.setFirst = true
    view.editText.setText("")
    view.distance_text.text = ""
    view.clearTurnRadiusNote()
  }

  /**
   * Volume up = SOS re-orientation from the route. Volume down = SOS with the camera:
   * one frame goes to the LLM for a description of the surroundings.
   * Neither changes the system volume.
   */
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
      triggerSos()
      return true
    }
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
      triggerSosLlm()
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  private fun triggerSosLlm() {
    if (sosLlmPending) return
    val now = System.currentTimeMillis()
    if (now - lastSosMs < SOS_COOLDOWN_MS) return
    lastSosMs = now
    sosLlmPending = true
    hapticGuide.pulseSos()

    val earth = arCoreSessionHelper.session?.earth
    val status = if (earth == null || earth.trackingState != TrackingState.TRACKING) {
      "Location not available yet."
    } else {
      val pose = earth.cameraGeospatialPose
      navGuide.sosStatus(
        user = LatLng(pose.latitude, pose.longitude),
        headingDeg = pose.heading,
        path = view.mapView?.viewPointList.orEmpty(),
      )
    }
    navLogger.logEvent("SOS_LLM", "requested status=$status")
    speechGuide.speak("Checking your surroundings.", priority = SpeakPriority.CRITICAL, flush = true)
    renderer.sosCapture = status
    // No camera frame (app paused, camera busy): give up so the button works again.
    sosHandler.postDelayed(sosTimeout, SOS_LLM_TIMEOUT_MS)
  }

  /** Called on the UI thread with the LLM reply, or null when it failed. */
  fun onSosHelp(help: String?) {
    sosHandler.removeCallbacks(sosTimeout)
    if (!sosLlmPending) return
    sosLlmPending = false
    if (help.isNullOrBlank()) {
      navLogger.logEvent("SOS_LLM", "failed")
      speechGuide.speak(
        "Could not check your surroundings. Press volume up for your route.",
        priority = SpeakPriority.CRITICAL,
        flush = true,
      )
      return
    }
    navGuide.speakSosHelp(help)
  }

  private fun triggerSos() {
    val now = System.currentTimeMillis()
    if (now - lastSosMs < SOS_COOLDOWN_MS) return
    lastSosMs = now

    val earth = arCoreSessionHelper.session?.earth
    if (earth == null || earth.trackingState != TrackingState.TRACKING) {
      speechGuide.speak(
        "Location not available yet. Wait for tracking.",
        priority = SpeakPriority.CRITICAL,
        flush = true,
      )
      hapticGuide.pulseSos()
      navLogger.logEvent("SOS", "no geospatial tracking")
      return
    }

    val pose = earth.cameraGeospatialPose
    val user = LatLng(pose.latitude, pose.longitude)
    val path = view.mapView?.viewPointList.orEmpty()
    navGuide.speakSos(user = user, headingDeg = pose.heading, path = path)
  }

  override fun onResume() {
    super.onResume()
    startPhoneLocation()
    sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also { sensor ->
      sensorManager.registerListener(headingListener, sensor, SensorManager.SENSOR_DELAY_UI)
    }
    guidanceHandler.removeCallbacks(guidanceTick)
    guidanceHandler.post(guidanceTick)
  }

  override fun onPause() {
    guidanceHandler.removeCallbacks(guidanceTick)
    sensorManager.unregisterListener(headingListener)
    fusedLocation.removeLocationUpdates(locationCallback)
    super.onPause()
  }

  override fun onDestroy() {
    speechGuide.shutdown()
    if (::speechRecognizer.isInitialized) {
      speechRecognizer.destroy()
    }
    super.onDestroy()
  }

  private fun onDeviceFix(loc: android.location.Location) {
    val latLng = LatLng(loc.latitude, loc.longitude)
    deviceLatLng = latLng
    if (::view.isInitialized) view.mapView?.showUser(latLng)
  }

  private fun startPhoneLocation() {
    if (
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    try {
      fusedLocation.lastLocation.addOnSuccessListener { loc ->
        if (loc != null) onDeviceFix(loc)
      }
      val request = LocationRequest.create().apply {
        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        interval = 1000L
        fastestInterval = 1000L
      }
      fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    } catch (_: SecurityException) {
    }
  }

  // Configure the session, setting the desired options according to your usecase.
  fun configureSession(session: Session) {
    // TODO: Configure ARCore to use GeospatialMode.ENABLED.
    session.configure(
      session.config.apply {
        // Enable Geospatial Mode.
        geospatialMode = Config.GeospatialMode.ENABLED
      }
    )
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == HelloGeoView.REQUEST_SAVE_LOG && resultCode == RESULT_OK) {
      data?.data?.let { view.writeNavigationLog(it) }
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (!GeoPermissionsHelper.hasGeoPermissions(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera and location permissions are needed to run this application", Toast.LENGTH_LONG)
        .show()
      if (!GeoPermissionsHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        GeoPermissionsHelper.launchPermissionSettings(this)
      }
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
}
