package com.nextersolutions.optimus.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.nextersolutions.optimus.model.DrawTool

class CadGLSurfaceView(
    context: Context,
    private val renderer: CadRenderer,
    private val getCurrentTool: () -> DrawTool,
    private val onTap: (Float, Float) -> Unit,
    private val onDoubleTap: (Float, Float) -> Unit,
    private val onMove: (Float, Float) -> Unit,
    private val onPan: (Float, Float) -> Unit,
    private val onZoom: (Float, Float, Float) -> Unit,
) : GLSurfaceView(context) {

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap(e.x, e.y)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap(e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // Only pan in SELECT mode
            if (getCurrentTool() != DrawTool.SELECT) return false
            if (scaleDetector.isInProgress) return false
            onPan(-dx, dy)
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Only zoom in SELECT mode
            if (getCurrentTool() != DrawTool.SELECT) return false
            onZoom(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // Cursor/preview update on move — always, so preview works during drawing
        if (event.action == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress) {
            onMove(event.x, event.y)
        }
        return true
    }
}
