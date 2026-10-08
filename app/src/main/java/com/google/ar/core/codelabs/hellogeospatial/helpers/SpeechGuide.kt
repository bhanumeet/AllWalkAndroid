package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/** Higher rank wins. Drift is lowest and never interrupts anything else. */
enum class SpeakPriority(val rank: Int) {
  IDLE(0),
  DRIFT(1),
  NORMAL(2),
  CRITICAL(3), // SOS, arrived, etc.
}

/**
 * Spoken navigation guidance. Separate from SpeechRecognizer (destination STT).
 * Always on during an active route — no mute / tilt-to-pause.
 *
 * Priority: CRITICAL/NORMAL can preempt drift; drift never preempts anyone.
 */
class SpeechGuide(
  context: Context,
  private val logger: NavigationLogger? = null,
) : TextToSpeech.OnInitListener {
  companion object {
    private const val TAG = "SpeechGuide"
  }

  private val tts = TextToSpeech(context.applicationContext, this)
  @Volatile private var ready = false
  @Volatile private var speaking = false
  @Volatile private var activePriority: SpeakPriority = SpeakPriority.IDLE
  @Volatile private var activeUtteranceId: String? = null

  override fun onInit(status: Int) {
    if (status != TextToSpeech.SUCCESS) {
      Log.e(TAG, "TTS init failed: $status")
      ready = false
      return
    }
    val result = tts.setLanguage(Locale.US)
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
      tts.language = Locale.getDefault()
    }
    tts.setSpeechRate(0.92f)
    tts.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    )
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {
        speaking = true
      }

      override fun onDone(utteranceId: String?) {
        clearIfActive(utteranceId)
      }

      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) {
        clearIfActive(utteranceId)
      }
    })
    ready = true
  }

  private fun clearIfActive(utteranceId: String?) {
    if (utteranceId == null || utteranceId == activeUtteranceId) {
      speaking = false
      activePriority = SpeakPriority.IDLE
      activeUtteranceId = null
    }
  }

  fun isSpeaking(): Boolean = speaking

  fun speak(
    text: String,
    priority: SpeakPriority = SpeakPriority.NORMAL,
    flush: Boolean = false,
  ) {
    if (!ready || text.isBlank()) return

    // Drift is last: never interrupt, and skip while anything else is talking.
    if (priority == SpeakPriority.DRIFT) {
      if (speaking || activePriority.rank > SpeakPriority.DRIFT.rank) {
        logger?.logEvent("TTS_SKIP", "drift blocked by ${activePriority.name}: $text")
        return
      }
    } else if (speaking && priority.rank < activePriority.rank) {
      // Lower than what's already playing (e.g. normal while SOS) — drop.
      logger?.logEvent("TTS_SKIP", "${priority.name} blocked by ${activePriority.name}: $text")
      return
    }

    val shouldFlush =
      flush ||
        (speaking && priority.rank > activePriority.rank) ||
        (speaking && priority.rank >= SpeakPriority.NORMAL.rank && activePriority == SpeakPriority.DRIFT)

    logger?.logTts(text, shouldFlush)
    val mode = if (shouldFlush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
    val utteranceId = "nav-${priority.name}-${System.nanoTime()}"
    activePriority = priority
    activeUtteranceId = utteranceId
    speaking = true
    val params = Bundle()
    tts.speak(text, mode, params, utteranceId)
  }

  /** Only for route reset / teardown — not for temporary mute. */
  fun stop() {
    if (ready) tts.stop()
    speaking = false
    activePriority = SpeakPriority.IDLE
    activeUtteranceId = null
  }

  fun shutdown() {
    ready = false
    speaking = false
    activePriority = SpeakPriority.IDLE
    activeUtteranceId = null
    tts.stop()
    tts.shutdown()
  }
}
