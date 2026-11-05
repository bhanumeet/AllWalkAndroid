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
  /** Place name, spoken first. */
  val name: String = displayName,
  /** Street or area, so nearby branches can be told apart. */
  val detail: String = "",
  /** Full address, spoken once when navigation starts. */
  val fullAddress: String = "",
) {
  /** Name plus the full address, without repeating the name if it is already in the address. */
  val confirmLine: String
    get() {
      val address = fullAddress.trim()
      if (address.isBlank()) return name
      if (address.contains(name, ignoreCase = true)) return address
      return "$name, $address"
    }
}

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
    const val MAX_RESULTS = 4
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
    search(
      provider = provider,
      placeName = placeName,
      near = near,
      onSuccess = { results ->
        val first = results.firstOrNull()
        if (first == null) {
          fail(provider, "No such place in ${SEARCH_RADIUS_M / 1000}km", onError)
        } else {
          onSuccess(first)
        }
      },
      onError = onError,
    )
  }

  /** Up to [MAX_RESULTS] nearby matches, nearest first. */
  fun search(
    provider: DestinationProvider,
    placeName: String,
    near: LatLng,
    onSuccess: (List<DestinationResult>) -> Unit,
    onError: (String) -> Unit,
  ) {
    log(
      "PROVIDER_SELECTED",
      "provider=${provider.storageValue} label=${provider.label} " +
        "query=$placeName near=${near.latitude},${near.longitude} " +
        "fallback=NONE",
    )
    when (provider) {
      DestinationProvider.GEOAPIFY -> searchWithGeoapify(placeName, near, onSuccess, onError)
      DestinationProvider.GOOGLE_PLACES -> searchWithGooglePlaces(placeName, near, onSuccess, onError)
    }
  }

  private fun searchWithGeoapify(
    placeName: String,
    near: LatLng,
    onSuccess: (List<DestinationResult>) -> Unit,
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
        log(
          "API_RESPONSE",
          "provider=geoapify step=search resultCount=${ans.results.size}",
        )
        val picks = ans.results.mapNotNull { toGeoapifyResult(it, placeName) }
          .sortedBy { metersBetween(near, it.latLng) }
          .take(MAX_RESULTS)
        if (picks.isEmpty()) {
          fail(
            DestinationProvider.GEOAPIFY,
            "No such place in ${SEARCH_RADIUS_M / 1000}km",
            onError,
          )
          return@get
        }
        deliver(picks, onSuccess)
      }
    }
  }

  private fun toGeoapifyResult(result: com.google.ar.core.codelabs.hellogeospatial.GeocodingResult, placeName: String): DestinationResult? {
    if (result.lat == 0.0 && result.lon == 0.0) return null
    val place = result.name.clean().ifBlank { result.address_line1.clean() }.ifBlank { placeName }
    val street = listOf(result.housenumber.clean(), result.street.clean())
      .filter { it.isNotBlank() }
      .joinToString(" ")
    val lineStreet = result.address_line1.clean().substringBefore(",").trim()
    val city = result.city.clean()
    val detail = when {
      street.isNotBlank() && !street.equals(place, ignoreCase = true) -> street
      lineStreet.isNotBlank() &&
        !lineStreet.equals(place, ignoreCase = true) &&
        !lineStreet.equals(city, ignoreCase = true) -> lineStreet
      else -> ""
    }
    val display = listOf(place, detail).filter { it.isNotBlank() }.joinToString(" — ")
    val formatted = result.formatted.clean()
    val full = if (formatted.isNotBlank()) {
      formatted
    } else {
      listOf(detail, city, result.state.clean(), result.postcode.clean())
        .filter { it.isNotBlank() }
        .joinToString(", ")
    }
    return DestinationResult(
      latLng = LatLng(result.lat, result.lon),
      displayName = display.ifBlank { placeName },
      provider = DestinationProvider.GEOAPIFY,
      name = place,
      detail = detail,
      fullAddress = full,
    )
  }

  private fun searchWithGooglePlaces(
    placeName: String,
    near: LatLng,
    onSuccess: (List<DestinationResult>) -> Unit,
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
      val picks = (0 until count).mapNotNull { index ->
        val item = results!![index].asJsonObject
        val loc = item.getAsJsonObject("geometry")?.getAsJsonObject("location") ?: return@mapNotNull null
        val lat = loc.get("lat")?.asDouble ?: return@mapNotNull null
        val lng = loc.get("lng")?.asDouble ?: return@mapNotNull null
        val name = item.get("name")?.asString.orEmpty().ifBlank { placeName }
        val formatted = item.get("formatted_address")?.asString.orEmpty().trim()
        val address = shortAddress(formatted)
        val detail = if (address.equals(name, ignoreCase = true)) "" else address
        val display = listOf(name, detail).filter { it.isNotBlank() }.joinToString(" — ")
        DestinationResult(
          latLng = LatLng(lat, lng),
          displayName = display.ifBlank { placeName },
          provider = DestinationProvider.GOOGLE_PLACES,
          name = name,
          detail = detail,
          fullAddress = formatted,
        )
      }.sortedBy { metersBetween(near, it.latLng) }.take(MAX_RESULTS)
      log(
        "API_RESPONSE",
        "provider=google_places picked=${picks.joinToString(" | ") { it.displayName }}",
      )
      if (picks.isEmpty()) {
        fail(
          DestinationProvider.GOOGLE_PLACES,
          "No such place in ${SEARCH_RADIUS_M / 1000}km",
          onError,
        )
        return@get
      }
      deliver(picks, onSuccess)
    }
  }

  private fun deliver(results: List<DestinationResult>, onSuccess: (List<DestinationResult>) -> Unit) {
    val provider = results.first().provider
    log(
      "DEST_SUCCESS",
      "USED_PROVIDER=${provider.storageValue} count=${results.size} " +
        results.joinToString(" | ") {
          "${it.displayName} @ ${it.latLng.latitude},${it.latLng.longitude}"
        } + " fallback=NONE",
    )
    onSuccess(results)
  }

  /** Street line only. City and state stay off the spoken choice. */
  private fun shortAddress(formatted: String): String {
    return formatted.split(",")
      .map { it.trim() }
      .firstOrNull { it.isNotEmpty() }
      .orEmpty()
  }

  private fun String?.clean(): String = this?.trim().orEmpty()

  private fun metersBetween(a: LatLng, b: LatLng): Double {
    val earthRadius = 6371000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLng = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return 2 * earthRadius * Math.asin(Math.sqrt(h))
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
