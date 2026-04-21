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
    private val getCurrentTool: () -> DrawTool,   // lambda: reads selected tool from ViewModel
    private val getIs3DMode:    () -> Boolean,     // lambda: reads 3D flag from ViewModel
    private val onTap:       (Float, Float) -> Unit,
    private val onDoubleTap: (Float, Float) -> Unit,
    private val onMove:      (Float, Float) -> Unit,
    private val onPan:       (Float, Float) -> Unit,   // 2D pan: delta in screen pixels
    private val onZoom:      (Float, Float, Float) -> Unit, // 2D zoom: factor, focusX, focusY
    private val onOrbit:     (Float, Float) -> Unit,   // 3D orbit: dYaw, dPitch in degrees
    private val onOrbitZoom: (Float) -> Unit,           // 3D zoom: scale factor
) : GLSurfaceView(context) {

    // Tracks the last known touch position for computing deltas on scroll
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // ── Gesture detector: handles tap, double-tap, and scroll (drag) ─────────
    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {

            // onDown fires immediately on finger-down.
            // For drawing tools we place the point instantly (no 300ms delay).
            // SELECT waits for onSingleTapConfirmed to avoid placing on drag-start.
            override fun onDown(e: MotionEvent): Boolean {
                if (getCurrentTool() != DrawTool.SELECT) {
                    onTap(e.x, e.y)
                }
                return true
            }

            // onSingleTapConfirmed fires ~300ms later, only when no double-tap follows.
            // Used exclusively by SELECT so it can distinguish a tap from a drag.
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (getCurrentTool() == DrawTool.SELECT) {
                    onTap(e.x, e.y)
                }
                return true
            }

            // Double-tap only matters for SPLINE and POLYLINE (finish the shape) and SELECT
            // (open vertex edit dialog). For all other tools — LINE, ARC, CIRCLE, ELLIPSE,
            // EXTRUDE — double-tap has no special meaning and should be ignored entirely.
            // We do NOT suppress onDown here because for those tools two quick taps are just
            // two normal point placements, not a "finish" gesture.
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val tool = getCurrentTool()
                if (tool == DrawTool.SPLINE || tool == DrawTool.POLYLINE || tool == DrawTool.SELECT) {
                    onDoubleTap(e.x, e.y)
                }
                // For all other tools: do nothing — the two onDown events already placed both points
                return true
            }

            // Scroll (one-finger drag): pan in 2D, orbit in 3D — SELECT mode only
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (getCurrentTool() != DrawTool.SELECT) return false
                if (scaleDetector.isInProgress) return false

                if (getIs3DMode()) {
                    onOrbit(-dx * 0.3f, dy * 0.3f)
                } else {
                    onPan(-dx, dy)
                }
                return true
            }
        }
    )

    // ── Scale (pinch) detector: zoom in 2D, move camera in 3D ────────────────
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (getCurrentTool() != DrawTool.SELECT) return false // drawing tools: no pinch

                if (getIs3DMode()) {
                    onOrbitZoom(detector.scaleFactor)  // 3D: move camera closer/farther
                } else {
                    onZoom(detector.scaleFactor, detector.focusX, detector.focusY) // 2D: zoom
                }
                return true
            }
        }
    )

    init {
        setEGLContextClientVersion(2)  // request OpenGL ES 2.0 context
        setRenderer(renderer)          // attach the renderer
        renderMode = RENDERMODE_CONTINUOUSLY // redraw every frame (not just on dirty)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)   // let the scale detector see all events first
        gestureDetector.onTouchEvent(event) // then the gesture detector

        // Also track raw finger position for preview ghost updates (no detector needed)
        if (event.action == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress) {
            onMove(event.x, event.y)        // update rubber-band preview while drawing
        }
        return true // consume all touch events
    }
}
