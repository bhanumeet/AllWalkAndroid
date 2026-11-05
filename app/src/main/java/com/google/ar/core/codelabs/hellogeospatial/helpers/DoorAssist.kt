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
 * inside the destination radius, then beeps when a door is in view.
 */
class DoorAssist(
  private val activity: HelloGeoActivity,
  private val logger: NavigationLogger?,
) {
  @Volatile var cue: String = ""

  private val executor = Executors.newSingleThreadExecutor()
  private val busy = AtomicBoolean(false)
  private var detector: DoorDetector? = null
  private val vlm = VlmDoorClient()
  /** Read from settings each time door finding starts. */
  @Volatile private var useVlm = false
  /** Consecutive VLM yes answers; beep only after [VLM_YES_IN_ROW]. */
  private var vlmYesInRow = 0
  private var started = false
  private var tones: ToneGenerator? = null
  private var lastBeepMs = 0L
  /** Door rectangle in ARCore IMAGE_PIXELS: left, top, right, bottom. */
  @Volatile private var imageBox: FloatArray? = null
  private var boxOnScreen = false

  fun ensureStarted() {
    if (started) return
    started = true
    cue = ""
    vlmYesInRow = 0
    useVlm = AppSettings(activity).doorDetector == DoorDetectorMode.VLM
    try {
      tones = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    } catch (error: Throwable) {
      Log.w(TAG, "ToneGenerator unavailable", error)
    }
    logger?.logEvent("DOOR", "assist started detector=${if (useVlm) "vlm" else "yolo"}")
  }

  fun stopIfIdle() {
    if (started) stop()
  }

  fun wantsFrame(): Boolean = started && !busy.get()

  fun stop() {
    started = false
    cue = ""
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
    if (useVlm) {
      executor.execute {
        try {
          checkWithVlm(bitmap)
        } finally {
          if (!bitmap.isRecycled) bitmap.recycle()
          busy.set(false)
        }
      }
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

  /** One yes/no Decisions call per frame; the next frame goes when the reply is back. */
  private fun checkWithVlm(bitmap: Bitmap) {
    val probability = vlm.doorProbability(bitmap)
    if (!started) return
    if (probability == null) {
      logger?.logEvent("DOOR", "vlm failed: ${vlm.lastFailure}")
      // Back off so a missing key or no signal does not retry every frame.
      Thread.sleep(VLM_RETRY_MS)
      return
    }
    if (probability >= VlmDoorClient.DOOR_THRESHOLD) {
      vlmYesInRow++
      logger?.logEvent("DOOR", "vlm yes p=${"%.2f".format(probability)} in_row=$vlmYesInRow")
      if (vlmYesInRow >= VLM_YES_IN_ROW) beep()
    } else {
      vlmYesInRow = 0
      logger?.logEvent("DOOR", "vlm no p=${"%.2f".format(probability)}")
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
    beep()
    logger?.logEvent("DOOR", "seen score=${"%.2f".format(door.score)}")
  }

  private fun beep() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastBeepMs < BEEP_INTERVAL_MS) return
    lastBeepMs = now
    tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
  }

  private fun showCue() {
    val text = cue
    activity.runOnUiThread {
      if (activity.navGuide.doorAssist) {
        activity.view.distance_text.text = if (text.isEmpty()) "Arrived" else text
      }
    }
  }

  companion object {
    private const val TAG = "DoorAssist"
    private const val BEEP_INTERVAL_MS = 1200L
    private const val VLM_RETRY_MS = 2_000L
    private const val VLM_YES_IN_ROW = 2
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
