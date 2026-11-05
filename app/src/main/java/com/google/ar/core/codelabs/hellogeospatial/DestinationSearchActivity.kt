package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.codelabs.hellogeospatial.helpers.AppSettings
import com.google.ar.core.codelabs.hellogeospatial.helpers.DestinationResolver
import com.google.ar.core.codelabs.hellogeospatial.helpers.DestinationResult
import com.google.maps.android.SphericalUtil
import java.util.Locale

/**
 * First screen: ask where to go, list a few nearby matches, then open navigation.
 */
class DestinationSearchActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
  companion object {
    const val EXTRA_START_FRESH = "start_fresh"
    private const val PERMISSION_REQUEST = 21
    private val NUMBER_WORDS = listOf(
      listOf("1", "one", "won", "first"),
      listOf("2", "two", "too", "to", "second"),
      listOf("3", "three", "tree", "free", "third"),
      listOf("4", "four", "for", "fore", "fourth"),
    )
  }

  private enum class Phase { ASK, RESULTS }

  private lateinit var input: EditText
  private lateinit var statusView: TextView
  private lateinit var resultsList: LinearLayout
  private lateinit var micIcon: ImageView
  private lateinit var micHint: TextView
  private lateinit var hearAgainButton: Button
  private lateinit var searchAgainButton: ImageButton

  private val appSettings by lazy { AppSettings(this) }
  private val resolver by lazy { DestinationResolver(this) }
  private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private var speechRecognizer: SpeechRecognizer? = null
  private var phase = Phase.ASK
  private var near: LatLng? = null
  private var shown: List<DestinationResult> = emptyList()
  private var greeted = false
  private var resumed = false
  private var listenAfterSpeech = false
  private var listenWhenResumed = false
  private var listening = false
  private val mainHandler = Handler(Looper.getMainLooper())
  private val listenRunnable = Runnable { startListening() }
  private var pendingSpeech: String? = null
  private var pendingShowStatus = true
  private var pendingLaunch: DestinationResult? = null
  /** What the user typed or said; names the saved log files. */
  private var lastQuery = ""
  private var keepSpeechOnPause = false
  private var searching = false
  private var openingNavigation = false
  private var askedPermissions = false
  private var pendingQuery: String? = null
  private var activeUtterance: String? = null
  private var ttsFailed = false

  private val locationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      val loc = result.lastLocation ?: return
      onLocationFix(LatLng(loc.latitude, loc.longitude))
      fused.removeLocationUpdates(this)
    }
  }

  private fun onLocationFix(latLng: LatLng) {
    if (near == null) near = latLng
    val query = pendingQuery ?: return
    if (searching) return
    pendingQuery = null
    runSearch(query)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_destination_search)
    input = findViewById(R.id.search_input)
    statusView = findViewById(R.id.search_status)
    resultsList = findViewById(R.id.results_list)
    micIcon = findViewById(R.id.mic_icon)
    micHint = findViewById(R.id.mic_hint)
    hearAgainButton = findViewById(R.id.hear_again_button)
    searchAgainButton = findViewById(R.id.search_again_button)

    tts = TextToSpeech(this, this)
    if (SpeechRecognizer.isRecognitionAvailable(this)) {
      speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
        it.setRecognitionListener(recognitionListener)
      }
    }

    findViewById<View>(R.id.screen_root).setOnClickListener { onSpeakTapped() }
    findViewById<View>(R.id.mic_pad).setOnClickListener { onSpeakTapped() }
    findViewById<ImageButton>(R.id.search_button).setOnClickListener { runSearch(input.text.toString()) }
    hearAgainButton.setOnClickListener { speakOptions(thenListen = false) }
    searchAgainButton.setOnClickListener { resetToAsk() }
    findViewById<ImageButton>(R.id.search_settings_button).setOnClickListener {
      startActivity(Intent(this, SettingsActivity::class.java))
    }
    input.setOnClickListener { stopListening() }
    input.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == EditorInfo.IME_ACTION_SEARCH) {
        runSearch(input.text.toString())
        true
      } else {
        false
      }
    }
  }

  override fun onInit(status: Int) {
    ttsReady = status == TextToSpeech.SUCCESS
    if (!ttsReady) {
      ttsFailed = true
      val launch = pendingLaunch
      if (launch != null) {
        pendingLaunch = null
        runOnUiThread { openNavigation(launch) }
      }
      return
    }
    val engine = tts ?: return
    val lang = engine.setLanguage(Locale.US)
    if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
      engine.language = Locale.getDefault()
    }
    engine.setSpeechRate(0.92f)
    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {}

      override fun onDone(utteranceId: String?) {
        runOnUiThread { onSpeechFinished(utteranceId) }
      }

      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) {
        runOnUiThread { onSpeechFinished(utteranceId) }
      }
    })
    val queued = pendingSpeech
    if (queued != null) {
      pendingSpeech = null
      speak(queued, listenAfterSpeech, updateStatus = pendingShowStatus)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (intent.getBooleanExtra(EXTRA_START_FRESH, false)) {
      intent.removeExtra(EXTRA_START_FRESH)
      resetToAsk()
    }
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    if (!hasLocationPermission() || !hasMicPermission()) {
      if (!askedPermissions) {
        askedPermissions = true
        ActivityCompat.requestPermissions(
          this,
          arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
          ),
          PERMISSION_REQUEST,
        )
      }
      return
    }
    ensureLocation()
    if (!greeted && pendingLaunch == null) {
      greeted = true
      speakPrompt()
    } else if (listenWhenResumed && pendingLaunch == null) {
      listenWhenResumed = false
      startListening()
    }
  }

  override fun onPause() {
    resumed = false
    mainHandler.removeCallbacks(listenRunnable)
    listening = false
    stopListening()
    if (keepSpeechOnPause) {
      keepSpeechOnPause = false
    } else {
      tts?.stop()
    }
    super.onPause()
  }

  override fun onDestroy() {
    fused.removeLocationUpdates(locationCallback)
    speechRecognizer?.destroy()
    tts?.shutdown()
    super.onDestroy()
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != PERMISSION_REQUEST) return
    if (hasLocationPermission()) {
      ensureLocation()
    }
    if (!greeted && hasLocationPermission()) {
      greeted = true
      if (hasMicPermission()) {
        speakPrompt()
      } else {
        speak("Microphone is off. Type where you want to go, then tap Search.", thenListen = false)
      }
    } else if (!hasLocationPermission()) {
      speak("Location is needed to find places near you.", thenListen = false)
    }
  }

  private fun speakPrompt() {
    phase = Phase.ASK
    speak("Where do you want to go? Tap the screen to speak.", thenListen = false, updateStatus = false)
  }

  private fun resetToAsk() {
    shown = emptyList()
    resultsList.removeAllViews()
    showMicPad(listeningHint = true)
    hearAgainButton.visibility = View.GONE
    input.setText("")
    phase = Phase.ASK
    speakPrompt()
  }

  private fun onSpeakTapped() {
    if (speechRecognizer == null) {
      speak("Voice input is not available. Type the place, then tap Search.", thenListen = false)
      return
    }
    if (!hasMicPermission()) {
      speak("Microphone permission is needed to speak a destination.", thenListen = false)
      return
    }
    tts?.stop()
    listenAfterSpeech = false
    // Stopping and starting in one tap makes the recognizer report busy and drop the number.
    mainHandler.removeCallbacks(listenRunnable)
    if (listening) {
      listening = false
      try {
        speechRecognizer?.cancel()
      } catch (_: Exception) {
      }
    }
    statusView.text = if (phase == Phase.RESULTS) "Listening for a number" else "Listening"
    mainHandler.postDelayed(listenRunnable, 300L)
  }

  private fun runSearch(raw: String) {
    val query = raw.trim()
    hideKeyboard()
    stopListening()
    if (query.isEmpty()) {
      speak("Say or type where you want to go. Tap the screen to speak.", thenListen = false)
      return
    }
    val origin = near
    if (origin == null) {
      pendingQuery = query
      ensureLocation()
      speak("Still finding your location.", thenListen = false)
      return
    }
    if (searching) return
    searching = true
    lastQuery = query
    phase = Phase.ASK
    statusView.text = "Searching for $query"
    speak("Searching for $query.", thenListen = false)
    val provider = appSettings.destinationProvider
    resolver.search(
      provider = provider,
      placeName = query,
      near = origin,
      onSuccess = { results ->
        runOnUiThread {
          searching = false
          showResults(results, origin)
        }
      },
      onError = {
        runOnUiThread {
          searching = false
          shown = emptyList()
          resultsList.removeAllViews()
          showMicPad(listeningHint = true)
          hearAgainButton.visibility = View.GONE
          val spoken = if (it.contains("No such place", ignoreCase = true)) {
            "No places found nearby. Try another name."
          } else {
            "Search failed. Try again."
          }
          statusView.text = spoken
          speak(spoken, thenListen = false)
        }
      },
    )
  }

  private fun showResults(results: List<DestinationResult>, origin: LatLng) {
    shown = results
    openingNavigation = false
    phase = Phase.RESULTS
    resultsList.removeAllViews()
    results.forEachIndexed { index, result ->
      resultsList.addView(optionRow(index, result, origin))
    }
    showMicPad(listeningHint = false)
    hearAgainButton.visibility = View.VISIBLE
    statusView.text = "Say a number"
    speakOptions(thenListen = false, updateStatus = false)
  }

  private fun showMicPad(listeningHint: Boolean) {
    micIcon.visibility = if (listeningHint) View.VISIBLE else View.GONE
    micHint.visibility = View.VISIBLE
    micHint.text = if (listeningHint) "Tap anywhere to speak" else "Tap to speak a number"
  }

  private fun optionRow(index: Int, result: DestinationResult, origin: LatLng): TextView {
    val meters = SphericalUtil.computeDistanceBetween(origin, result.latLng)
    val dist = distancePhrase(meters)
    val subtitle = listOf(dist, result.detail).filter { it.isNotBlank() }.joinToString(" · ")
    val spoken = spokenOption(index, result.name, dist, result.detail)
    return TextView(this).apply {
      text = "${index + 1}. ${result.name}\n$subtitle"
      textSize = 14f
      setTextColor(Color.parseColor("#1A1A1A"))
      maxLines = 2
      ellipsize = TextUtils.TruncateAt.END
      gravity = Gravity.START or Gravity.CENTER_VERTICAL
      setPadding(dp(2), dp(6), dp(2), dp(6))
      contentDescription = spoken
      isClickable = false
      isFocusable = false
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
      )
    }
  }

  private fun speakOptions(thenListen: Boolean, updateStatus: Boolean = true) {
    if (shown.isEmpty() || near == null) return
    val origin = near ?: return
    val countWord = when (shown.size) {
      1 -> "one place"
      2 -> "two places"
      3 -> "three places"
      else -> "four places"
    }
    val lines = shown.mapIndexed { index, result ->
      val meters = SphericalUtil.computeDistanceBetween(origin, result.latLng)
      spokenOption(index, result.name, distancePhrase(meters), result.detail)
    }
    speak(
      "I found $countWord. ${lines.joinToString(" ")} Tap the screen, then say a number.",
      thenListen = thenListen && hasMicPermission(),
      updateStatus = updateStatus,
    )
  }

  private fun spokenOption(index: Int, name: String, distance: String, detail: String): String {
    val number = listOf("One", "Two", "Three", "Four").getOrElse(index) { "${index + 1}" }
    val where = if (detail.isBlank()) "" else " $detail."
    return "$number. $name, $distance.$where"
  }

  private fun choose(index: Int) {
    if (openingNavigation) return
    val result = shown.getOrNull(index) ?: return
    val origin = near ?: return
    openingNavigation = true
    stopListening()
    listenAfterSpeech = false
    pendingLaunch = null
    keepSpeechOnPause = true
    val meters = SphericalUtil.computeDistanceBetween(origin, result.latLng)
    val street = result.detail.trim()
    val where = if (street.isBlank()) "" else ", $street"
    speak(
      "Opening navigation to ${result.name}$where, ${distancePhrase(meters)}.",
      thenListen = false,
    )
    openNavigation(result)
  }

  private fun openNavigation(result: DestinationResult) {
    startActivity(
      Intent(this, HelloGeoActivity::class.java).apply {
        putExtra(HelloGeoActivity.EXTRA_DEST_LAT, result.latLng.latitude)
        putExtra(HelloGeoActivity.EXTRA_DEST_LNG, result.latLng.longitude)
        putExtra(HelloGeoActivity.EXTRA_DEST_NAME, result.displayName)
        putExtra(HelloGeoActivity.EXTRA_DEST_QUERY, lastQuery)
      },
    )
  }

  private fun speak(text: String, thenListen: Boolean, updateStatus: Boolean = true) {
    if (updateStatus) statusView.text = text
    listenAfterSpeech = thenListen
    if (!ttsReady) {
      pendingSpeech = text
      pendingShowStatus = updateStatus
      return
    }
    val utteranceId = "search-${System.nanoTime()}"
    activeUtterance = utteranceId
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
  }

  private fun onSpeechFinished(utteranceId: String?) {
    if (utteranceId != null && utteranceId != activeUtterance) return
    val launch = pendingLaunch
    if (launch != null) {
      pendingLaunch = null
      listenAfterSpeech = false
      openNavigation(launch)
      return
    }
    if (listenAfterSpeech) {
      listenAfterSpeech = false
      if (resumed) startListening() else listenWhenResumed = true
    }
  }

  private fun startListening() {
    val recognizer = speechRecognizer ?: return
    if (!resumed || !hasMicPermission()) return
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        if (phase == Phase.RESULTS) {
          RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
        } else {
          RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        },
      )
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
    }
    statusView.text = if (phase == Phase.RESULTS) "Listening for a number" else "Listening"
    try {
      listening = true
      recognizer.startListening(intent)
    } catch (_: Exception) {
      listening = false
      statusView.text = "Voice input failed. Type a place, then tap Search."
    }
  }

  private fun stopListening() {
    try {
      speechRecognizer?.stopListening()
    } catch (_: Exception) {
    }
  }

  private val recognitionListener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
      listening = true
    }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {
      if (phase != Phase.RESULTS) return
      val index = indexFromResults(partialResults) ?: return
      choose(index)
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onError(error: Int) {
      listening = false
      if (!resumed) return
      if (error == SpeechRecognizer.ERROR_CLIENT) return
      if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
        mainHandler.removeCallbacks(listenRunnable)
        mainHandler.postDelayed(listenRunnable, 400L)
        return
      }
      statusView.text = if (phase == Phase.RESULTS) {
        "Say one, two, three, or four."
      } else {
        "I didn't hear a place. Tap the screen to speak, or type it and tap Search."
      }
    }

    override fun onResults(results: Bundle?) {
      listening = false
      val heard = results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        .orEmpty()
      if (phase == Phase.RESULTS) {
        val index = indexFromResults(results)
        if (index != null) {
          choose(index)
        } else if (heard.isNotEmpty()) {
          statusView.text = "Heard $heard. Say one, two, three, or four."
        } else {
          onError(SpeechRecognizer.ERROR_NO_MATCH)
        }
      } else if (heard.isEmpty()) {
        onError(SpeechRecognizer.ERROR_NO_MATCH)
      } else {
        input.setText(heard)
        runSearch(heard)
      }
    }
  }

  private fun indexFromResults(results: Bundle?): Int? {
    val guesses = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    for (guess in guesses) {
      optionIndex(guess)?.let { return it }
    }
    return null
  }

  private fun optionIndex(speech: String): Int? {
    val text = speech.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").trim()
    if (text.isEmpty()) return null
    val tokens = text.split(Regex("\\s+"))
    NUMBER_WORDS.forEachIndexed { index, words ->
      if (index >= shown.size) return@forEachIndexed
      if (tokens.any { it in words }) return index
    }
    shown.forEachIndexed { index, result ->
      val name = result.name.lowercase(Locale.US)
      if (name.length > 3 && text.contains(name)) return index
    }
    return null
  }

  private fun ensureLocation() {
    if (!hasLocationPermission() || near != null) return
    try {
      fused.lastLocation.addOnSuccessListener { loc ->
        if (loc != null) onLocationFix(LatLng(loc.latitude, loc.longitude))
      }
      val request = LocationRequest.create().apply {
        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        interval = 1000L
        numUpdates = 1
      }
      fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    } catch (_: SecurityException) {
    }
  }

  private fun hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
  }

  private fun hasMicPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
  }

  private fun hideKeyboard() {
    val imm = getSystemService(InputMethodManager::class.java)
    imm?.hideSoftInputFromWindow(input.windowToken, 0)
  }

  private fun distancePhrase(meters: Double): String {
    val rounded = meters.toInt()
    return if (rounded < 1000) {
      "$rounded meters"
    } else {
      String.format(Locale.US, "%.1f kilometers", rounded / 1000.0)
    }
  }

  private fun dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
  }
}
