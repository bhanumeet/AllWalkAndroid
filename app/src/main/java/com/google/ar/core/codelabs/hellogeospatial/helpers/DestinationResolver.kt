package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.codelabs.hellogeospatial.GeoCodingResponse
import com.google.ar.core.codelabs.hellogeospatial.R
import com.google.ar.core.codelabs.hellogeospatial.Root
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

data class DestinationResult(
  val latLng: LatLng,
  val displayName: String,
  val provider: DestinationProvider,
)

/**
 * Resolves typed destination names via Geoapify or Google Places Text Search.
 * Never falls back between providers — caller chooses exactly one.
 */
class DestinationResolver(
  private val context: Context,
  private val eventLog: ((type: String, message: String) -> Unit)? = null,
) {
  companion object {
    private const val TAG = "DestinationResolver"
    private const val SEARCH_RADIUS_M = 5000
  }

  private val client = OkHttpClient()
  private val gson = Gson()
  private val googleApiKey: String by lazy {
    context.getString(R.string.GoogleCloudApiKey)
  }
  private val geoapifyApiKey: String by lazy {
    context.getString(R.string.GeoapifyApiKey)
  }

  fun resolve(
    provider: DestinationProvider,
    placeName: String,
    near: LatLng,
    onSuccess: (DestinationResult) -> Unit,
    onError: (String) -> Unit,
  ) {
    log(
      "PROVIDER_SELECTED",
      "provider=${provider.storageValue} label=${provider.label} " +
        "query=$placeName near=${near.latitude},${near.longitude} " +
        "fallback=NONE",
    )
    when (provider) {
      DestinationProvider.GEOAPIFY -> resolveWithGeoapify(placeName, near, onSuccess, onError)
      DestinationProvider.GOOGLE_PLACES -> resolveWithGooglePlaces(placeName, near, onSuccess, onError)
    }
  }

  private fun resolveWithGeoapify(
    placeName: String,
    near: LatLng,
    onSuccess: (DestinationResult) -> Unit,
    onError: (String) -> Unit,
  ) {
    val reverseUrl = Uri.parse("https://api.geoapify.com/v1/geocode/reverse").buildUpon()
      .appendQueryParameter("lat", near.latitude.toString())
      .appendQueryParameter("lon", near.longitude.toString())
      .appendQueryParameter("format", "json")
      .appendQueryParameter("apiKey", geoapifyApiKey)
      .build()
      .toString()

    log("API_REQUEST", "provider=geoapify step=reverse url=${redact(reverseUrl)}")
    get(reverseUrl, { err -> fail(DestinationProvider.GEOAPIFY, err, onError) }) { reverseBody ->
      log("API_RESPONSE", "provider=geoapify step=reverse bytes=${reverseBody.length}")
      val reverse = gson.fromJson(reverseBody, GeoCodingResponse::class.java)
      val postcode = reverse.results.firstOrNull()?.postcode
      if (postcode.isNullOrBlank()) {
        fail(DestinationProvider.GEOAPIFY, "reverse geocode returned no postcode", onError)
        return@get
      }
      log("API_RESPONSE", "provider=geoapify step=reverse postcode=$postcode")

      val searchUrl = Uri.parse("https://api.geoapify.com/v1/geocode/search").buildUpon()
        .appendQueryParameter("name", placeName)
        .appendQueryParameter("postcode", postcode)
        .appendQueryParameter(
          "filter",
          "circle:${near.longitude},${near.latitude},$SEARCH_RADIUS_M",
        )
        .appendQueryParameter("format", "json")
        .appendQueryParameter("apiKey", geoapifyApiKey)
        .build()
        .toString()

      log("API_REQUEST", "provider=geoapify step=search url=${redact(searchUrl)}")
      get(searchUrl, { err -> fail(DestinationProvider.GEOAPIFY, err, onError) }) { searchBody ->
        val ans = gson.fromJson(searchBody, Root::class.java)
        val first = ans.results.firstOrNull()
        log(
          "API_RESPONSE",
          "provider=geoapify step=search resultCount=${ans.results.size}",
        )
        if (first == null) {
          fail(
            DestinationProvider.GEOAPIFY,
            "No such place in ${SEARCH_RADIUS_M / 1000}km",
            onError,
          )
          return@get
        }
        val addressNum = first.address_line2.split(" ").firstOrNull().orEmpty()
        val display = listOf(first.address_line1, addressNum)
          .filter { it.isNotBlank() }
          .joinToString(" ")
        succeed(
          DestinationResult(
            latLng = LatLng(first.lat, first.lon),
            displayName = display.ifBlank { placeName },
            provider = DestinationProvider.GEOAPIFY,
          ),
          onSuccess,
        )
      }
    }
  }

  private fun resolveWithGooglePlaces(
    placeName: String,
    near: LatLng,
    onSuccess: (DestinationResult) -> Unit,
    onError: (String) -> Unit,
  ) {
    val url = Uri.parse("https://maps.googleapis.com/maps/api/place/textsearch/json").buildUpon()
      .appendQueryParameter("query", placeName)
      .appendQueryParameter("location", "${near.latitude},${near.longitude}")
      .appendQueryParameter("radius", SEARCH_RADIUS_M.toString())
      .appendQueryParameter("key", googleApiKey)
      .build()
      .toString()

    log("API_REQUEST", "provider=google_places step=textsearch url=${redact(url)}")
    get(url, { err -> fail(DestinationProvider.GOOGLE_PLACES, err, onError) }) { body ->
      val root = JsonParser.parseString(body).asJsonObject
      val status = root.get("status")?.asString.orEmpty()
      val errMsg = root.get("error_message")?.asString
      val results = root.getAsJsonArray("results")
      val count = results?.size() ?: 0
      log(
        "API_RESPONSE",
        "provider=google_places status=$status resultCount=$count " +
          "error_message=${errMsg ?: "none"} bodyPreview=${body.take(240).replace("\n", " ")}",
      )
      if (status != "OK" && status != "ZERO_RESULTS") {
        fail(
          DestinationProvider.GOOGLE_PLACES,
          "Places status=$status ${errMsg ?: ""}".trim(),
          onError,
        )
        return@get
      }
      if (count == 0) {
        fail(
          DestinationProvider.GOOGLE_PLACES,
          "No such place in ${SEARCH_RADIUS_M / 1000}km (ZERO_RESULTS)",
          onError,
        )
        return@get
      }
      val first = results!![0].asJsonObject
      val loc = first.getAsJsonObject("geometry").getAsJsonObject("location")
      val lat = loc.get("lat").asDouble
      val lng = loc.get("lng").asDouble
      val name = first.get("name")?.asString.orEmpty()
      val address = first.get("formatted_address")?.asString.orEmpty()
      val placeId = first.get("place_id")?.asString.orEmpty()
      val display = listOf(name, address).filter { it.isNotBlank() }.joinToString(" — ")
      log(
        "API_RESPONSE",
        "provider=google_places picked name=$name place_id=$placeId lat=$lat lng=$lng",
      )
      succeed(
        DestinationResult(
          latLng = LatLng(lat, lng),
          displayName = display.ifBlank { placeName },
          provider = DestinationProvider.GOOGLE_PLACES,
        ),
        onSuccess,
      )
    }
  }

  private fun succeed(result: DestinationResult, onSuccess: (DestinationResult) -> Unit) {
    log(
      "DEST_SUCCESS",
      "USED_PROVIDER=${result.provider.storageValue} " +
        "label=${result.provider.label} " +
        "name=${result.displayName} " +
        "lat=${result.latLng.latitude} lng=${result.latLng.longitude} " +
        "fallback=NONE",
    )
    onSuccess(result)
  }

  private fun fail(provider: DestinationProvider, message: String, onError: (String) -> Unit) {
    val clean =
      "FAILED provider=${provider.storageValue} (${provider.label}): $message | NO_FALLBACK_TO_OTHER_PROVIDER"
    log("DEST_FAIL", clean)
    onError(clean)
  }

  private fun log(type: String, message: String) {
    Log.i(TAG, "$type | $message")
    eventLog?.invoke(type, message)
  }

  private fun redact(url: String): String {
    return url
      .replace(Regex("([?&](?:key|apiKey)=)[^&]+"), "$1REDACTED")
  }

  private fun get(url: String, onError: (String) -> Unit, onBody: (String) -> Unit) {
    val request = Request.Builder().url(url).get().build()
    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        onError("network: ${e.message ?: "request failed"}")
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          val body = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            onError("HTTP ${response.code} body=${body.take(200)}")
            return
          }
          if (body.isBlank()) {
            onError("Empty response body")
            return
          }
          try {
            onBody(body)
          } catch (e: Exception) {
            Log.e(TAG, "Parse failed", e)
            onError("parse: ${e.message ?: "failed"}")
          }
        }
      }
    })
  }
}
