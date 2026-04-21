package com.nextersolutions.optimus.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.nextersolutions.optimus.model.DrawTool

// ─────────────────────────────────────────────────────────────────────────────
// Custom GLSurfaceView that routes touch gestures to the ViewModel.
//
// Gesture routing depends on the current tool AND view mode:
//
//   SELECT + 2D  → single-finger drag = pan, pinch = zoom
//   SELECT + 3D  → single-finger drag = orbit (yaw/pitch), pinch = zoom distance
//   Any draw tool → all touch events become taps/moves for point placement;
//                   pan and zoom are disabled to prevent accidental canvas drift
// ─────────────────────────────────────────────────────────────────────────────
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

    // Guards against onDown firing for the second tap of a double-tap sequence
    private var suppressNextDown = false

    // ── Gesture detector: handles tap, double-tap, and scroll (drag) ─────────
    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {

            // onDown fires immediately on finger-down — used for drawing tools so there
            // is zero delay between the touch and the point being placed.
            // SELECT is excluded here because it needs to wait to distinguish tap from drag.
            override fun onDown(e: MotionEvent): Boolean {
                if (getCurrentTool() != DrawTool.SELECT && !suppressNextDown) {
                    onTap(e.x, e.y)  // instant placement for all drawing tools
                }
                suppressNextDown = false  // reset the guard after each down event
                return true              // must return true so the detector continues tracking
            }

            // onSingleTapConfirmed fires ~300ms after touch (only when no double-tap follows).
            // Used only for SELECT so we can distinguish a tap from a drag properly.
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (getCurrentTool() == DrawTool.SELECT) {
                    onTap(e.x, e.y)  // delayed tap only needed for SELECT hit-testing
                }
                return true
            }

            // Double-tap: finishes multi-point shapes or opens vertex edit dialog.
            // We suppress the next onDown so the second finger-down of the double-tap
            // doesn't accidentally place an extra point.
            override fun onDoubleTap(e: MotionEvent): Boolean {
                suppressNextDown = true   // block the onDown that already fired or is about to
                onDoubleTap(e.x, e.y)
                return true
            }

            // Scroll (one-finger drag): pan in 2D, orbit in 3D (SELECT mode only)
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (getCurrentTool() != DrawTool.SELECT) return false // drawing tools: no drag
                if (scaleDetector.isInProgress) return false           // ignore if pinching

                if (getIs3DMode()) {
                    // 3D: convert pixel delta to orbit angles
                    onOrbit(-dx * 0.3f, dy * 0.3f)   // dx inverted: drag right = rotate right
                } else {
                    // 2D: translate pan in world space
                    onPan(-dx, dy)                     // dy sign: screen Y down, world Y up
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
