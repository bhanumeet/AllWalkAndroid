/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial

import android.opengl.Matrix
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.snackbar.Snackbar
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.*
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException
import kotlin.math.*
import com.google.maps.android.PolyUtil
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions


class HelloGeoRenderer(val activity: HelloGeoActivity) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  var viewPoints = mutableListOf<Step>()
//  var blueWaypoint = mutableListOf<LatLng>()
  var isClick = false
  private val FIX_DISTANCE: Double = 3.0
  private var snackbar: Snackbar? = null

  private val headingValues = ArrayDeque<Double>()
  private var avgHeading = 0.0
  private var vpsWasGood = false
  private var vpsPointIndex = 0
  private var lastVpsLat = Double.NaN
  private var lastVpsLng = Double.NaN
  private val oneSecond = 29
  private var curFrame = 0

  private var minDis: Double = 0.0


  val gson = Gson()
  //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
  companion object {
    val TAG = "Xuan"

    private val Z_NEAR = 0.1f
    private val Z_FAR = 1000f
    /** Same bar the Geospatial samples use for a high-quality localization. */
    private const val VPS_HORIZONTAL_M = 10.0
    private const val VPS_YAW_DEG = 15.0
    private const val VPS_POINT_SPACING_M = 2.0
  }

  /*This is an instance of BackgroundRenderer,
   which is likely a class responsible for rendering the background in the AR scene.*/
  lateinit var backgroundRenderer: BackgroundRenderer

  // Framebuffers are used in OpenGL to contain the rendered image. This is likely a buffer
  // for the virtual scene that will be drawn over the real-world image captured by the camera.
  lateinit var virtualSceneFramebuffer: Framebuffer

  //This is a boolean flag used to check if the texture names have been set for the AR session.
  var hasSetTextureNames = false

  fun prepareForNewSession() {
    hasSetTextureNames = false
    vpsWasGood = false
    lastVpsLat = Double.NaN
    lastVpsLng = Double.NaN
    headingValues.clear()
    avgHeading = 0.0
  }

  // Virtual object (ARCore pawn)
  //The Mesh is the geometric shape of the object
  lateinit var virtualObjectMesh: Mesh

  //Shader controls how it's drawn (like its color, the way it reflects light, etc.)
  lateinit var virtualObjectShader: Shader

  //Texture is the bitmap image that is overlaid onto the object to give it detail.
  lateinit var virtualObjectTexture: Texture

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  val session
    get() = activity.arCoreSessionHelper.session

  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      // Virtual object to render (Geospatial Marker)
      virtualObjectTexture =
        Texture.createFromAsset(
          render,
          "models/spatial_marker_baked.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectMesh = Mesh.createFromAsset(render, "models/geospatial_marker.obj");
      virtualObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_object.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", virtualObjectTexture)

      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, false)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }

  //</editor-fold>
  /*
    General Description: This function is responsible for drawing each frame on the AR view, handling
                         camera, tracking state, background rendering, geospatial positioning, and other
                         relevant tasks. It also integrates with an OCR function when needed and provides
                         feedback to the user based on current state and readings.
    Input:
      - render: A SampleRender object that provides methods to draw 2D and 3D graphics.
    Output:
      - Draws the frame to the view, but does not have a return value.
  */
  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        showError("Camera not available. Try restarting the app.")
        return
      }

    val camera = frame.camera


    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame)

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // -- Draw background
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // Navigation + voice must keep running even if the camera briefly loses tracking
    // (common when the phone is tilted to glance at the map). Only 3D drawing waits.
    val cameraTracking = camera.trackingState == TrackingState.TRACKING
    if (cameraTracking) {
      camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
      camera.getViewMatrix(viewMatrix, 0)
      render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
    }
    //</editor-fold>

    updateNavigationAndVoice(session, frame, cameraTracking)

    // Draw the placed anchor, if it exists (requires camera tracking for matrices).
    if (cameraTracking) {
      earthAnchor?.let {
        render.renderCompassAtAnchor(it)
      }
      // Compose the virtual scene with the background.
      backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
    }
  }

  /**
   * Always-on guidance: map follow + ArrowAlignedGuide TTS.
   * Independent of phone tilt / camera TrackingState.PAUSED.
   */
  private fun updateNavigationAndVoice(
    session: Session,
    frame: Frame,
    cameraTracking: Boolean,
  ) {
    val earth = session.earth
    if (earth?.trackingState != TrackingState.TRACKING) return

    val cameraGeospatialPose = earth.cameraGeospatialPose
    logVpsQualityPose(cameraGeospatialPose, earth.earthState.toString())

    headingValues.addLast(cameraGeospatialPose.heading)
    avgHeading += cameraGeospatialPose.heading

    if (headingValues.size > oneSecond) {
      avgHeading -= headingValues.removeFirst()
    }

    val cosSum = headingValues.sumOf { cos(Math.toRadians(it)) }
    val sinSum = headingValues.sumOf { sin(Math.toRadians(it)) }

    var avgHeading = Math.toDegrees(atan2(sinSum / headingValues.size, cosSum / headingValues.size))

    if (avgHeading > 180) {
      avgHeading -= 360.0
    }

    activity.view.mapView?.updateMapPosition(
      latitude = cameraGeospatialPose.latitude,
      longitude = cameraGeospatialPose.longitude,
      heading = avgHeading,
      isClick
    )

    if (cameraTracking && activity.view.ocrIsOn) {
      useOCR(frame)
    }

    if (activity.navGuide.doorAssist) {
      activity.doorAssist.ensureStarted()
      offerDoorFrame(session, frame)
      activity.doorAssist.projectBox(frame)
    } else {
      activity.doorAssist.stopIfIdle()
    }

    if (isClick && curFrame++ == oneSecond) {
      val curLatlng = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
      val path = activity.view.mapView?.viewPointList.orEmpty()

      if (path.size >= 2) {
        val status = activity.navGuide.update(
          user = curLatlng,
          headingDeg = avgHeading,
          path = path,
        )

        activity.runOnUiThread {
          if (status != null) {
            activity.view.distance_text.text = when {
              status.doorAssist && activity.doorAssist.cue.isNotEmpty() -> activity.doorAssist.cue
              status.doorAssist -> "Arrived"
              else -> status.statusText
            }
            if (status.statusText == "Arrived" && !status.doorAssist) {
              Toast.makeText(activity, "Arrived", Toast.LENGTH_LONG).show()
              // Keep TTS playing "Arrived." — do not speech.stop() on reset.
              activity.reset(keepSpeaking = true)
            } else if (!status.onRoute && !status.doorAssist) {
              showSnackbar(
                activity.findViewById(android.R.id.content),
                "Off path · ${"%.1f".format(status.crossTrackMeters)}m (buf ${activity.navGuide.corridorMeters.toInt()}m)",
              )
            }
          }
        }
      }
      curFrame = 0
    }
  }

  /**
   * ARCore does not return the Street View features it matched.
   * Log the device pose whenever accuracy is in the VPS-quality range,
   * once per lock and again each time that pose moves.
   */
  private fun logVpsQualityPose(pose: GeospatialPose, earthState: String) {
    val good = pose.horizontalAccuracy <= VPS_HORIZONTAL_M &&
      pose.headingAccuracy <= VPS_YAW_DEG
    if (!good) {
      if (vpsWasGood) {
        activity.navLogger.logEvent(
          "VPS_LOST",
          "horizontal_m=${"%.2f".format(pose.horizontalAccuracy)} " +
            "yaw_deg=${"%.1f".format(pose.headingAccuracy)} earthState=$earthState",
        )
      }
      vpsWasGood = false
      return
    }

    val moved = lastVpsLat.isNaN() ||
      distanceMeters(lastVpsLat, lastVpsLng, pose.latitude, pose.longitude) >= VPS_POINT_SPACING_M
    if (vpsWasGood && !moved) return

    val newLock = !vpsWasGood
    vpsWasGood = true
    vpsPointIndex++
    lastVpsLat = pose.latitude
    lastVpsLng = pose.longitude
    activity.navLogger.logVpsPoint(
      index = vpsPointIndex,
      lat = pose.latitude,
      lng = pose.longitude,
      altitude = pose.altitude,
      headingDeg = pose.heading,
      horizontalAccuracyM = pose.horizontalAccuracy,
      verticalAccuracyM = pose.verticalAccuracy,
      yawAccuracyDeg = pose.headingAccuracy,
      earthState = earthState,
      newLock = newLock,
    )
  }

  private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val la1 = Math.toRadians(lat1)
    val lo1 = Math.toRadians(lng1)
    val la2 = Math.toRadians(lat2)
    val lo2 = Math.toRadians(lng2)
    val d = 6371000.0 * acos(
      (cos(la1) * cos(la2) * cos(lo2 - lo1) + sin(la1) * sin(la2)).coerceIn(-1.0, 1.0)
    )
    return d
  }

  /*
    General Description: Displays a snackbar message on the provided view.
    Input:
      - view: The view to display the snackbar on.
      - message: The message string to be displayed in the snackbar.
    Output:
      - Displays the snackbar to the user.
  */
  private fun showSnackbar(view: View, message: String) {
    snackbar = Snackbar.make(view, message, Snackbar.LENGTH_LONG)
    snackbar?.show()
  }

  /*
    General Description: Uses OCR on the provided frame to recognize text, then checks if the recognized
                         text matches a given string and provides feedback.
    Input:
      - frame: The AR frame to be processed for OCR.
    Output:
      - If a match is found, displays a snackbar to the user. Otherwise, does nothing.
  */
  private var doorScanTick = 0

  /** Door model runs on ARCore frames. CameraX cannot share the camera with the session. */
  private fun offerDoorFrame(session: Session, frame: Frame) {
    if (!activity.doorAssist.wantsFrame()) return
    if (doorScanTick++ % 4 != 0) return
    val image = try {
      frame.acquireCameraImage()
    } catch (_: NotYetAvailableException) {
      return
    } catch (_: ResourceExhaustedException) {
      return
    }
    val rotation = try {
      displayRotationHelper.getCameraSensorToDisplayRotation(session.cameraConfig.cameraId)
    } catch (_: Throwable) {
      90
    }
    activity.doorAssist.offer(image, rotation)
  }

  private fun useOCR(frame: Frame){
    try {
      val curImage = frame.acquireCameraImage()
      val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
      val result = recognizer.process(curImage,0)
        .addOnSuccessListener { visionText ->
          var resultText = visionText.text
//          Log.i(TAG, "useOCR: resultText $resultText")
          resultText = resultText.lowercase()
          val addString = activity.view.editText.text.toString()
          if(addString != null && addString != ""){
            val charArray= addString.split(" ")
//            Log.i(TAG, "useOCR: resultText ${charArray.toString()}")
            for(i in charArray){
              if(resultText.contains(i.lowercase())){
//                Log.i(TAG, "useOCR:++++++ $i Find")
                showSnackbar(activity.findViewById(android.R.id.content), "Find!!")
//                activity.view.find_text.text = String.format("find")
              }
            }
          }
          curImage.close()
        }
        .addOnFailureListener { e ->
          Log.e(TAG, "onDrawFrame: $e", )
        }
    }catch (e: NotYetAvailableException){
      Log.e(TAG, "onDrawFrame: $e", )
    }catch (e:ResourceExhaustedException){
      Log.e(TAG, "onDrawFrame: $e", )
    }
  }

  //当前点到两个 waypoint 的距离
  private fun distanceFromPointToLine(startToCur: Double, endToCur: Double, startToEnd: Double): Double {

    if(startToCur<FIX_DISTANCE*1.2 && endToCur<FIX_DISTANCE*1.2){
      return startToCur
    }

    return if(startToCur+endToCur <= startToEnd){// 如果起点到当前点的距离加上终点到当前点的距离小于等于 起点到终点返回 0
      0.0
    }else{
      var p = (startToEnd + startToCur + endToCur) / 2
      var q = max(p * (p - startToEnd) * (p - startToCur) * (p - endToCur),0.0)
      var s = sqrt(q)
      return 2 * s / startToEnd
    }
  }

  // 得到距离
  private fun isInRoute(i1: List<LatLng>, curPosition: LatLng): Boolean {
    // Iterate over each pair of points in the list
    var minD = 90.00
    for (i in 0 until i1.size - 1) {
        val start = i1[i]
        val end = i1[i + 1]
        var distance = 100.0

        var startToCur = getDistanceBetween(start,curPosition)
        var endToCur = getDistanceBetween(end,curPosition)
        var startToEnd = getDistanceBetween(start,end)

        val angleA = findAngle(startToEnd, endToCur, startToCur) // start 的角度
        val angleB = findAngle(startToEnd, startToCur, endToCur) // end 的角度

        if(angleA< PI/2 && angleB<PI/2){
          distance = distanceFromPointToLine(startToCur, endToCur, startToEnd)
          minD = min(minD,distance)
          if (minD <= FIX_DISTANCE && !minD.isNaN()) {
            minDis = (minD * 100.0).roundToInt() / 100.0
            return true
          }
        }
    }
    minDis = (minD*100.0).roundToInt() / 100.0
    return false
  }

  fun findAngle(a: Double, b: Double, c: Double): Double {
    val cosValue = ((a * a + b * b - c * c) / (2 * a * b)).coerceIn(-1.0, 1.0)
    return acos(cosValue) // in radians
  }

  private fun leftOrRight(point: LatLng, startPoint: LatLng, endPoint: LatLng): Boolean {
    val x1 = startPoint.latitude
    val y1 = startPoint.longitude
    val x2 = endPoint.latitude
    val y2 = endPoint.longitude
    val x3 = point.latitude
    val y3 = point.longitude

    return (x1-x3)*(y2-y3)-(y1-y3)*(x2-x3)>0
  }

  var earthAnchor: Anchor? = null
  var destinationLatLng: LatLng? = null
  

  //latLng represents the geographical coordinates (latitude and longitude)
  fun onMapClick(latLng: LatLng) {
    viewPoints.clear()
    activity.view.mapView?.eraseLines()
    activity.navGuide.reset()
    isClick = false
    destinationLatLng = latLng
    activity.navLogger.logEvent(
      "DESTINATION_SET",
      "Destination selected lat=${"%.8f".format(java.util.Locale.US, latLng.latitude)}, " +
        "lng=${"%.8f".format(java.util.Locale.US, latLng.longitude)}",
    )

    // To check the earth is available or not
    val earth = session?.earth ?: return
    if (earth.trackingState != TrackingState.TRACKING) {
      return
    }

    //if the tracking state is tracking then...
    if (earth?.trackingState == TrackingState.TRACKING) {
      val cameraGeospatialPose = earth.cameraGeospatialPose
      val curLatitude = cameraGeospatialPose.latitude
      val curLongitude = cameraGeospatialPose.longitude

      val url:String = getDirectionsUrl(LatLng(curLatitude,curLongitude),latLng)!!
      fetchJson(url) { stepList ->

        viewPoints = stepList.toMutableList()
        activity.view.mapView?.erasePoints()

        for(item in viewPoints){
          val poly = decodePoly(item.polyline.points)
          activity.view.mapView?.addViewPoints(poly)
          activity.view.mapView?.addViewPointMarker(LatLng(item.end_location.lat,item.end_location.lng))
//          blueWaypoint.add(LatLng(item.end_location.lat,item.end_location.lng))
        }
        activity.view.mapView?.drawPolyline()
        isClick = true

        val path = activity.view.mapView?.viewPointList.orEmpty()
        if (path.size >= 2) {
          // Arrival target = last Directions waypoint (polyline end), not search pin.
          val pathEnd = path.last()
          earthAnchor?.detach()
          val altitude = earth.cameraGeospatialPose.altitude - 1
          earthAnchor = earth.createAnchor(
            pathEnd.latitude, pathEnd.longitude, altitude, 0f, 0f, 0f, 1f,
          )
          activity.view.mapView?.earthMarker?.apply {
            position = pathEnd
            isVisible = true
          }
          activity.navLogger.logEvent(
            "ROUTE_END",
            "Arrival waypoint lat=${"%.8f".format(java.util.Locale.US, pathEnd.latitude)} " +
              "lng=${"%.8f".format(java.util.Locale.US, pathEnd.longitude)} " +
              "(search pin may differ if place is inside a building)",
          )
          val waypoints = viewPoints.map {
            LatLng(it.end_location.lat, it.end_location.lng)
          }
          activity.navGuide.onRouteReady(
            path = path,
            user = LatLng(curLatitude, curLongitude),
            headingDeg = cameraGeospatialPose.heading,
            waypoints = waypoints,
            place = destinationLatLng,
          )
        }
      }
    }

    // Temporary marker at search/geocode pin until the route polyline end is known.
    earthAnchor?.detach()
    // Place the earth anchor at the same altitude as that of the camera to make it easier to view.
    val altitude = earth.cameraGeospatialPose.altitude - 1
    // The rotation quaternion of the anchor in the East-Up-South (EUS) coordinate system.
    val qx = 0f
    val qy = 0f
    val qz = 0f
    val qw = 1f
    earthAnchor =
      earth.createAnchor(latLng.latitude, latLng.longitude, altitude, qx, qy, qz, qw)
    activity.view.mapView?.earthMarker?.apply {
      position = latLng
      isVisible = true
    }
  }

  private fun fetchJson(url: String, callback: (List<Step>) -> Unit) {
    val request = Request.Builder()
      .url(url)
      .build()

    val client = OkHttpClient()
    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.i(TAG, "fetchJson: false")
        e.printStackTrace()
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          Log.i(TAG, "fetchJson: Success")
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          val responseData = response.body?.string()
          val geoResponse = gson.fromJson(responseData, GeoResponse::class.java)
          Log.i(TAG, "fetchJson: ${geoResponse}")
          if(geoResponse.routes.isNotEmpty()){
            val stepList = geoResponse.routes[0].legs[0].steps
            activity.runOnUiThread {
              callback(stepList)
            }
          }
        }
      }
    })
  }

  private fun decodePoly(encoded: String): List<LatLng> {
    val poly = ArrayList<LatLng>()
    var index = 0
    val len = encoded.length
    var lat = 0
    var lng = 0
    while (index < len) {
      var b: Int
      var shift = 0
      var result = 0
      do {
        b = encoded[index++].toInt() - 63
        result = result or (b and 0x1f shl shift)
        shift += 5
      } while (b >= 0x20)
      val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
      lat += dlat
      shift = 0
      result = 0
      do {
        b = encoded[index++].toInt() - 63
        result = result or (b and 0x1f shl shift)
        shift += 5
      } while (b >= 0x20)
      val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
      lng += dlng
      val p = LatLng(lat.toDouble() / 1E5,
        lng.toDouble() / 1E5)
      poly.add(p)
    }
    return poly
  }

  private fun getDirectionsUrl(origin: LatLng, dest:LatLng):String?{
    val stringOrigin = "origin="+origin.latitude+","+origin.longitude

    val stringDest = "destination="+dest.latitude+","+dest.longitude

    val mode = "mode=walking"

    val parameters = "$stringOrigin&$stringDest&$mode"

    val output = "json"

//   API key
    val apiKey = activity.getString(R.string.GoogleCloudApiKey)
    return "https://maps.googleapis.com/maps/api/directions/$output?$parameters&waypoints&key=$apiKey"
  }

  fun calculateHeading(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lonDiff = Math.toRadians(lon2 - lon1)
    val lat1Rads = Math.toRadians(lat1)
    val lat2Rads = Math.toRadians(lat2)

    val y = sin(lonDiff) * cos(lat2Rads)
    val x = cos(lat1Rads) * sin(lat2Rads) - sin(lat1Rads) * cos(lat2Rads) * cos(lonDiff)

    var heading = Math.toDegrees(atan2(y, x))

    // Convert to a compass bearing, i.e., in the range [0,360)
    heading = (heading + 360) % 360
    if (heading > 180) {
      heading -= 360
    }
    return heading
  }

  // 得到两个经纬度点的距离
  private fun getDistanceBetween(curPosition: LatLng, waypoint: LatLng): Double{

    val la1= rad(curPosition.latitude)
    val lo1= rad(curPosition.longitude)
    val la2= rad(waypoint.latitude)
    val lo2= rad(waypoint.longitude)
    val d = 6367 * acos(cos(la1) * cos(la2) * cos(lo2-lo1) + sin(la1) * sin(la2))
    return d*1000
  }

  private fun rad(d:Double): Double{
    return d*Math.PI/180.0
  }

  private fun SampleRender.renderCompassAtAnchor(anchor: Anchor) {
    // Get the current pose of the Anchor in world space. The Anchor pose is updated
    // during calls to session.update() as ARCore refines its estimate of the world.
    anchor.pose.toMatrix(modelMatrix, 0)

    // Calculate model/view/projection matrices
    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

    // Update shader properties and draw
    virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
    draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}
