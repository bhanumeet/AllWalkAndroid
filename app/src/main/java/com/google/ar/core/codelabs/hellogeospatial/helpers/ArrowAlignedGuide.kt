package com.google.ar.core.codelabs.hellogeospatial.helpers

import com.google.android.gms.maps.model.LatLng
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Local turn guidance from the user's ARCore location + heading.
 *
 * Google Directions supplies path coordinates only.
 * Cues are never from Google turn text.
 *
 * - Distance = along-path meters to the next bend
 * - Left/Right = relative to where the user is facing now
 *   (needed path bearing after the bend − user heading)
 */
class ArrowAlignedGuide(
  private val speech: SpeechGuide,
  private val logger: NavigationLogger? = null,
  private val haptics: HapticGuide? = null,
  initialCorridorMeters: Double = DEFAULT_BUFFER_METERS,
  private val lookAheadMeters: Double = 12.0,
  private val deviationIntervalMs: Long = 3_000L,
) {
  companion object {
    const val DEFAULT_BUFFER_METERS = 5.0
    const val MIN_BUFFER_METERS = 1
    const val MAX_BUFFER_METERS = 20
    private const val TURN_NOW_M = 2.0
    /** Single early cue before the turn. */
    private const val TURN_AHEAD_M = 10.0
    /**
     * Arrival / near-destination use the **last path waypoint** (Directions polyline end),
     * not the Places/Geoapify building centroid — that can sit inside a store.
     */
    private const val DEST_NEAR_M = 10.0
    /** Must be this close (crow-fly) to the path-end waypoint to count as arrived. */
    private const val DEST_ARRIVED_M = 2.0
    /** Start door finding this close to the path end or the place pin. */
    private const val DOOR_ASSIST_M = 3.0
    private const val TURN_MIN_DEG = 45.0
    /** Sample bearings this far before/after a vertex so dense polylines still show real corners. */
    private const val TURN_SAMPLE_M = 8.0
    private const val MAX_TURN_LOOKAHEAD_M = 60.0
    /** If user already faces within this of the needed bearing, don't say left/right. */
    private const val ALREADY_FACING_DEG = 30.0
    /** Start orientation: facing path well enough to walk straight (within ~1 hour / 30°). */
    private const val START_STRAIGHT_DEG = 30.0
    /** Re-prompt path alignment while waiting for the user to face the route. */
    private const val ALIGN_CUE_INTERVAL_MS = 4_000L
    /** Legs longer than this get on-path affirmations at 1/3 and 2/3. */
    private const val AFFIRM_MIN_LEG_M = 50.0
  }

  /** Latched inside 3 m of the destination. Stays on until navigation reset. */
  @Volatile var doorAssist: Boolean = false

  /** Live on-route threshold (meters from nearest path point). Slider writes here. */
  @Volatile
  var corridorMeters: Double =
    initialCorridorMeters.coerceIn(MIN_BUFFER_METERS.toDouble(), MAX_BUFFER_METERS.toDouble())
    set(value) {
      field = value.coerceIn(MIN_BUFFER_METERS.toDouble(), MAX_BUFFER_METERS.toDouble())
    }

  private var segmentIndex = 0
  private var activeTurnVertex = -1
  private var announcedBands = mutableSetOf<String>()
  private var announcedDestNear = false
  private var arrivedAnnounced = false
  private var lastDeviationSpeakMs = 0L
  private var lastSpeechMs = 0L
  private var lastAlignCueMs = 0L
  private var routeStarted = false
  private var wasOnRoute = true
  /** True until the user faces within ±START_STRAIGHT_DEG of the path. */
  private var awaitingPathAlign = false
  private var startAlignedAnnounced = false
  /** Directions step ends; last one is the destination. */
  private var routeWaypoints: List<LatLng> = emptyList()
  /** Geocoded place pin (often off the sidewalk). Used for left/right, not for the arrival radius. */
  private var placePin: LatLng? = null
  private val announcedAffirm = mutableSetOf<String>()

  data class Status(
    val statusText: String,
    val crossTrackMeters: Double,
    val distToNextMeters: Double,
    val distToDestMeters: Double,
    val destBearingDeg: Double,
    val relativeToPathDeg: Double,
    val onRoute: Boolean,
    /** True once the user is inside the door-finding radius. Stays on until reset. */
    val doorAssist: Boolean = false,
  )

  private data class UpcomingTurn(
    val distanceMeters: Double,
    /** Absolute compass bearing the user should face for this maneuver. */
    val neededBearingDeg: Double,
    val vertexIndex: Int,
  )

  /** Call when the buffer slider changes during an active route. */
  fun onCorridorChanged() {
    if (!routeStarted) return
    lastDeviationSpeakMs = 0L
    wasOnRoute = true // next off-route sample speaks immediately as "just left"
    haptics?.stopDrift()
    logger?.logEvent("BUFFER_LIVE", "corridor now ${corridorMeters.toInt()}m — drift re-armed")
  }

  fun reset(stopSpeech: Boolean = true) {
    segmentIndex = 0
    activeTurnVertex = -1
    announcedBands.clear()
    announcedDestNear = false
    arrivedAnnounced = false
    lastDeviationSpeakMs = 0L
    lastSpeechMs = 0L
    lastAlignCueMs = 0L
    routeStarted = false
    wasOnRoute = true
    awaitingPathAlign = false
    startAlignedAnnounced = false
    routeWaypoints = emptyList()
    placePin = null
    announcedAffirm.clear()
    doorAssist = false
    haptics?.stopDrift()
    if (stopSpeech) {
      speech.stop()
    }
  }

  fun onRouteReady(
    path: List<LatLng>,
    user: LatLng,
    headingDeg: Double,
    waypoints: List<LatLng> = emptyList(),
    place: LatLng? = null,
  ) {
    reset()
    if (path.size < 2) return
    routeWaypoints = waypoints.ifEmpty { listOf(path.last()) }
    placePin = place
    segmentIndex = nearestSegmentIndex(path, user)
    routeStarted = true
    awaitingPathAlign = true
    startAlignedAnnounced = false
    logger?.updatePose(user.latitude, user.longitude, headingDeg)
    val dest = path.last()
    logger?.logRouteStart(user, dest, path, corridorMeters, routeWaypoints)
    speakShort("Route set.", flush = true)
    // Align to path first. Destination angle is spoken only after alignment.
    updateStartAlignment(path, user, headingDeg, nowMs = System.currentTimeMillis(), forceCue = true)
  }

  /**
   * Phase 1: face the path.
   * Phase 2 (once aligned): "You're on path… Destination is at …" once.
   */
  private fun updateStartAlignment(
    path: List<LatLng>,
    user: LatLng,
    headingDeg: Double,
    nowMs: Long,
    forceCue: Boolean = false,
  ) {
    if (!awaitingPathAlign || startAlignedAnnounced) return

    val lookAhead = lookAheadPoint(path, user, segmentIndex, lookAheadMeters)
    val pathBearing = absoluteBearingDeg(user, lookAhead)
    val relToPath = relativeBearingDeg(headingDeg, pathBearing)
    val absRel = kotlin.math.abs(relToPath)

    if (absRel <= START_STRAIGHT_DEG) {
      awaitingPathAlign = false
      startAlignedAnnounced = true
      val pathEnd = path.last()
      val destRel = relativeBearingDeg(headingDeg, absoluteBearingDeg(user, pathEnd))
      val destCue = destinationClockPhrase(destRel)
      val cue = "You're on path. $destCue Walk straight."
      logger?.logEvent(
        "ORIENT_ALIGNED",
        "heading=${"%.1f".format(headingDeg)} pathBearing=${"%.1f".format(pathBearing)} " +
          "destRel=${"%.1f".format(destRel)} cue=$cue",
      )
      speakShort(cue, flush = true)
      return
    }

    // Still aligning — re-prompt periodically (not the destination clock yet).
    if (!forceCue && nowMs - lastAlignCueMs < ALIGN_CUE_INTERVAL_MS) return
    lastAlignCueMs = nowMs

    val clock = clockHourFromRelativeDeg(relToPath)
    val cue = if (clock == 12) {
      "You're facing the path."
    } else {
      "Turn to your $clock o'clock to face the path."
    }
    logger?.logEvent(
      "ORIENT_ALIGN",
      "heading=${"%.1f".format(headingDeg)} pathBearing=${"%.1f".format(pathBearing)} " +
        "relDeg=${"%.1f".format(relToPath)} clock=$clock cue=$cue",
    )
    speakShort(cue, flush = forceCue)
  }

  /** Place pin when it sits off the path end; otherwise the path end itself. */
  private fun sideTargetOrPathEnd(pathEnd: LatLng): LatLng {
    val pin = placePin ?: return pathEnd
    return if (distanceMeters(pin, pathEnd) >= 4.0) pin else pathEnd
  }

  private fun destinationClockPhrase(destRelDeg: Double): String {
    val clock = clockHourFromRelativeDeg(destRelDeg)
    return when (clock) {
      12 -> "Destination is at your 12 o'clock."
      6 -> "Destination is at your 6 o'clock."
      else -> "Destination is to your $clock o'clock."
    }
  }

  /**
   * Immediate re-orientation for disoriented users (volume up/down).
   * Speaks: on/off path, distance off line, clock turn to face path, how to return.
   */
  fun speakSos(user: LatLng, headingDeg: Double, path: List<LatLng>) {
    haptics?.pulseSos()
    if (path.size < 2) {
      val msg = "No route set."
      logger?.logEvent("SOS", msg)
      speakShort(msg, flush = true, priority = SpeakPriority.CRITICAL)
      return
    }

    val nearest = nearestPointOnPath(path, user)
    segmentIndex = nearest.segmentIndex
    val crossTrack = nearest.distanceMeters
    val bufferM = corridorMeters
    val onRoute = crossTrack <= bufferM
    val sideLeft = isLeftOfSegment(user, nearest.segStart, nearest.segEnd)
    val lookAhead = lookAheadPoint(path, user, nearest.segmentIndex, lookAheadMeters)
    val pathBearing = absoluteBearingDeg(user, lookAhead)
    val relToPath = relativeBearingDeg(headingDeg, pathBearing)
    val faceClock = clockHourFromRelativeDeg(relToPath)
    val pathEnd = path.last()
    val destM = distanceMeters(user, pathEnd).roundToInt()
    val destRel = relativeBearingDeg(headingDeg, absoluteBearingDeg(user, pathEnd))

    val parts = mutableListOf<String>()

    if (onRoute) {
      parts.add("You are on the path.")
      if (faceClock != 12) {
        parts.add("Turn to your $faceClock o'clock to face along the path.")
      }
      parts.add(destinationClockPhrase(destRel))
      parts.add("About $destM meters to destination.")
      parts.add("Walk straight.")
    } else {
      val metersOff = crossTrack.roundToInt().coerceAtLeast(1)
      val side = if (sideLeft) "left" else "right"
      val correction = if (sideLeft) "right" else "left"
      parts.add("You are off the path.")
      parts.add("About $metersOff meters $side of the line.")
      if (faceClock == 12) {
        parts.add("You are facing along the path.")
      } else {
        parts.add("Turn to your $faceClock o'clock to face the path.")
      }
      parts.add("Bear $correction to return.")
      parts.add("About $destM meters to destination.")
    }

    val msg = parts.joinToString(" ")
    logger?.logEvent(
      "SOS",
      "onRoute=$onRoute cross_m=${"%.1f".format(crossTrack)} buf_m=${"%.1f".format(bufferM)} " +
        "faceClock=$faceClock dest_m=$destM " +
        "lat=${"%.5f".format(user.latitude)} lng=${"%.5f".format(user.longitude)}",
    )
    speakShort(msg, flush = true, priority = SpeakPriority.CRITICAL)
  }

  /**
   * Map relative bearing to a clock hour.
   * 12 = straight ahead, 3 = right, 6 = behind, 9 = left.
   * [relDeg] > 0 means target is to the right (clockwise).
   */
  private fun clockHourFromRelativeDeg(relDeg: Double): Int {
    val hour = ((relDeg / 30.0).roundToInt() % 12 + 12) % 12
    return if (hour == 0) 12 else hour
  }

  fun update(user: LatLng, headingDeg: Double, path: List<LatLng>, nowMs: Long = System.currentTimeMillis()): Status? {
    if (!routeStarted || path.size < 2) return null

    logger?.updatePose(user.latitude, user.longitude, headingDeg)

    val nearest = nearestPointOnPath(path, user)
    segmentIndex = nearest.segmentIndex
    val crossTrack = nearest.distanceMeters
    // Read once — slider can change corridorMeters mid-update from the UI thread.
    val bufferM = corridorMeters
    val onRoute = crossTrack <= bufferM
    val sideLeft = isLeftOfSegment(user, nearest.segStart, nearest.segEnd)

    // End of walking polyline (last waypoint) — not the search/geocode pin.
    val pathEnd = path.last()
    val lookAhead = lookAheadPoint(path, user, segmentIndex, lookAheadMeters)
    val distNext = distanceMeters(user, lookAhead)
    val alongToEnd = alongPathRemainingMeters(path, nearest)
    val crowFlyToEnd = distanceMeters(user, pathEnd)
    // Status distance: prefer crow-fly to path end (honest for the user).
    val distDest = crowFlyToEnd
    val bearingDest = absoluteBearingDeg(user, pathEnd)
    val relPath = relativeBearingDeg(headingDeg, absoluteBearingDeg(user, lookAhead))
    val upcoming = findNextTurn(path, user, segmentIndex)

    // Finish start alignment before normal turn cues (destination angle only after aligned).
    if (awaitingPathAlign) {
      updateStartAlignment(path, user, headingDeg, nowMs)
    }

    // Arrive when within the radius of the path-end waypoint. Buffer does not matter.
    val arrivedNow = crowFlyToEnd <= DEST_ARRIVED_M
    val pinNear = placePin?.let { distanceMeters(user, it) <= DOOR_ASSIST_M } == true
    if (!doorAssist && (crowFlyToEnd <= DOOR_ASSIST_M || pinNear)) {
      doorAssist = true
      logger?.logEvent(
        "DOOR",
        "within ${DOOR_ASSIST_M.toInt()}m crowfly_m=${"%.1f".format(crowFlyToEnd)}",
      )
    }

    // Door finding is the last step. Keep the route alive so the camera scan continues
    // after "Arrived" instead of clearing navigation.
    if (doorAssist) {
      haptics?.stopDrift()
      if (arrivedNow && !arrivedAnnounced) {
        arrivedAnnounced = true
        val sideTarget = sideTargetOrPathEnd(pathEnd)
        val destRel = relativeBearingDeg(headingDeg, absoluteBearingDeg(user, sideTarget))
        val sideCue = when {
          kotlin.math.abs(destRel) <= 20.0 -> "Destination ahead."
          kotlin.math.abs(destRel) >= 150.0 -> "Destination behind you."
          destRel > 0 -> "Destination to your right."
          else -> "Destination to your left."
        }
        speakShort("Arrived. $sideCue", flush = true, priority = SpeakPriority.CRITICAL)
        haptics?.pulseArrived()
        logger?.logEvent(
          "ARRIVED",
          "path_end=true along_m=${"%.1f".format(alongToEnd)} " +
            "crowfly_m=${"%.1f".format(crowFlyToEnd)} " +
            "cross_m=${"%.1f".format(crossTrack)} " +
            "buf_m=${"%.1f".format(bufferM)} " +
            "destRel=${"%.1f".format(destRel)} " +
            "end_lat=${"%.8f".format(pathEnd.latitude)} " +
            "end_lng=${"%.8f".format(pathEnd.longitude)}",
        )
      }
      return Status(
        statusText = "Door",
        crossTrackMeters = crossTrack,
        distToNextMeters = distNext,
        distToDestMeters = distDest,
        destBearingDeg = bearingDest,
        relativeToPathDeg = relPath,
        onRoute = true,
        doorAssist = true,
      )
    }

    val approaching =
      crowFlyToEnd <= DEST_NEAR_M ||
        (alongToEnd <= DEST_NEAR_M && crossTrack <= maxOf(bufferM, DEST_NEAR_M))
    if (approaching && !announcedDestNear) {
      announcedDestNear = true
      val sideTarget = sideTargetOrPathEnd(pathEnd)
      val destRel = relativeBearingDeg(headingDeg, absoluteBearingDeg(user, sideTarget))
      speakShort(
        "Approaching destination. ${destinationClockPhrase(destRel)}",
        flush = true,
      )
      logger?.logEvent(
        "DEST_NEAR",
        "approaching path_end=true along_m=${"%.1f".format(alongToEnd)} " +
          "crowfly_m=${"%.1f".format(crowFlyToEnd)} cross_m=${"%.1f".format(crossTrack)}",
      )
    }

    if (!onRoute) {
      val justLeftPath = wasOnRoute
      wasOnRoute = false
      // Restart vibe when first leaving path, and refresh when we speak (OS can cancel loops).
      haptics?.startDrift(restart = justLeftPath)
      if (justLeftPath || nowMs - lastDeviationSpeakMs >= deviationIntervalMs) {
        val correction = if (sideLeft) "right" else "left"
        val waypointRel = relativeBearingDeg(headingDeg, absoluteBearingDeg(user, lookAhead))
        val waypointClock = clockHourFromRelativeDeg(waypointRel)
        speakShort(
          "Drifting, bear $correction. Next waypoint to your $waypointClock o'clock.",
          priority = SpeakPriority.DRIFT,
        )
        lastDeviationSpeakMs = nowMs
        haptics?.startDrift(restart = true)
        logger?.logEvent(
          "DRIFT",
          "cross_m=${"%.1f".format(crossTrack)} buf_m=${"%.1f".format(bufferM)} " +
            "bear=$correction waypointClock=$waypointClock justLeft=$justLeftPath",
        )
      }
    } else {
      if (!wasOnRoute) {
        logger?.logEvent(
          "ON_ROUTE",
          "cross_m=${"%.1f".format(crossTrack)} buf_m=${"%.1f".format(bufferM)}",
        )
      }
      wasOnRoute = true
      haptics?.stopDrift()
      // Don't mix turn cues with start-alignment prompts.
      if (!awaitingPathAlign) {
        announceTurn(upcoming, headingDeg, nowMs)
        maybeAffirmLongLeg(path, user, nearest, headingDeg)
      }
    }

    val userRelToTurn = upcoming?.let {
      relativeBearingDeg(headingDeg, it.neededBearingDeg)
    }
    val status = buildString {
      if (upcoming != null && userRelToTurn != null) {
        val dir = if (userRelToTurn > 0) "R" else "L"
        append("$dir in ${upcoming.distanceMeters.roundToInt()}m")
        append(" · faceΔ=${userRelToTurn.roundToInt()}°")
      } else {
        append("straight")
      }
      append(" · ${"%.1f".format(crossTrack)}m off line")
      if (!onRoute) append(" · OFF")
    }

    logger?.logUserSample(
      lat = user.latitude,
      lng = user.longitude,
      headingDeg = headingDeg,
      crossTrackMeters = crossTrack,
      onRoute = onRoute,
      nowMs = nowMs,
    )

    return Status(
      statusText = status,
      crossTrackMeters = round2(crossTrack),
      distToNextMeters = round2(upcoming?.distanceMeters ?: distNext),
      distToDestMeters = round2(distDest),
      destBearingDeg = round2(bearingDest),
      relativeToPathDeg = round2(userRelToTurn ?: relPath),
      onRoute = onRoute,
    )
  }

  /**
   * On a leg longer than 50 m, speak once at 1/3 and once at 2/3 of that leg.
   * Target is the next Directions step end, or the destination if it is the last.
   */
  private fun maybeAffirmLongLeg(
    path: List<LatLng>,
    user: LatLng,
    nearest: NearestOnPath,
    headingDeg: Double,
  ) {
    if (routeWaypoints.isEmpty() || path.size < 2) return

    val userAlong = alongFromStart(path, nearest)
    val marks = routeWaypoints.mapIndexed { index, point ->
      val onPath = nearestPointOnPath(path, point)
      Triple(index, alongFromStart(path, onPath), point)
    }.sortedBy { it.second }

    val next = marks.firstOrNull { it.second > userAlong + 3.0 } ?: return
    val prevAlong = marks.filter { it.second <= userAlong + 3.0 }.maxOfOrNull { it.second } ?: 0.0
    val legLen = next.second - prevAlong
    if (legLen <= AFFIRM_MIN_LEG_M) return

    val interval = legLen / 3.0
    val intoLeg = userAlong - prevAlong
    val due = when {
      intoLeg >= 2.0 * interval -> 2
      intoLeg >= interval -> 1
      else -> 0
    }
    if (due == 0) return
    val key = "${next.first}-$due"
    if (!announcedAffirm.add(key)) return
    if (due == 2) announcedAffirm.add("${next.first}-1")

    val clock = clockHourFromRelativeDeg(
      relativeBearingDeg(headingDeg, absoluteBearingDeg(user, next.third)),
    )
    val isDestination = next.first == routeWaypoints.lastIndex
    val cue = if (isDestination) {
      "You are on path. Destination to your $clock o'clock."
    } else {
      "You are on path. Next waypoint to your $clock o'clock."
    }
    logger?.logEvent(
      "AFFIRM",
      "leg_m=${"%.1f".format(legLen)} into_m=${"%.1f".format(intoLeg)} " +
        "checkpoint=$due clock=$clock dest=$isDestination",
    )
    speakShort(cue)
  }

  /** Meters from path start to [nearest]. */
  private fun alongFromStart(path: List<LatLng>, nearest: NearestOnPath): Double {
    var along = 0.0
    for (i in 0 until nearest.segmentIndex) {
      along += distanceMeters(path[i], path[i + 1])
    }
    along += distanceMeters(path[nearest.segmentIndex], nearest.closest)
    return along
  }

  /**
   * Speak turn cues from the user's current heading vs the bearing they need.
   * Distance is to the next path bend from their current location.
   */
  private fun announceTurn(turn: UpcomingTurn?, headingDeg: Double, nowMs: Long) {
    if (turn == null) {
      activeTurnVertex = -1
      announcedBands.clear()
      return
    }

    val rel = relativeBearingDeg(headingDeg, turn.neededBearingDeg)
    // Already facing the right way — stay quiet.
    if (kotlin.math.abs(rel) < ALREADY_FACING_DEG) {
      return
    }

    val direction = if (rel > 0) "right" else "left"

    if (turn.vertexIndex != activeTurnVertex) {
      activeTurnVertex = turn.vertexIndex
      announcedBands.clear()
      logger?.logEvent(
        "TURN_USER",
        "vertex=${turn.vertexIndex} " +
          "userHeading=${"%.1f".format(headingDeg)} " +
          "neededBearing=${"%.1f".format(turn.neededBearingDeg)} " +
          "relDeg=${"%.1f".format(rel)} " +
          "dir=$direction " +
          "dist_m=${"%.1f".format(turn.distanceMeters)} " +
          "source=user_heading_vs_path_bend",
      )
    }

    val dist = turn.distanceMeters

    when {
      dist <= TURN_NOW_M -> {
        if (announcedBands.add("now")) {
          speakShort("Turn $direction now.", flush = true)
          if (direction == "left") {
            haptics?.pulseLeftNow()
          } else {
            haptics?.pulseRightNow()
          }
        }
      }
      dist <= TURN_AHEAD_M -> {
        if (announcedBands.add("ahead")) {
          speakShort("Approaching $direction turn.")
        }
      }
    }
  }

  private fun speakShort(
    text: String,
    flush: Boolean = false,
    priority: SpeakPriority = SpeakPriority.NORMAL,
  ) {
    speech.speak(text, priority = priority, flush = flush)
    lastSpeechMs = System.currentTimeMillis()
  }

  /**
   * Next path bend ahead of the user.
   * Returns distance from current location and the absolute bearing to face after the bend.
   * Left/right is decided later from user heading — not from path-only geometry.
   */
  private fun findNextTurn(path: List<LatLng>, user: LatLng, startSeg: Int): UpcomingTurn? {
    if (path.size < 3) return null
    val i0 = startSeg.coerceIn(0, path.size - 2)
    val from = closestPointOnSegment(user, path[i0], path[i0 + 1])
    var along = distanceMeters(from, path[i0 + 1])

    for (vertex in (i0 + 1) until path.lastIndex) {
      if (along > MAX_TURN_LOOKAHEAD_M) break

      val before = pointAlongPath(path, vertex, -TURN_SAMPLE_M) ?: path[vertex - 1]
      val after = pointAlongPath(path, vertex, TURN_SAMPLE_M) ?: path[vertex + 1]
      val bearingIn = absoluteBearingDeg(before, path[vertex])
      val bearingOut = absoluteBearingDeg(path[vertex], after)
      val pathDelta = normalize180(bearingOut - bearingIn)

      if (kotlin.math.abs(pathDelta) >= TURN_MIN_DEG) {
        return UpcomingTurn(
          distanceMeters = along,
          neededBearingDeg = bearingOut,
          vertexIndex = vertex,
        )
      }
      along += distanceMeters(path[vertex], path[vertex + 1])
    }
    return null
  }

  /**
   * Walk [deltaMeters] along the path from [vertexIndex].
   * Negative = backward toward start, positive = forward toward destination.
   */
  private fun pointAlongPath(path: List<LatLng>, vertexIndex: Int, deltaMeters: Double): LatLng? {
    if (vertexIndex !in path.indices) return null
    if (deltaMeters == 0.0) return path[vertexIndex]

    if (deltaMeters > 0) {
      var remaining = deltaMeters
      var i = vertexIndex
      var from = path[i]
      while (i < path.lastIndex && remaining > 0) {
        val to = path[i + 1]
        val seg = distanceMeters(from, to)
        if (seg >= remaining) {
          val t = (remaining / seg).coerceIn(0.0, 1.0)
          return interpolate(from, to, t)
        }
        remaining -= seg
        from = to
        i++
      }
      return path.last()
    } else {
      var remaining = -deltaMeters
      var i = vertexIndex
      var from = path[i]
      while (i > 0 && remaining > 0) {
        val to = path[i - 1]
        val seg = distanceMeters(from, to)
        if (seg >= remaining) {
          val t = (remaining / seg).coerceIn(0.0, 1.0)
          return interpolate(from, to, t)
        }
        remaining -= seg
        from = to
        i--
      }
      return path.first()
    }
  }

  private data class NearestOnPath(
    val segmentIndex: Int,
    val segStart: LatLng,
    val segEnd: LatLng,
    val closest: LatLng,
    val distanceMeters: Double,
  )

  private fun nearestPointOnPath(path: List<LatLng>, user: LatLng): NearestOnPath {
    var bestIdx = 0
    var bestDist = Double.MAX_VALUE
    var bestClosest = path[0]
    var bestStart = path[0]
    var bestEnd = path[1]

    for (i in 0 until path.size - 1) {
      val a = path[i]
      val b = path[i + 1]
      val closest = closestPointOnSegment(user, a, b)
      val d = distanceMeters(user, closest)
      if (d < bestDist) {
        bestDist = d
        bestIdx = i
        bestClosest = closest
        bestStart = a
        bestEnd = b
      }
    }

    return NearestOnPath(
      segmentIndex = bestIdx,
      segStart = bestStart,
      segEnd = bestEnd,
      closest = bestClosest,
      distanceMeters = bestDist,
    )
  }

  /** Meters left to walk along the polyline from the nearest path point to path.end. */
  private fun alongPathRemainingMeters(path: List<LatLng>, nearest: NearestOnPath): Double {
    var remaining = distanceMeters(nearest.closest, nearest.segEnd)
    for (i in (nearest.segmentIndex + 1) until path.lastIndex) {
      remaining += distanceMeters(path[i], path[i + 1])
    }
    return remaining
  }

  private fun lookAheadPoint(
    path: List<LatLng>,
    user: LatLng,
    startSeg: Int,
    targetMeters: Double,
  ): LatLng {
    var remaining = targetMeters
    var i = startSeg.coerceIn(0, path.size - 2)
    var from = closestPointOnSegment(user, path[i], path[i + 1])

    while (i < path.size - 1) {
      val to = path[i + 1]
      val segLen = distanceMeters(from, to)
      if (segLen >= remaining || i == path.size - 2) {
        if (segLen < 0.01) return to
        val t = (remaining / segLen).coerceIn(0.0, 1.0)
        return interpolate(from, to, t)
      }
      remaining -= segLen
      from = to
      i++
    }
    return path.last()
  }

  private fun nearestSegmentIndex(path: List<LatLng>, user: LatLng): Int {
    return nearestPointOnPath(path, user).segmentIndex
  }

  private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

  fun relativeBearingDeg(headingDeg: Double, targetBearingDeg: Double): Double {
    return normalize180(targetBearingDeg - headingDeg)
  }

  fun absoluteBearingDeg(from: LatLng, to: LatLng): Double {
    val lonDiff = Math.toRadians(to.longitude - from.longitude)
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val y = sin(lonDiff) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lonDiff)
    val bearing = Math.toDegrees(atan2(y, x))
    return (bearing + 360.0) % 360.0
  }

  fun distanceMeters(a: LatLng, b: LatLng): Double {
    val la1 = Math.toRadians(a.latitude)
    val lo1 = Math.toRadians(a.longitude)
    val la2 = Math.toRadians(b.latitude)
    val lo2 = Math.toRadians(b.longitude)
    val d = 6367.0 * acos(
      (cos(la1) * cos(la2) * cos(lo2 - lo1) + sin(la1) * sin(la2)).coerceIn(-1.0, 1.0)
    )
    return d * 1000.0
  }

  private fun normalize180(deg: Double): Double {
    var d = ((deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    if (d == -180.0) d = 180.0
    return d
  }

  private fun interpolate(a: LatLng, b: LatLng, t: Double): LatLng {
    return LatLng(
      a.latitude + (b.latitude - a.latitude) * t,
      a.longitude + (b.longitude - a.longitude) * t,
    )
  }

  private fun closestPointOnSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
    val ax = a.longitude
    val ay = a.latitude
    val bx = b.longitude
    val by = b.latitude
    val px = p.longitude
    val py = p.latitude
    val abx = bx - ax
    val aby = by - ay
    val apx = px - ax
    val apy = py - ay
    val abLen2 = abx * abx + aby * aby
    if (abLen2 < 1e-18) return a
    val t = ((apx * abx + apy * aby) / abLen2).coerceIn(0.0, 1.0)
    return LatLng(ay + aby * t, ax + abx * t)
  }

  private fun isLeftOfSegment(p: LatLng, a: LatLng, b: LatLng): Boolean {
    val cross = (b.longitude - a.longitude) * (p.latitude - a.latitude) -
      (b.latitude - a.latitude) * (p.longitude - a.longitude)
    return cross > 0
  }
}
