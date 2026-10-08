package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.AudioManager
import android.media.Image
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Runs the DoorDetection model on ARCore camera frames once the user is
 * inside the destination radius, then beeps and speaks toward the door.
 */
class DoorAssist(
  private val activity: HelloGeoActivity,
  private val speech: SpeechGuide,
  private val logger: NavigationLogger?,
) {
  @Volatile var cue: String = ""

  private val executor = Executors.newSingleThreadExecutor()
  private val busy = AtomicBoolean(false)
  private var detector: DoorDetector? = null
  private var started = false
  private var tones: ToneGenerator? = null
  private var lastBeepMs = 0L
  private var lastSpeakMs = 0L
  private var lastPhrase = ""
  /** Door rectangle in ARCore IMAGE_PIXELS: left, top, right, bottom. */
  @Volatile private var imageBox: FloatArray? = null
  private var boxOnScreen = false

  fun ensureStarted() {
    if (started) return
    started = true
    cue = ""
    try {
      tones = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    } catch (error: Throwable) {
      Log.w(TAG, "ToneGenerator unavailable", error)
    }
    logger?.logEvent("DOOR", "assist started")
  }

  fun stopIfIdle() {
    if (started) stop()
  }

  fun wantsFrame(): Boolean = started && !busy.get()

  fun stop() {
    started = false
    cue = ""
    lastPhrase = ""
    imageBox = null
    boxOnScreen = false
    activity.view.doorBoxOverlay.clearBox()
    try {
      tones?.release()
    } catch (_: Throwable) {
    }
    tones = null
  }

  /** Map the latest door box onto the camera view. Call from the GL thread. */
  fun projectBox(frame: Frame) {
    val box = imageBox
    if (box == null) {
      if (boxOnScreen) {
        boxOnScreen = false
        activity.view.doorBoxOverlay.clearBox()
      }
      return
    }
    val input = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    input.put(
      floatArrayOf(
        box[0], box[1],
        box[2], box[1],
        box[2], box[3],
        box[0], box[3],
      ),
    )
    input.rewind()
    val output = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, input, Coordinates2d.VIEW, output)
    output.rewind()
    val coords = FloatArray(8)
    output.get(coords)
    var minX = coords[0]
    var minY = coords[1]
    var maxX = coords[0]
    var maxY = coords[1]
    var index = 2
    while (index < coords.size) {
      minX = min(minX, coords[index])
      maxX = max(maxX, coords[index])
      minY = min(minY, coords[index + 1])
      maxY = max(maxY, coords[index + 1])
      index += 2
    }
    boxOnScreen = true
    activity.view.doorBoxOverlay.setBox(minX, minY, maxX, maxY)
  }

  /** Copy the ARCore frame and detect off the GL thread. Closes [image]. */
  fun offer(image: Image, rotationDegrees: Int) {
    if (!started) {
      image.close()
      return
    }
    if (!busy.compareAndSet(false, true)) {
      image.close()
      return
    }
    val imageWidth = image.width
    val imageHeight = image.height
    val bitmap = try {
      if (image.format != ImageFormat.YUV_420_888) null else yuvToBitmap(image, rotationDegrees)
    } catch (error: Throwable) {
      Log.w(TAG, "Frame copy failed", error)
      null
    } finally {
      try {
        image.close()
      } catch (_: Throwable) {
      }
    }
    if (bitmap == null) {
      busy.set(false)
      return
    }
    executor.execute {
      try {
        val model = detector ?: DoorDetector(activity.applicationContext).also { detector = it }
        val result = model.detect(bitmap)
        onResult(result, bitmap.width, bitmap.height, rotationDegrees, imageWidth, imageHeight)
      } catch (error: Throwable) {
        Log.e(TAG, "Door detection failed", error)
      } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
        busy.set(false)
      }
    }
  }

  private fun onResult(
    result: FrameResult,
    uprightWidth: Int,
    uprightHeight: Int,
    rotationDegrees: Int,
    imageWidth: Int,
    imageHeight: Int,
  ) {
    val door = result.detections.maxByOrNull { it.box.width * it.box.height }
    if (door == null || result.width <= 0) {
      imageBox = null
      if (cue.isNotEmpty()) {
        cue = ""
        showCue()
      }
      return
    }
    val cropX = (uprightWidth - result.width) / 2f
    val cropY = (uprightHeight - result.height) / 2f
    val step = max(1, max(imageWidth, imageHeight) / MAX_EDGE)
    imageBox = uprightBoxToImagePixels(
      left = door.box.left + cropX,
      top = door.box.top + cropY,
      right = door.box.right + cropX,
      bottom = door.box.bottom + cropY,
      uprightWidth = uprightWidth,
      uprightHeight = uprightHeight,
      rotation = rotationDegrees,
      step = step,
    )
    val center = door.box.centerX / result.width
    val direction = when {
      center < 0.38f -> DoorDirection.LEFT
      center > 0.62f -> DoorDirection.RIGHT
      else -> DoorDirection.AHEAD
    }
    val phrase = when (direction) {
      DoorDirection.LEFT -> "Door to your left."
      DoorDirection.RIGHT -> "Door to your right."
      DoorDirection.AHEAD -> "Door ahead. Walk forward."
    }
    cue = phrase.removeSuffix(".")
    showCue()
    beep(direction)
    val now = SystemClock.elapsedRealtime()
    if (phrase != lastPhrase || now - lastSpeakMs >= SPEAK_INTERVAL_MS) {
      lastPhrase = phrase
      lastSpeakMs = now
      speech.speak(phrase, priority = SpeakPriority.NORMAL, flush = false)
      logger?.logEvent(
        "DOOR",
        "dir=$direction score=${"%.2f".format(door.score)} center=${"%.2f".format(center)}",
      )
    }
  }

  private fun beep(direction: DoorDirection) {
    val now = SystemClock.elapsedRealtime()
    val interval = if (direction == DoorDirection.AHEAD) BEEP_AHEAD_MS else BEEP_INTERVAL_MS
    if (now - lastBeepMs < interval) return
    lastBeepMs = now
    val tone = tones ?: return
    when (direction) {
      DoorDirection.LEFT -> tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
      DoorDirection.AHEAD -> tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
      DoorDirection.RIGHT -> {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
        executor.execute {
          try {
            Thread.sleep(180)
            if (started) tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
          } catch (_: InterruptedException) {
          }
        }
      }
    }
  }

  private fun showCue() {
    val text = cue
    activity.runOnUiThread {
      if (activity.navGuide.doorAssist) {
        activity.view.distance_text.text = if (text.isEmpty()) "Arrived" else text
      }
    }
  }

  private enum class DoorDirection { LEFT, AHEAD, RIGHT }

  companion object {
    private const val TAG = "DoorAssist"
    private const val BEEP_INTERVAL_MS = 1200L
    private const val BEEP_AHEAD_MS = 450L
    private const val SPEAK_INTERVAL_MS = 2500L
    private const val MAX_EDGE = 640

    private fun uprightBoxToImagePixels(
      left: Float,
      top: Float,
      right: Float,
      bottom: Float,
      uprightWidth: Int,
      uprightHeight: Int,
      rotation: Int,
      step: Int,
    ): FloatArray {
      val preW: Int
      val preH: Int
      if (rotation == 90 || rotation == 270) {
        preW = uprightHeight
        preH = uprightWidth
      } else {
        preW = uprightWidth
        preH = uprightHeight
      }
      val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
      val bounds = android.graphics.RectF(0f, 0f, preW.toFloat(), preH.toFloat())
      matrix.mapRect(bounds)
      matrix.postTranslate(-bounds.left, -bounds.top)
      val inverse = Matrix()
      if (!matrix.invert(inverse)) {
        return floatArrayOf(left * step, top * step, right * step, bottom * step)
      }
      val points = floatArrayOf(left, top, right, top, right, bottom, left, bottom)
      inverse.mapPoints(points)
      var minX = Float.POSITIVE_INFINITY
      var minY = Float.POSITIVE_INFINITY
      var maxX = Float.NEGATIVE_INFINITY
      var maxY = Float.NEGATIVE_INFINITY
      var index = 0
      while (index < points.size) {
        val x = points[index] * step
        val y = points[index + 1] * step
        minX = min(minX, x)
        maxX = max(maxX, x)
        minY = min(minY, y)
        maxY = max(maxY, y)
        index += 2
      }
      return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun yuvToBitmap(image: Image, rotationDegrees: Int): Bitmap {
      val width = image.width
      val height = image.height
      val step = max(1, max(width, height) / MAX_EDGE)
      val outW = width / step
      val outH = height / step
      val yPlane = image.planes[0]
      val uPlane = image.planes[1]
      val vPlane = image.planes[2]
      val yBuffer = yPlane.buffer
      val uBuffer = uPlane.buffer
      val vBuffer = vPlane.buffer
      val yRowStride = yPlane.rowStride
      val uvRowStride = uPlane.rowStride
      val uvPixelStride = uPlane.pixelStride
      val argb = IntArray(outW * outH)
      for (row in 0 until outH) {
        val srcRow = row * step
        for (col in 0 until outW) {
          val srcCol = col * step
          val y = yBuffer.get(srcRow * yRowStride + srcCol).toInt() and 0xFF
          val uvIndex = (srcRow / 2) * uvRowStride + (srcCol / 2) * uvPixelStride
          val u = (uBuffer.get(min(uvIndex, uBuffer.limit() - 1)).toInt() and 0xFF) - 128
          val v = (vBuffer.get(min(uvIndex, vBuffer.limit() - 1)).toInt() and 0xFF) - 128
          val r = (y + 1.370705f * v).toInt().coerceIn(0, 255)
          val g = (y - 0.337633f * u - 0.698001f * v).toInt().coerceIn(0, 255)
          val b = (y + 1.732446f * u).toInt().coerceIn(0, 255)
          argb[row * outW + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
      }
      val raw = Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888)
      if (rotationDegrees == 0) return raw
      val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
      return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
        if (it !== raw) raw.recycle()
      }
    }
  }
}
