package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.ar.core.codelabs.hellogeospatial.BuildConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Door finding with the OpenAI Decisions API: one yes/no question per frame.
 * https://developers.openai.com/api/docs/guides/decisions
 */
class VlmDoorClient {
  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

  @Volatile var lastFailure: String? = null
    private set

  /**
   * Probability (0 to 1) that a door is in [bitmap], or null when the request failed.
   * Blocking; call off the main thread.
   */
  fun doorProbability(bitmap: Bitmap): Double? {
    val key = BuildConfig.OPENAI_API_KEY
    if (key.isBlank()) {
      lastFailure = "No OpenAI key"
      return null
    }
    val jpeg = ByteArrayOutputStream().use { stream ->
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)) return null
      stream.toByteArray()
    }
    val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
    val body = JSONObject()
      .put("model", MODEL)
      .put(
        "input",
        JSONArray().put(
          JSONObject()
            .put("role", "user")
            .put(
              "content",
              JSONArray()
                .put(
                  JSONObject()
                    .put("type", "input_text")
                    .put("text", "Photo from a blind pedestrian's phone, facing forward, at their destination."),
                )
                .put(
                  JSONObject()
                    .put("type", "input_image")
                    .put("image_url", dataUrl),
                ),
            ),
        ),
      )
      .put(
        "questions",
        JSONArray().put(
          JSONObject()
            .put("type", "predicate")
            .put("name", QUESTION)
            .put("instructions", INSTRUCTIONS),
        ),
      )
    val request = Request.Builder()
      .url("https://api.openai.com/v1/decisions")
      .addHeader("Authorization", "Bearer $key")
      .post(body.toString().toRequestBody(JSON_MEDIA))
      .build()
    return try {
      http.newCall(request).execute().use { response ->
        val payload = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          val detail = runCatching {
            JSONObject(payload).getJSONObject("error").getString("message")
          }.getOrDefault("status ${response.code}")
          lastFailure = "Decisions ${response.code}: $detail"
          Log.e(TAG, lastFailure!!)
          return null
        }
        val answers = JSONObject(payload).getJSONArray("answers")
        for (i in 0 until answers.length()) {
          val answer = answers.getJSONObject(i)
          if (answer.optString("name") == QUESTION && answer.optString("type") == "predicate") {
            lastFailure = null
            return answer.getDouble("probability")
          }
        }
        lastFailure = "Decisions returned no answer"
        null
      }
    } catch (error: Throwable) {
      lastFailure = "Decisions request failed: ${error.message}"
      Log.e(TAG, "Decisions request failed", error)
      null
    }
  }

  companion object {
    private const val TAG = "VlmDoor"
    private const val MODEL = "gpt-6-luna"
    private const val QUESTION = "door_visible"
    /** Probability at or above this counts as a door. */
    const val DOOR_THRESHOLD = 0.8
    private const val INSTRUCTIONS =
      "Is there a door the person could walk through right now, close and in front of them? " +
        "Answer yes only if a building entrance door is within about 5 meters, roughly ahead, " +
        "and takes up a clear part of the photo, with its frame or handle visible. " +
        "Answer no if the door is far away, across the street, at the very edge of the photo, " +
        "mostly hidden, or if you only see windows, walls, storefront glass, gates, garage doors, " +
        "or vehicle doors. If unsure, answer no."
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
  }
}
