package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Draws the detected door on top of the camera view. Touches pass through. */
class DoorBoxOverlay @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : View(context, attrs) {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 6f * resources.displayMetrics.density
    color = Color.parseColor("#00E676")
  }
  private var rect: RectF? = null

  fun setBox(left: Float, top: Float, right: Float, bottom: Float) {
    post {
      rect = RectF(left, top, right, bottom)
      invalidate()
    }
  }

  fun clearBox() {
    post {
      if (rect == null) return@post
      rect = null
      invalidate()
    }
  }

  override fun onDraw(canvas: Canvas) {
    val box = rect ?: return
    canvas.drawRoundRect(box, 16f, 16f, paint)
  }

  override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false
}
