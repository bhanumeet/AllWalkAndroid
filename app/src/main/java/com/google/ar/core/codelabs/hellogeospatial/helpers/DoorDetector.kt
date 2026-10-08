package com.google.ar.core.codelabs.hellogeospatial.helpers

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv5 door detector (640×640) from the DoorDetection project.
 * Same model and thresholds: door640.onnx, confidence 0.35.
 */
class DoorDetector(context: Context) : AutoCloseable {

  private val environment = OrtEnvironment.getEnvironment()
  private val session: OrtSession
  @Volatile private var closed = false
  private val input = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
  private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
  private val letterbox = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
  private val letterboxCanvas = Canvas(letterbox)
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val destination = android.graphics.RectF()

  init {
    val modelFile = copyModel(context)
    val options = OrtSession.SessionOptions()
    options.setIntraOpNumThreads(4)
    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    try {
      options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
    } catch (error: Throwable) {
      Log.w(TAG, "XNNPACK unavailable, using the default CPU backend", error)
    }
    session = options.use { environment.createSession(modelFile.absolutePath, it) }
    Log.i(TAG, "Loaded door model from ${modelFile.absolutePath}")
  }

  fun detect(source: Bitmap): FrameResult {
    if (closed) return FrameResult(emptyList(), source.width, source.height, 0f)
    val square = source.toSquare()
    try {
      val sourceWidth = square.width.toFloat()
      val sourceHeight = square.height.toFloat()
      val scale = min(INPUT_SIZE / sourceWidth, INPUT_SIZE / sourceHeight)
      val drawnWidth = sourceWidth * scale
      val drawnHeight = sourceHeight * scale
      val padX = (INPUT_SIZE - drawnWidth) / 2f
      val padY = (INPUT_SIZE - drawnHeight) / 2f

      letterboxCanvas.drawColor(LETTERBOX_COLOR)
      destination.set(padX, padY, padX + drawnWidth, padY + drawnHeight)
      letterboxCanvas.drawBitmap(square, null, destination, paint)
      letterbox.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
      fillInput(pixels, input)

      val candidates = ArrayList<Detection>()
      val bestScore = OnnxTensor.createTensor(
        environment,
        FloatBuffer.wrap(input),
        longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
      ).use { tensor ->
        session.run(mapOf(INPUT_NAME to tensor)).use { results ->
          val output = results[0] as OnnxTensor
          readDetections(output, padX, padY, scale, candidates)
        }
      }
      val kept = nonMaxSuppression(candidates, IOU_THRESHOLD).map { detection ->
        detection.copy(box = detection.box.clamp(sourceWidth, sourceHeight))
      }
      return FrameResult(kept, square.width, square.height, bestScore)
    } finally {
      if (square !== source && !square.isRecycled) square.recycle()
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    session.close()
    if (!letterbox.isRecycled) letterbox.recycle()
  }

  private fun readDetections(
    output: OnnxTensor,
    padX: Float,
    padY: Float,
    scale: Float,
    into: MutableList<Detection>,
  ): Float {
    val shape = output.info.shape
    val dim1 = shape[1].toInt()
    val dim2 = shape[2].toInt()
    val rowMajor = dim1 > dim2
    val anchors = if (rowMajor) dim1 else dim2
    val attributes = if (rowMajor) dim2 else dim1
    check(attributes >= 5) { "Unexpected model output shape ${shape.contentToString()}" }
    val values = output.floatBuffer
    var bestScore = 0f
    for (anchor in 0 until anchors) {
      fun at(attribute: Int): Float {
        val index = if (rowMajor) anchor * attributes + attribute else attribute * anchors + anchor
        return values.get(index)
      }
      val objectness = if (attributes >= 6) at(4) else 1f
      var classScore = at(if (attributes >= 6) 5 else 4)
      val firstClass = if (attributes >= 6) 6 else 5
      for (attribute in firstClass until attributes) {
        classScore = max(classScore, at(attribute))
      }
      val score = objectness * classScore
      if (score > bestScore) bestScore = score
      if (score < CONFIDENCE_THRESHOLD) continue
      val box = mapBoxToSource(at(0), at(1), at(2), at(3), padX, padY, scale)
      if (box.width < 4f || box.height < 4f) continue
      into.add(Detection(box, score))
    }
    return bestScore
  }

  companion object {
    private const val TAG = "DoorDetector"
    private const val MODEL_ASSET = "door640.onnx"
    const val INPUT_SIZE = 640
    const val CONFIDENCE_THRESHOLD = 0.35f
    private const val IOU_THRESHOLD = 0.45f
    private const val INPUT_NAME = "images"
    private val LETTERBOX_COLOR = Color.rgb(114, 114, 114)

    private fun copyModel(context: Context): File {
      val file = File(context.filesDir, MODEL_ASSET)
      if (file.exists() && file.length() > 1_000_000L) return file
      val temporary = File(context.filesDir, "$MODEL_ASSET.tmp")
      context.assets.open(MODEL_ASSET).use { input ->
        FileOutputStream(temporary).use { output -> input.copyTo(output) }
      }
      if (file.exists()) file.delete()
      if (!temporary.renameTo(file)) {
        temporary.copyTo(file, overwrite = true)
        temporary.delete()
      }
      return file
    }
  }
}

data class Detection(val box: Box, val score: Float)

data class Box(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top
  val centerX: Float get() = (left + right) / 2f

  fun clamp(maxWidth: Float, maxHeight: Float): Box {
    return Box(
      left.coerceIn(0f, maxWidth),
      top.coerceIn(0f, maxHeight),
      right.coerceIn(0f, maxWidth),
      bottom.coerceIn(0f, maxHeight),
    )
  }
}

data class FrameResult(
  val detections: List<Detection>,
  val width: Int,
  val height: Int,
  val bestScore: Float,
)

private fun mapBoxToSource(
  cx: Float,
  cy: Float,
  width: Float,
  height: Float,
  padX: Float,
  padY: Float,
  scale: Float,
): Box {
  return Box(
    (cx - width / 2f - padX) / scale,
    (cy - height / 2f - padY) / scale,
    (cx + width / 2f - padX) / scale,
    (cy + height / 2f - padY) / scale,
  )
}

private fun nonMaxSuppression(candidates: List<Detection>, iouThreshold: Float): List<Detection> {
  if (candidates.isEmpty()) return emptyList()
  val sorted = candidates.sortedByDescending { it.score }
  val removed = BooleanArray(sorted.size)
  val kept = ArrayList<Detection>()
  for (i in sorted.indices) {
    if (removed[i]) continue
    val current = sorted[i]
    kept.add(current)
    for (j in i + 1 until sorted.size) {
      if (!removed[j] && iou(current.box, sorted[j].box) > iouThreshold) removed[j] = true
    }
  }
  return kept
}

private fun iou(first: Box, second: Box): Float {
  val left = max(first.left, second.left)
  val top = max(first.top, second.top)
  val right = min(first.right, second.right)
  val bottom = min(first.bottom, second.bottom)
  val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
  val union = first.width * first.height + second.width * second.height - intersection
  if (union <= 0f) return 0f
  return intersection / union
}

private fun fillInput(pixels: IntArray, input: FloatArray) {
  val plane = DoorDetector.INPUT_SIZE * DoorDetector.INPUT_SIZE
  for (index in 0 until plane) {
    val pixel = pixels[index]
    input[index] = ((pixel shr 16) and 0xFF) / 255f
    input[plane + index] = ((pixel shr 8) and 0xFF) / 255f
    input[plane * 2 + index] = (pixel and 0xFF) / 255f
  }
}

private fun Bitmap.toSquare(): Bitmap {
  val side = min(width, height)
  val left = (width - side) / 2
  val top = (height - side) / 2
  if (left == 0 && top == 0 && side == width) return this
  return Bitmap.createBitmap(this, left, top, side, side)
}
