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

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    navLogger = NavigationLogger(this)
    speechGuide = SpeechGuide(this, navLogger)
    hapticGuide = HapticGuide(this, navLogger)
    navGuide = ArrowAlignedGuide(speechGuide, navLogger, hapticGuide)
    doorAssist = com.google.ar.core.codelabs.hellogeospatial.helpers.DoorAssist(this, speechGuide, navLogger)

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
  }

  /** Volume up/down = SOS re-orientation (does not change system volume). */
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
      triggerSos()
      return true
    }
    return super.onKeyDown(keyCode, event)
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

  override fun onDestroy() {
    speechGuide.shutdown()
    if (::speechRecognizer.isInitialized) {
      speechRecognizer.destroy()
    }
    super.onDestroy()
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
