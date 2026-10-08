package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptics for blind navigation.
 *  - Left now  → 1 short pulse
 *  - Right now → 2 short pulses
 *  - Drift     → continuous vibration (same for left/right)
 *  - Arrived   → long strong pattern
 */
class HapticGuide(
  context: Context,
  private val logger: NavigationLogger? = null,
) {
  private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
    manager?.defaultVibrator
  } else {
    @Suppress("DEPRECATION")
    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
  }

  @Volatile private var driftActive = false

  fun pulseLeftNow() {
    // Interrupt drift briefly so the turn cue is clear; guide will restart drift next tick.
    cancelInternal()
    vibrateOnce(longArrayOf(0, PULSE_MS), intArrayOf(0, 255), "left_now")
  }

  fun pulseRightNow() {
    cancelInternal()
    vibrateOnce(
      longArrayOf(0, PULSE_MS, GAP_MS, PULSE_MS),
      intArrayOf(0, 255, 0, 255),
      "right_now",
    )
  }

  fun pulseArrived() {
    cancelInternal()
    vibrateOnce(
      longArrayOf(0, 450, 120, 450, 120, 700),
      intArrayOf(0, 255, 0, 255, 0, 255),
      "arrived",
    )
  }

  fun pulseSos() {
    cancelInternal()
    vibrateOnce(
      longArrayOf(0, 120, 80, 120, 80, 350),
      intArrayOf(0, 255, 0, 255, 0, 255),
      "sos",
    )
  }

  /**
   * Continuous off-buffer haptic.
   * @param restart force re-issue the waveform (OS / turn pulses can cancel a looping vibe).
   */
  fun startDrift(restart: Boolean = false) {
    if (driftActive && !restart) return
    driftActive = true
    vibrateRepeating(
      timings = longArrayOf(220, 180),
      amplitudes = intArrayOf(255, 0),
      label = if (restart) "drift_restart" else "drift",
    )
  }

  fun stopDrift() {
    if (!driftActive) {
      vibrator?.cancel()
      return
    }
    cancelInternal()
    logger?.logEvent("HAPTIC", "drift_stop")
  }

  fun cancelAll() {
    cancelInternal()
  }

  private fun cancelInternal() {
    driftActive = false
    vibrator?.cancel()
  }

  private fun vibrateOnce(timings: LongArray, amplitudes: IntArray, label: String) {
    vibrate(timings, amplitudes, repeat = -1, label = label)
  }

  private fun vibrateRepeating(timings: LongArray, amplitudes: IntArray, label: String) {
    vibrate(timings, amplitudes, repeat = 0, label = label)
  }

  private fun vibrate(timings: LongArray, amplitudes: IntArray, repeat: Int, label: String) {
    val vib = vibrator ?: return
    if (!vib.hasVibrator()) return
    logger?.logEvent("HAPTIC", label)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val effect = if (vib.hasAmplitudeControl()) {
        VibrationEffect.createWaveform(timings, amplitudes, repeat)
      } else {
        VibrationEffect.createWaveform(timings, repeat)
      }
      vib.vibrate(effect)
    } else {
      @Suppress("DEPRECATION")
      vib.vibrate(timings, repeat)
    }
  }

  companion object {
    private const val PULSE_MS = 220L
    private const val GAP_MS = 120L
  }
}
