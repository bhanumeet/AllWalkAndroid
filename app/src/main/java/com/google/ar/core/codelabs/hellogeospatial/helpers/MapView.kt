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
package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import androidx.annotation.ColorInt
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoActivity
import com.google.ar.core.codelabs.hellogeospatial.R
import kotlin.math.cos
import kotlin.math.pow

class MapView(val activity: HelloGeoActivity, val googleMap: GoogleMap) {
  private val VIEW_POINT_COLOR: Int = Color.argb(255, 0, 20, 255)
  private val CAMERA_MARKER_COLOR: Int = Color.argb(255, 0, 255, 0)
  private val EARTH_MARKER_COLOR: Int = Color.argb(255, 125, 125, 125)
  /** Semi-transparent blue corridor (screen-width polyline ≈ 2 × buffer meters). */
  private val BUFFER_FILL_COLOR: Int = Color.argb(90, 30, 144, 255)

  var setInitialCameraPosition = false
  val cameraMarker = createMarker(CAMERA_MARKER_COLOR,false)
  var cameraIdle = true

  var setFirst = true

  var viewPointMarkerList = mutableListOf<Marker>()

  var viewPointList = mutableListOf<LatLng>()
  var viewPointListSize = 0;

  var curPointer = 0

  val earthMarker = createMarker(EARTH_MARKER_COLOR,false)

  var polyline: Polyline? = null
  /** Wide translucent path = visual corridor of width 2 × [bufferMeters]. */
  private var bufferPolyline: Polyline? = null
  private var bufferMeters: Double = ArrowAlignedGuide.DEFAULT_BUFFER_METERS
  private var lastBufferDrawZoom: Float = -1f

  init {
    googleMap.uiSettings.apply {
      isMapToolbarEnabled = false
      isIndoorLevelPickerEnabled = false
      isZoomControlsEnabled = false
      isTiltGesturesEnabled = false
      isScrollGesturesEnabled = true
    }

    googleMap.setOnMarkerClickListener { unused -> false }

    // Add listeners to keep track of when the GoogleMap camera is moving.
    googleMap.setOnCameraMoveListener { cameraIdle = false }
    googleMap.setOnCameraIdleListener {
      cameraIdle = true
      // Only rebuild corridor when zoom changes (width is zoom-dependent).
      val zoom = googleMap.cameraPosition.zoom
      if (viewPointList.size >= 2 && kotlin.math.abs(zoom - lastBufferDrawZoom) > 0.05f) {
        drawBuffer(bufferMeters)
      }
    }
  }

  fun erasePoints(){
    if(!viewPointMarkerList.isEmpty()){
      for(item in viewPointMarkerList){
        item.remove()
      }
    }
  }

  fun addViewPoints(latLng: List<LatLng>){
    val uniqueSet = LinkedHashSet(viewPointList)
    uniqueSet.addAll(latLng)
    viewPointList = ArrayList(uniqueSet)
    viewPointListSize = viewPointList.size
  }

  fun getLastPoint(): LatLng {
    return viewPointList[viewPointListSize - 1]
  }

  fun getCurLine(): List<LatLng>? {
    if (curPointer == viewPointListSize - 1) {
      return null
    }
    return listOf(viewPointList[curPointer], viewPointList[curPointer + 1])
  }

  fun getNextLine(): List<LatLng>? {
    if (curPointer == viewPointListSize - 2) {
      return null
    }
    return listOf(viewPointList[curPointer+1], viewPointList[curPointer + 2])
  }

  fun curPointerAdd(){
    curPointer++
  }

  fun isArrivedLast(): Boolean {
    if (curPointer == viewPointListSize - 2) {
      return true
    }
    return false
  }

  fun eraseLines(){
    viewPointList.clear()
    curPointer = 0
    clearBuffer()
  }

  fun addViewPointMarker(latLng:LatLng){
    val viewPointMarker = createMarker(VIEW_POINT_COLOR,true)
    viewPointMarker.isVisible = true
    viewPointMarker.position = latLng
    viewPointMarkerList.add(viewPointMarker)
  }

  fun drawPolyline() {
    val polylineOptions = PolylineOptions().apply {
      addAll(viewPointList) //add all points to polyline
      width(5f) // width of the line
      color(Color.RED) // color of the line
      geodesic(true)
    }

    polyline?.remove() // remove old polyline if exists

    polyline = googleMap.addPolyline(polylineOptions.apply { zIndex(1f) })
    drawBuffer(bufferMeters)
  }

  fun setBufferMeters(meters: Double, redraw: Boolean = true) {
    bufferMeters = meters.coerceIn(
      ArrowAlignedGuide.MIN_BUFFER_METERS.toDouble(),
      ArrowAlignedGuide.MAX_BUFFER_METERS.toDouble(),
    )
    if (redraw && viewPointList.size >= 2) {
      drawBuffer(bufferMeters)
    }
  }

  fun currentBufferMeters(): Double = bufferMeters

  fun drawBuffer(meters: Double = bufferMeters) {
    bufferMeters = meters.coerceIn(
      ArrowAlignedGuide.MIN_BUFFER_METERS.toDouble(),
      ArrowAlignedGuide.MAX_BUFFER_METERS.toDouble(),
    )
    clearBuffer()
    if (viewPointList.size < 2) return

    val lat = viewPointList.first().latitude
    val zoom = googleMap.cameraPosition.zoom.toDouble()
    // Web-Mercator meters/pixel at equator, corrected for latitude.
    val metersPerPx =
      156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
    // Full corridor width = left buffer + right buffer.
    val widthPx = ((2.0 * bufferMeters) / metersPerPx)
      .toFloat()
      .coerceIn(6f, 600f)

    bufferPolyline = googleMap.addPolyline(
      PolylineOptions()
        .addAll(viewPointList)
        .width(widthPx)
        .color(BUFFER_FILL_COLOR)
        .geodesic(true)
        .zIndex(0f)
        .clickable(false)
    )
    lastBufferDrawZoom = googleMap.cameraPosition.zoom
  }

  fun clearBuffer() {
    bufferPolyline?.remove()
    bufferPolyline = null
  }

  fun updateMapPosition(latitude: Double, longitude: Double, heading: Double, isClick: Boolean){
    val position = LatLng(latitude, longitude)
    activity.runOnUiThread {
      // If the map is already in the process of a camera update, then don't move it.
      if (!cameraIdle) {
        return@runOnUiThread
      }
      cameraMarker.isVisible = true
      cameraMarker.position = position
      cameraMarker.rotation = heading.toFloat()

      if(setFirst){
        val cameraPositionBuilder: CameraPosition.Builder = if (!setInitialCameraPosition) {
          // Set the camera position with an initial default zoom level.
          setInitialCameraPosition = true
          CameraPosition.Builder().zoom(21f).target(position)
        } else {
          // Set the camera position and keep the same zoom level.
          CameraPosition.Builder()
            .zoom(googleMap.cameraPosition.zoom)
            .target(position)
        }
        googleMap.moveCamera(
          CameraUpdateFactory.newCameraPosition(cameraPositionBuilder.build()))
        setFirst = false
      }
      if(isClick){
        val adjustedHeading = if (heading < 0) heading + 360 else heading
        googleMap.moveCamera(
          CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
              .target(position)
              .zoom(googleMap.cameraPosition.zoom) // Set your preferred zoom level
              .bearing(adjustedHeading.toFloat()) // Set the orientation of the camera to follow the user's heading
              .build()
          )
        )
        googleMap.uiSettings.isCompassEnabled = true
      }else{
        googleMap.uiSettings.isCompassEnabled = false
      }
    }
  }

  /** Creates and adds a 2D anchor marker on the 2D map view.  */
  private fun createMarker(
    color: Int,
    isViewPoint: Boolean
  ): Marker {
    val markersOptions = MarkerOptions()
      .position(LatLng(0.0,0.0))
      .draggable(false)
      .anchor(0.5f, 0.5f)
      .flat(true)
      .visible(false)
      .icon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(color,isViewPoint)))

    return googleMap.addMarker(markersOptions)!!

  }

  private fun createColoredMarkerBitmap(@ColorInt color: Int,isViewPoint: Boolean): Bitmap {
    val opt = BitmapFactory.Options()
    opt.inMutable = true

    if(!isViewPoint){
      val navigationIcon =
        BitmapFactory.decodeResource(activity.resources, R.drawable.ic_navigation_white_48dp, opt)
      val p = Paint()
      p.colorFilter = LightingColorFilter(color,  /* add= */1)
      val canvas = Canvas(navigationIcon)
      canvas.drawBitmap(navigationIcon,  /* left= */0f,  /* top= */0f, p)

      val resizedIcon = Bitmap.createScaledBitmap(navigationIcon, 100, 100, false)
      return resizedIcon
    }

    val navigationIcon =
      BitmapFactory.decodeResource(activity.resources, R.drawable.ic_destination, opt)
    val p = Paint()
    p.colorFilter = LightingColorFilter(color,  /* add= */1)
    val canvas = Canvas(navigationIcon)
    canvas.drawBitmap(navigationIcon,  /* left= */0f,  /* top= */0f, p)

    val resizedIcon = Bitmap.createScaledBitmap(navigationIcon, 30, 30, false)
    return resizedIcon
  }
}