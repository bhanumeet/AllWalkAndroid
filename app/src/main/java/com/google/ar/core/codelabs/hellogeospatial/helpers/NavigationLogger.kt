package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Navigation drift log:
 * - Full planned path coordinates once at route start
 * - User position every [userSampleIntervalMs] (default 1s)
 * - TTS / buffer / lifecycle events for context
 */
class NavigationLogger(context: Context) {
  companion object {
    private const val TAG = "NavigationLogger"
    private const val DIR_NAME = "nav_logs"
    const val USER_SAMPLE_INTERVAL_MS = 1_000L
  }

  private val appContext = context.applicationContext
  private val lock = Any()
  private val lines = CopyOnWriteArrayList<String>()
  private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
  private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

  @Volatile private var sessionFile: File? = null
  @Volatile private var tableFile: File? = null
  private val pathPoints = mutableListOf<LatLng>()
  private val walkPoints = mutableListOf<LatLng>()
  private val waypointPoints = mutableListOf<LatLng>()
  private val vpsPoints = mutableListOf<LatLng>()
  /** Spoken cue with where the user was when it played. */
  private data class Cue(val time: String, val lat: Double?, val lng: Double?, val text: String)
  private val cues = mutableListOf<Cue>()
  @Volatile var lastLat: Double? = null
    private set
  @Volatile var lastLng: Double? = null
    private set
  @Volatile var lastHeading: Double? = null
    private set

  private var lastUserSampleMs = 0L
  /** Destination search text. Saved logs are named after it. */
  @Volatile var logTitle: String = ""
  private var userSampleIndex = 0

  init {
    ensureSessionFile()
    log("SESSION", "Logger started; user samples every ${USER_SAMPLE_INTERVAL_MS / 1000}s")
  }

  fun updatePose(lat: Double, lng: Double, headingDeg: Double) {
    lastLat = lat
    lastLng = lng
    lastHeading = headingDeg
  }

  /**
   * Logs start/destination plus every polyline coordinate of the planned path.
   * Offline drift = distance from each USER sample to nearest PATH segment.
   */
  fun logRouteStart(
    start: LatLng,
    destination: LatLng,
    path: List<LatLng>,
    bufferMeters: Double,
    waypoints: List<LatLng> = emptyList(),
  ) {
    lastUserSampleMs = 0L
    userSampleIndex = 0
    synchronized(lock) {
      pathPoints.clear()
      waypointPoints.clear()
    }

    log(
      "ROUTE_START",
      "buffer=${bufferMeters}m pathPoints=${path.size}",
      lat = start.latitude,
      lng = start.longitude,
      extra = "start=${fmt(start.latitude)},${fmt(start.longitude)} " +
        "dest=${fmt(destination.latitude)},${fmt(destination.longitude)}",
    )
    log(
      "DESTINATION",
      "destination coordinates",
      lat = destination.latitude,
      lng = destination.longitude,
    )
    logPath(path)
    logWaypoints(waypoints.ifEmpty { listOf(destination) })

    // Immediate first user sample at route start.
    logUserSample(
      lat = start.latitude,
      lng = start.longitude,
      headingDeg = lastHeading ?: 0.0,
      crossTrackMeters = 0.0,
      onRoute = true,
      force = true,
    )
  }

  fun logPath(path: List<LatLng>) {
    log("PATH_BEGIN", "count=${path.size}")
    path.forEachIndexed { index, point ->
      log(
        "PATH",
        "i=$index",
        lat = point.latitude,
        lng = point.longitude,
      )
    }
    log("PATH_END", "count=${path.size}")
    replaceSeries(pathPoints, path)
  }

  /** Directions step ends: each turn, then the destination. */
  fun logWaypoints(waypoints: List<LatLng>) {
    waypoints.forEachIndexed { index, point ->
      log(
        "WAYPOINT",
        "i=$index",
        lat = point.latitude,
        lng = point.longitude,
      )
    }
    replaceSeries(waypointPoints, waypoints)
  }

  /**
   * Records user pose at most once a second (unless [force]).
   * Use these rows with PATH rows to compute drift offline.
   */
  fun logUserSample(
    lat: Double,
    lng: Double,
    headingDeg: Double,
    crossTrackMeters: Double,
    onRoute: Boolean,
    nowMs: Long = System.currentTimeMillis(),
    force: Boolean = false,
  ) {
    updatePose(lat, lng, headingDeg)
    if (!force && nowMs - lastUserSampleMs < USER_SAMPLE_INTERVAL_MS) return

    lastUserSampleMs = nowMs
    userSampleIndex++
    log(
      "USER",
      "sample=$userSampleIndex",
      lat = lat,
      lng = lng,
      heading = headingDeg,
      extra = "crossTrack_m=${"%.3f".format(Locale.US, crossTrackMeters)} " +
        "onRoute=$onRoute " +
        "interval_s=${USER_SAMPLE_INTERVAL_MS / 1000}",
    )
    appendSeries(walkPoints, lat, lng)
  }

  fun logTts(text: String, flush: Boolean = false) {
    synchronized(lock) {
      cues.add(Cue(timeFormat.format(Date()), lastLat, lastLng, text))
      writeTableLocked()
    }
    log(
      "TTS",
      text,
      lat = lastLat,
      lng = lastLng,
      heading = lastHeading,
      extra = if (flush) "flush=true" else null,
    )
  }

  fun logBuffer(meters: Double) {
    log("BUFFER", "corridor set to ${meters.toInt()} m", lat = lastLat, lng = lastLng)
  }

  fun logEvent(type: String, message: String) {
    log(type, message, lat = lastLat, lng = lastLng, heading = lastHeading)
  }

  /**
   * One row per VPS-quality localization.
   * Coordinates are the device pose ARCore is using, not internal Street View features.
   */
  fun logVpsPoint(
    index: Int,
    lat: Double,
    lng: Double,
    altitude: Double,
    headingDeg: Double,
    horizontalAccuracyM: Double,
    verticalAccuracyM: Double,
    yawAccuracyDeg: Double,
    earthState: String,
    newLock: Boolean,
  ) {
    updatePose(lat, lng, headingDeg)
    log(
      "VPS",
      "i=$index ${if (newLock) "lock" else "update"}",
      lat = lat,
      lng = lng,
      heading = headingDeg,
      extra = "alt_m=${"%.2f".format(Locale.US, altitude)} " +
        "horizontalAccuracy_m=${"%.2f".format(Locale.US, horizontalAccuracyM)} " +
        "verticalAccuracy_m=${"%.2f".format(Locale.US, verticalAccuracyM)} " +
        "yawAccuracy_deg=${"%.1f".format(Locale.US, yawAccuracyDeg)} " +
        "earthState=$earthState " +
        "note=device_pose_at_vps_quality_not_streetview_feature",
    )
    appendSeries(vpsPoints, lat, lng)
  }

  fun clearAndStartNewSession() {
    synchronized(lock) {
      lines.clear()
      sessionFile = null
      tableFile = null
      pathPoints.clear()
      walkPoints.clear()
      waypointPoints.clear()
      vpsPoints.clear()
      cues.clear()
      lastUserSampleMs = 0L
      userSampleIndex = 0
    }
    ensureSessionFile()
    log("SESSION", "New log session; user samples every ${USER_SAMPLE_INTERVAL_MS / 1000}s")
  }

  /**
   * Copies this session's .csv and .txt to Downloads/AllWalk without a dialog.
   * Returns false when there is nothing to save or the copy fails.
   */
  fun saveToDownloads(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val (csv, txt) = synchronized(lock) {
      if (walkPoints.isEmpty() && pathPoints.isEmpty()) return false
      writeTableLocked()
      Pair(tableFile ?: return false, sessionFile ?: return false)
    }
    return try {
      val base = exportBaseName()
      copyToDownloads(csv, "text/csv", "$base.csv")
      copyToDownloads(txt, "text/plain", "$base.txt")
      Log.i(TAG, "Saved ${csv.name} and ${txt.name} to Downloads/AllWalk")
      true
    } catch (e: Exception) {
      Log.e(TAG, "saveToDownloads failed", e)
      false
    }
  }

  /**
   * File name for a saved log: the destination search text plus the session start time,
   * e.g. "gong_cha_20261029_141503". Falls back to "nav_log_..." without a destination.
   */
  fun exportBaseName(): String {
    val stamp = sessionFile?.nameWithoutExtension?.removePrefix("nav_log_")
      ?: fileNameFormat.format(Date())
    val slug = logTitle.lowercase(Locale.US)
      .replace(Regex("[^a-z0-9]+"), "_")
      .trim('_')
      .take(40)
      .trim('_')
    return "${slug.ifBlank { "nav_log" }}_$stamp"
  }

  private fun copyToDownloads(file: File, mime: String, name: String) {
    val resolver = appContext.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, name)
      put(MediaStore.Downloads.MIME_TYPE, mime)
      put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AllWalk")
      put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: throw java.io.IOException("Could not create ${file.name}")
    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
      ?: throw java.io.IOException("Could not open ${file.name}")
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
  }

  fun currentLogFile(): File {
    ensureSessionFile()
    return sessionFile!!
  }

  /** Map table: one latitude/longitude column pair per series, plus spoken cues. */
  fun currentTableFile(): File {
    ensureSessionFile()
    synchronized(lock) { writeTableLocked() }
    return tableFile!!
  }

  fun lineCount(): Int = lines.size

  fun buildExportText(): String = synchronized(lock) {
    lines.joinToString("\n")
  }

  private fun log(
    type: String,
    message: String,
    lat: Double? = null,
    lng: Double? = null,
    heading: Double? = null,
    extra: String? = null,
  ) {
    val ts = timeFormat.format(Date())
    val parts = mutableListOf(ts, type, message)
    if (lat != null && lng != null) {
      parts.add("lat=${fmt(lat)}")
      parts.add("lng=${fmt(lng)}")
    }
    if (heading != null) {
      parts.add("heading=${"%.1f".format(Locale.US, heading)}")
    }
    if (!extra.isNullOrBlank()) {
      parts.add(extra)
    }
    val line = parts.joinToString(" | ")
    synchronized(lock) {
      lines.add(line)
      appendLineLocked(line)
    }
    Log.i(TAG, line)
  }

  private fun fmt(v: Double): String = "%.8f".format(Locale.US, v)

  private fun ensureSessionFile() {
    synchronized(lock) {
      if (sessionFile != null) return
      val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, DIR_NAME)
      if (!dir.exists()) dir.mkdirs()
      val file = File(dir, "nav_log_${fileNameFormat.format(Date())}.txt")
      file.writeText(
        "BlindNav drift log\n" +
          "Started: ${timeFormat.format(Date())}\n" +
          "USER samples every ${USER_SAMPLE_INTERVAL_MS / 1000} seconds\n" +
          "PATH lines = planned route polyline (use for ground-truth path)\n" +
          "USER lines = observed user pose (use to measure drift from PATH)\n" +
          "VPS lines = camera pose when ARCore accuracy is VPS-quality " +
          "(Street View match points are not exposed by ARCore)\n" +
          "Format: timestamp | TYPE | message | lat=... | lng=... | heading=... | extras\n" +
          "Map table is the .csv next to this file: path, walk, waypoint, and vps\n" +
          "columns (latitude, longitude), plus cue_time, cue_lat, cue_lng, cue_text.\n" +
          "------------------------------------------------------------\n"
      )
      sessionFile = file
      tableFile = File(dir, file.nameWithoutExtension + ".csv")
      writeTableLocked()
    }
  }

  private fun replaceSeries(list: MutableList<LatLng>, points: List<LatLng>) {
    synchronized(lock) {
      list.clear()
      list.addAll(points)
      writeTableLocked()
    }
  }

  private fun appendSeries(list: MutableList<LatLng>, lat: Double, lng: Double) {
    synchronized(lock) {
      list.add(LatLng(lat, lng))
      writeTableLocked()
    }
  }

  /**
   * Wide table: row N holds the Nth point of every series. A series that is shorter
   * leaves its columns empty, so each column pair can be plotted on its own.
   */
  private fun writeTableLocked() {
    val file = tableFile ?: return
    val series = listOf(pathPoints, walkPoints, waypointPoints, vpsPoints)
    val rows = maxOf(series.maxOf { it.size }, cues.size)
    val body = buildString {
      append(
        "path_lat,path_lng,walk_lat,walk_lng,waypoint_lat,waypoint_lng,vps_lat,vps_lng," +
          "cue_time,cue_lat,cue_lng,cue_text\n",
      )
      for (i in 0 until rows) {
        for (points in series) {
          val point = points.getOrNull(i)
          append(point?.let { fmt(it.latitude) }.orEmpty()).append(',')
          append(point?.let { fmt(it.longitude) }.orEmpty()).append(',')
        }
        val cue = cues.getOrNull(i)
        append(cue?.time.orEmpty()).append(',')
        append(cue?.lat?.let { fmt(it) }.orEmpty()).append(',')
        append(cue?.lng?.let { fmt(it) }.orEmpty()).append(',')
        append(cue?.let { csvQuote(it.text) }.orEmpty())
        append('\n')
      }
    }
    try {
      file.writeText(body)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write map table", e)
    }
  }

  private fun csvQuote(text: String): String =
    "\"" + text.replace("\"", "\"\"").replace('\n', ' ') + "\""

  private fun appendLineLocked(line: String) {
    try {
      ensureSessionFile()
      sessionFile?.appendText(line + "\n")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to append log line", e)
    }
  }
}
