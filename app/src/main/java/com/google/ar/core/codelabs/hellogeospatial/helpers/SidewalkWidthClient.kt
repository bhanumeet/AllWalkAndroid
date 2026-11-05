package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.google.ar.core.codelabs.hellogeospatial.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * One photo at an approaching turn. The model answers narrow, medium, or broad.
 */
/** Spoken arrival line and the sidewalk width at the destination. */
data class DestinationReply(val line: String?, val width: String?)

class SidewalkWidthClient(private val context: Context) {
  @Volatile var lastFailure: String? = null
  @Volatile var lastDestinationFailure: String? = null
  private val executor = Executors.newSingleThreadExecutor()
  private val http = OkHttpClient.Builder()
    .callTimeout(25, TimeUnit.SECONDS)
    .build()

  fun classify(
    image: Image,
    rotationDegrees: Int,
    onSaved: (Boolean) -> Unit,
    onResult: (String?) -> Unit,
  ) {
    val key = BuildConfig.OPENAI_API_KEY
    if (key.isBlank()) {
      image.close()
      lastFailure = "no key"
      Log.w(TAG, "No OpenAI key in local.properties; turn radius stays 2 m")
      onResult(null)
      return
    }
    lastFailure = null
    val jpeg = try {
      jpegBytes(image, rotationDegrees)
    } catch (error: Throwable) {
      Log.w(TAG, "Could not encode sidewalk frame", error)
      lastFailure = "could not read photo"
      null
    } finally {
      try {
        image.close()
      } catch (_: Throwable) {
      }
    }
    if (jpeg == null) {
      if (lastFailure == null) lastFailure = "could not read photo"
      onResult(null)
      return
    }
    executor.execute {
      onSaved(saveToGallery(jpeg, "sidewalk"))
      try {
        onResult(requestWidth(key, jpeg))
      } catch (error: Throwable) {
        lastFailure = error.message?.take(120) ?: "request failed"
        Log.e(TAG, "Sidewalk width request failed", error)
        onResult(null)
      }
    }
  }

  /**
   * One photo about 15 m from the destination.
   * The reply confirms the place and describes how to physically get in.
   * [onResult] gets the spoken line and the sidewalk width (narrow, medium, or broad).
   */
  fun describeDestination(
    image: Image,
    rotationDegrees: Int,
    destination: String,
    onResult: (DestinationReply?) -> Unit,
  ) {
    val key = BuildConfig.OPENAI_API_KEY
    if (key.isBlank()) {
      image.close()
      lastDestinationFailure = "Destination check failed."
      Log.w(TAG, "No OpenAI key; destination photo skipped")
      onResult(null)
      return
    }
    lastDestinationFailure = null
    val jpeg = try {
      jpegBytes(image, rotationDegrees)
    } catch (error: Throwable) {
      Log.w(TAG, "Could not encode destination frame", error)
      null
    } finally {
      try {
        image.close()
      } catch (_: Throwable) {
      }
    }
    if (jpeg == null) {
      lastDestinationFailure = "Destination check failed."
      onResult(null)
      return
    }
    executor.execute {
      saveToGallery(jpeg, "destination")
      try {
        onResult(requestDestinationCue(key, jpeg, destination))
      } catch (error: Throwable) {
        lastDestinationFailure = destinationFailure(error.message.orEmpty())
        Log.e(TAG, "Destination cue request failed", error)
        onResult(null)
      }
    }
  }

  /** Same JPEG bytes that are uploaded, stored in Pictures/AllWalk. */
  private fun saveToGallery(jpeg: ByteArray, kind: String): Boolean {
    return try {
      val name = "allwalk_${kind}_${FILE_TIME.format(Date())}.jpg"
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AllWalk")
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
      }
      val resolver = context.contentResolver
      val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      if (uri == null) {
        Log.e(TAG, "Gallery insert failed")
        return false
      }
      resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: return false
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
      }
      Log.i(TAG, "Saved sidewalk photo $name")
      true
    } catch (error: Throwable) {
      Log.e(TAG, "Could not save sidewalk photo", error)
      false
    }
  }

  /**
   * Volume-down SOS: one photo plus the current navigation status.
   * [onResult] gets a short spoken line, or null when the request failed.
   */
  fun describeSos(
    image: Image,
    rotationDegrees: Int,
    destination: String,
    status: String,
    onResult: (String?) -> Unit,
  ) {
    val key = BuildConfig.OPENAI_API_KEY
    val jpeg = try {
      if (key.isBlank()) null else jpegBytes(image, rotationDegrees)
    } catch (error: Throwable) {
      Log.w(TAG, "Could not encode SOS frame", error)
      null
    } finally {
      try {
        image.close()
      } catch (_: Throwable) {
      }
    }
    if (jpeg == null) {
      onResult(null)
      return
    }
    executor.execute {
      saveToGallery(jpeg, "sos")
      try {
        onResult(requestSosHelp(key, jpeg, destination, status))
      } catch (error: Throwable) {
        Log.e(TAG, "SOS request failed", error)
        onResult(null)
      }
    }
  }

  private fun requestSosHelp(
    key: String,
    jpeg: ByteArray,
    destination: String,
    status: String,
  ): String? {
    val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
    val place = destination.ifBlank { "not set" }
    val body = JSONObject()
      .put("model", "gpt-4o")
      .put(
        "messages",
        JSONArray()
          .put(JSONObject().put("role", "system").put("content", SOS_PROMPT))
          .put(
            JSONObject()
              .put("role", "user")
              .put(
                "content",
                JSONArray()
                  .put(
                    JSONObject()
                      .put("type", "text")
                      .put(
                        "text",
                        "Destination: $place. Navigation status: $status " +
                          "The person pressed SOS. Using the photo, help them now.",
                      ),
                  )
                  .put(
                    JSONObject()
                      .put("type", "image_url")
                      .put("image_url", JSONObject().put("url", dataUrl)),
                  ),
              ),
          ),
      )
      .put("max_tokens", 150)
    val request = Request.Builder()
      .url("https://api.openai.com/v1/chat/completions")
      .addHeader("Authorization", "Bearer $key")
      .post(body.toString().toRequestBody(JSON_MEDIA))
      .build()
    http.newCall(request).execute().use { response ->
      val payload = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        Log.e(TAG, "SOS status ${response.code}: $payload")
        return null
      }
      val content = JSONObject(payload)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")
      return content.replace(Regex("[\"\\n]"), " ").trim().replace(Regex("\\s+"), " ")
        .ifBlank { null }
    }
  }

  private fun destinationFailure(detail: String): String {
    val text = detail.lowercase(Locale.US)
    return if ("credit" in text || "quota" in text || "billing" in text) {
      "Destination check failed. No credits."
    } else {
      "Destination check failed."
    }
  }

  private fun sidewalkFailure(code: Int, detail: String): String {
    val text = detail.lowercase(Locale.US)
    return when {
      code == 429 || "credit" in text || "quota" in text || "billing" in text ->
        "OpenAI account has no credits"
      code == 401 -> "OpenAI key was rejected"
      else -> "OpenAI request failed"
    }
  }

  private fun requestWidth(key: String, jpeg: ByteArray): String? {
    val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
    val schema = JSONObject()
      .put("type", "object")
      .put("additionalProperties", false)
      .put(
        "properties",
        JSONObject().put(
          "width",
          JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("narrow").put("medium").put("broad")),
        ),
      )
      .put("required", JSONArray().put("width"))
    val body = JSONObject()
      .put("model", "gpt-4o")
      .put(
        "messages",
        JSONArray()
          .put(
            JSONObject()
              .put("role", "system")
              .put("content", SYSTEM_PROMPT),
          )
          .put(
            JSONObject()
              .put("role", "user")
              .put(
                "content",
                JSONArray()
                  .put(JSONObject().put("type", "text").put("text", "How wide is this sidewalk?"))
                  .put(
                    JSONObject()
                      .put("type", "image_url")
                      .put("image_url", JSONObject().put("url", dataUrl)),
                  ),
              ),
          ),
      )
      .put(
        "response_format",
        JSONObject()
          .put("type", "json_schema")
          .put(
            "json_schema",
            JSONObject()
              .put("name", "sidewalk_width")
              .put("strict", true)
              .put("schema", schema),
          ),
      )
      .put("max_tokens", 20)
    val request = Request.Builder()
      .url("https://api.openai.com/v1/chat/completions")
      .addHeader("Authorization", "Bearer $key")
      .post(body.toString().toRequestBody(JSON_MEDIA))
      .build()
    http.newCall(request).execute().use { response ->
      val payload = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val detail = runCatching {
          JSONObject(payload).getJSONObject("error").getString("message")
        }.getOrDefault("status ${response.code}")
        Log.e(TAG, "OpenAI status ${response.code}: $detail")
        throw IllegalStateException(sidewalkFailure(response.code, detail))
      }
      val content = JSONObject(payload)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")
      val width = JSONObject(content).getString("width")
      return if (width == "narrow" || width == "medium" || width == "broad") width else null
    }
  }

  private fun requestDestinationCue(
    key: String,
    jpeg: ByteArray,
    destination: String,
  ): DestinationReply? {
    val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
    val place = destination.ifBlank { "the selected destination" }
    val schema = JSONObject()
      .put("type", "object")
      .put("additionalProperties", false)
      .put(
        "properties",
        JSONObject()
          .put("description", JSONObject().put("type", "string"))
          .put(
            "width",
            JSONObject()
              .put("type", "string")
              .put("enum", JSONArray().put("narrow").put("medium").put("broad")),
          ),
      )
      .put("required", JSONArray().put("description").put("width"))
    val body = JSONObject()
      .put("model", "gpt-4o")
      .put(
        "messages",
        JSONArray()
          .put(
            JSONObject()
              .put("role", "system")
              .put("content", DESTINATION_PROMPT),
          )
          .put(
            JSONObject()
              .put("role", "user")
              .put(
                "content",
                JSONArray()
                  .put(
                    JSONObject()
                      .put("type", "text")
                      .put(
                        "text",
                        "Destination: $place. " +
                          "The person is blind and about 15 meters away. " +
                          "From this photo, confirm whether this place is here and tell them " +
                          "what they need to know to reach and get in the entrance. " +
                          "Also judge the width of the sidewalk they are walking on.",
                      ),
                  )
                  .put(
                    JSONObject()
                      .put("type", "image_url")
                      .put("image_url", JSONObject().put("url", dataUrl)),
                  ),
              ),
          ),
      )
      .put(
        "response_format",
        JSONObject()
          .put("type", "json_schema")
          .put(
            "json_schema",
            JSONObject()
              .put("name", "destination_check")
              .put("strict", true)
              .put("schema", schema),
          ),
      )
      .put("max_tokens", 200)
    val request = Request.Builder()
      .url("https://api.openai.com/v1/chat/completions")
      .addHeader("Authorization", "Bearer $key")
      .post(body.toString().toRequestBody(JSON_MEDIA))
      .build()
    http.newCall(request).execute().use { response ->
      val payload = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val detail = runCatching {
          JSONObject(payload).getJSONObject("error").getString("message")
        }.getOrDefault("status ${response.code}")
        Log.e(TAG, "Destination cue status ${response.code}: $detail")
        lastDestinationFailure = destinationFailure(sidewalkFailure(response.code, detail))
        return null
      }
      val content = JSONObject(payload)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")
      val reply = JSONObject(content)
      val width = reply.optString("width").takeIf { it == "narrow" || it == "medium" || it == "broad" }
      val words = reply.optString("description")
        .replace(Regex("[\"\\n]"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(40)
      val line = words.joinToString(" ").ifBlank { null }
      if (line == null && width == null) return null
      return DestinationReply(line, width)
    }
  }

  companion object {
    private const val TAG = "SidewalkWidth"
    private val FILE_TIME = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val MAX_EDGE = 640
    private const val SYSTEM_PROMPT =
      "You judge the walking sidewalk in a first-person phone photo taken just before a corner. " +
        "Choose one width. narrow: about one person wide, little room to swing the turn. " +
        "medium: a typical sidewalk with room for two people. " +
        "broad: a wide sidewalk, plaza, or open corner with lots of room to turn. " +
        "If the sidewalk is hard to see, choose medium."
    private const val SOS_PROMPT =
      "You help a blind pedestrian who just pressed an SOS button because they are unsure " +
        "where they are or what is around them. The photo is what their phone sees, facing " +
        "the way they are pointing it. You are also given their navigation status from the app. " +
        "First, warn about anything in the photo that matters for their safety right now, " +
        "such as traffic, a street or crossing, a curb or drop, stairs, or something in the way. " +
        "Then describe where they are in plain words: the kind of place and the main things " +
        "around them, so they can orient themselves. If they are off the path, use what you see " +
        "to help them get back, together with the navigation status. " +
        "Only mention what you can see. Do not invent signs, streets, or obstacles. " +
        "If the photo is too dark or blurry to help, say so and tell them to hold the phone up " +
        "and point it ahead. Speak at most three short sentences, in plain words, and nothing else."
    private const val DESTINATION_PROMPT =
      "You help a blind person who is about 15 meters from a destination. " +
        "A navigation app guides them along the street to the front of the place, " +
        "but it cannot see the last few meters. Your job is that last stretch. " +
        "The photo is what their phone sees, facing the way they are walking. " +
        "First, say whether the destination appears to be here, based only on what is visible. " +
        "Then tell them what they will physically meet between them and the way in, in the order " +
        "they will meet it: anything they must climb, step up or down, go around, or pass through, " +
        "where the usable entrance is if it is not the obvious door, and anything in the way. " +
        "When it helps, say where it is as it appears in the photo, such as on the left side of the " +
        "building or straight ahead, and roughly how far. " +
        "Do not give street routes, clock positions, or turn-by-turn directions. " +
        "If the destination is not in view, say so and describe what is actually there. " +
        "Only mention what you can see. Do not invent signs, doors, or obstacles. " +
        "Put what to say in description: at most three short sentences, in plain words. " +
        "Put the width of the sidewalk they are walking on in width. " +
        "narrow: about one person wide. medium: a typical sidewalk with room for two people. " +
        "broad: a wide sidewalk or plaza. If the sidewalk is hard to see, choose medium."

    private fun jpegBytes(image: Image, rotationDegrees: Int): ByteArray? {
      if (image.format != ImageFormat.YUV_420_888) return null
      val bitmap = yuvToBitmap(image, rotationDegrees)
      return try {
        ByteArrayOutputStream().use { stream ->
          if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)) return null
          stream.toByteArray()
        }
      } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
      }
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
