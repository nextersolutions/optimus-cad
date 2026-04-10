package com.nextersolutions.optimus

import androidx.lifecycle.ViewModel
import com.nextersolutions.optimus.model.CadEntity
import com.nextersolutions.optimus.model.DrawTool
import com.nextersolutions.optimus.model.Geometry
import com.nextersolutions.optimus.model.Vec2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round

data class CadState(
    val entities: List<CadEntity> = emptyList(),
    val selectedTool: DrawTool = DrawTool.LINE,
    val previewEntity: CadEntity? = null,
    val inputPoints: List<Vec2> = emptyList(),
    val statusText: String = "Select a tool and tap to draw",
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
    val showGrid: Boolean = true,
    val snapToGrid: Boolean = true,
    val gridSize: Float = 20f,
)

class CadViewModel : ViewModel() {

    private val _state = MutableStateFlow(CadState())
    val state: StateFlow<CadState> = _state.asStateFlow()

    private var nextId = 1L

    // ── Tool selection ────────────────────────────────────────────────────────
    fun selectTool(tool: DrawTool) {
        _state.value = _state.value.copy(
            selectedTool = tool,
            inputPoints = emptyList(),
            previewEntity = null,
            statusText = toolPrompt(tool, 0)
        )
    }

    // ── Cancel / back: removes the last placed point, or resets if none ───────
    fun cancelLastPoint() {
        val s = _state.value
        if (s.inputPoints.isEmpty()) return   // nothing to cancel
        val remaining = s.inputPoints.dropLast(1)
        _state.value = s.copy(
            inputPoints = remaining,
            previewEntity = null,
            statusText = if (remaining.isEmpty())
                toolPrompt(s.selectedTool, 0)
            else
                "${remaining.size} point(s) placed — tap next or Back to remove"
        )
    }

    // ── Touch input ───────────────────────────────────────────────────────────
    fun onTap(screenX: Float, screenY: Float, viewWidth: Int, viewHeight: Int) {
        val world   = screenToWorld(screenX, screenY, viewWidth, viewHeight)
        val snapped = if (_state.value.snapToGrid) snap(world) else world
        val pts     = _state.value.inputPoints + snapped
        when (_state.value.selectedTool) {
            DrawTool.SELECT   -> handleSelect(snapped)
            DrawTool.LINE     -> handleLine(pts)
            DrawTool.ARC      -> handleArc(pts)
            DrawTool.CIRCLE   -> handleCircle(pts)
            DrawTool.ELLIPSE  -> handleEllipse(pts)
            DrawTool.SPLINE   -> handleSpline(pts)
            DrawTool.POLYLINE -> handlePolyline(pts)
        }
    }

    fun onDoubleTap(screenX: Float, screenY: Float, viewWidth: Int, viewHeight: Int) {
        when (_state.value.selectedTool) {
            DrawTool.SPLINE   -> finishSpline()
            DrawTool.POLYLINE -> finishPolyline()
            else -> {}
        }
    }

    fun onMove(screenX: Float, screenY: Float, viewWidth: Int, viewHeight: Int) {
        val world   = screenToWorld(screenX, screenY, viewWidth, viewHeight)
        val snapped = if (_state.value.snapToGrid) snap(world) else world
        updatePreview(snapped)
    }

    // ── Pan / Zoom ────────────────────────────────────────────────────────────
    fun onPan(dx: Float, dy: Float) {
        val s = _state.value
        _state.value = s.copy(panX = s.panX + dx / s.zoom, panY = s.panY + dy / s.zoom)
    }

    fun onZoom(factor: Float, focusX: Float, focusY: Float, viewWidth: Int, viewHeight: Int) {
        val s       = _state.value
        val newZoom = (s.zoom * factor).coerceIn(0.05f, 50f)
        _state.value = s.copy(zoom = newZoom)
    }

    fun toggleGrid() { _state.value = _state.value.copy(showGrid = !_state.value.showGrid) }
    fun toggleSnap() { _state.value = _state.value.copy(snapToGrid = !_state.value.snapToGrid) }

    fun undo() {
        val s = _state.value
        when {
            s.inputPoints.isNotEmpty() -> cancelLastPoint()
            s.entities.isNotEmpty() ->
                _state.value = s.copy(entities = s.entities.dropLast(1), statusText = "Undone")
        }
    }

    fun clearAll() {
        _state.value = _state.value.copy(
            entities = emptyList(), inputPoints = emptyList(),
            previewEntity = null, statusText = "Canvas cleared"
        )
    }

    // ── Tool handlers ─────────────────────────────────────────────────────────
    private fun handleLine(pts: List<Vec2>) {
        if (pts.size == 1) {
            _state.value = _state.value.copy(inputPoints = pts, statusText = "Tap end point  |  Back = remove last point")
        } else {
            commit(CadEntity.Line(nextId++, pts[0], pts[1]))
            reset()
        }
    }

    private fun handleArc(pts: List<Vec2>) {
        when (pts.size) {
            1 -> _state.value = _state.value.copy(inputPoints = pts, statusText = "Tap point on arc  |  Back = remove last")
            2 -> _state.value = _state.value.copy(inputPoints = pts, statusText = "Tap end point  |  Back = remove last")
            3 -> {
                val res = Geometry.arcCenterFrom3Points(pts[0], pts[1], pts[2])
                if (res == null) {
                    _state.value = _state.value.copy(inputPoints = emptyList(), statusText = "Points collinear — retry"); return
                }
                val (center, radius) = res
                val startA = atan2(pts[0].y - center.y, pts[0].x - center.x)
                val endA   = atan2(pts[2].y - center.y, pts[2].x - center.x)
                commit(CadEntity.Arc(nextId++, center, radius, startA, endA))
                reset()
            }
            else -> _state.value = _state.value.copy(inputPoints = pts)
        }
    }

    private fun handleCircle(pts: List<Vec2>) {
        if (pts.size == 1) {
            _state.value = _state.value.copy(inputPoints = pts, statusText = "Tap radius point  |  Back = remove last")
        } else {
            commit(CadEntity.Circle(nextId++, pts[0], pts[0].distTo(pts[1])))
            reset()
        }
    }

    private fun handleEllipse(pts: List<Vec2>) {
        when (pts.size) {
            1 -> _state.value = _state.value.copy(inputPoints = pts, statusText = "Tap major axis end  |  Back = remove last")
            2 -> _state.value = _state.value.copy(inputPoints = pts, statusText = "Tap minor axis end  |  Back = remove last")
            3 -> {
                val rx      = pts[0].distTo(pts[1])
                val axisDir = (pts[1] - pts[0]).normalized()
                val perp    = Vec2(-axisDir.y, axisDir.x)
                val v       = pts[2] - pts[0]
                val ry      = abs(v.x * perp.x + v.y * perp.y)
                val rotation = atan2(axisDir.y, axisDir.x)
                commit(CadEntity.Ellipse(nextId++, pts[0], rx, ry, rotation))
                reset()
            }
            else -> _state.value = _state.value.copy(inputPoints = pts)
        }
    }

    private fun handleSpline(pts: List<Vec2>) {
        _state.value = _state.value.copy(
            inputPoints = pts,
            statusText  = "Tap more points  |  Double-tap to finish  |  Back = remove last  (${pts.size} pts)"
        )
        updatePreview(pts.last())
    }

    private fun finishSpline() {
        val pts = _state.value.inputPoints
        if (pts.size >= 2) commit(CadEntity.Spline(nextId++, pts))
        reset()
    }

    private fun handlePolyline(pts: List<Vec2>) {
        _state.value = _state.value.copy(
            inputPoints = pts,
            statusText  = "Tap more points  |  Double-tap to finish  |  Back = remove last  (${pts.size} pts)"
        )
        updatePreview(pts.last())
    }

    private fun finishPolyline() {
        val pts = _state.value.inputPoints
        if (pts.size >= 2) commit(CadEntity.Polyline(nextId++, pts))
        reset()
    }

    private fun handleSelect(pt: Vec2) {
        _state.value = _state.value.copy(statusText = "World: (${pt.x.toInt()}, ${pt.y.toInt()})")
    }

    // ── Preview ───────────────────────────────────────────────────────────────
    private fun updatePreview(cursor: Vec2) {
        val s   = _state.value
        val pts = s.inputPoints
        if (pts.isEmpty()) return
        val preview: CadEntity? = when (s.selectedTool) {
            DrawTool.LINE     -> CadEntity.Line(-1, pts[0], cursor, floatArrayOf(1f, 0.8f, 0f, 0.7f))
            DrawTool.ARC      -> when (pts.size) {
                1 -> CadEntity.Line(-1, pts[0], cursor, floatArrayOf(1f, 0.8f, 0f, 0.7f))
                2 -> {
                    val res = Geometry.arcCenterFrom3Points(pts[0], pts[1], cursor)
                    if (res != null) {
                        val (c, r) = res
                        CadEntity.Arc(-1, c, r,
                            atan2(pts[0].y - c.y, pts[0].x - c.x),
                            atan2(cursor.y - c.y, cursor.x - c.x),
                            floatArrayOf(1f, 0.8f, 0f, 0.7f))
                    } else null
                }
                else -> null
            }
            DrawTool.CIRCLE   -> CadEntity.Circle(-1, pts[0], pts[0].distTo(cursor), floatArrayOf(1f, 0.8f, 0f, 0.7f))
            DrawTool.ELLIPSE  -> when (pts.size) {
                1 -> CadEntity.Line(-1, pts[0], cursor, floatArrayOf(1f, 0.8f, 0f, 0.7f))
                2 -> {
                    val rx      = pts[0].distTo(pts[1])
                    val axisDir = (pts[1] - pts[0]).normalized()
                    val perp    = Vec2(-axisDir.y, axisDir.x)
                    val v       = cursor - pts[0]
                    val ry      = abs(v.x * perp.x + v.y * perp.y)
                    CadEntity.Ellipse(-1, pts[0], rx, ry, atan2(axisDir.y, axisDir.x), floatArrayOf(1f, 0.8f, 0f, 0.7f))
                }
                else -> null
            }
            DrawTool.SPLINE   -> if (pts.size >= 1) CadEntity.Spline(-1, pts + cursor, floatArrayOf(1f, 0.8f, 0f, 0.7f)) else null
            DrawTool.POLYLINE -> if (pts.size >= 1) CadEntity.Polyline(-1, pts + cursor, color = floatArrayOf(1f, 0.8f, 0f, 0.7f)) else null
            else -> null
        }
        _state.value = s.copy(previewEntity = preview)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun commit(e: CadEntity) {
        _state.value = _state.value.copy(entities = _state.value.entities + e)
    }

    private fun reset() {
        val s = _state.value
        _state.value = s.copy(
            inputPoints   = emptyList(),
            previewEntity = null,
            statusText    = toolPrompt(s.selectedTool, 0)
        )
    }

    fun screenToWorld(sx: Float, sy: Float, vw: Int, vh: Int): Vec2 {
        val s  = _state.value
        val nx = sx / vw  - 0.5f   // -0.5 … 0.5
        val ny = -(sy / vh - 0.5f)
        return Vec2(
            nx * (vw / s.zoom) - s.panX,
            ny * (vh / s.zoom) - s.panY
        )
    }

    private fun snap(p: Vec2): Vec2 {
        val g = _state.value.gridSize
        return Vec2(round(p.x / g) * g, round(p.y / g) * g)
    }

    private fun toolPrompt(tool: DrawTool, step: Int) = when (tool) {
        DrawTool.SELECT   -> "Drag to pan  |  Pinch to zoom"
        DrawTool.LINE     -> "Tap start point"
        DrawTool.ARC      -> "Tap start point"
        DrawTool.CIRCLE   -> "Tap center"
        DrawTool.ELLIPSE  -> "Tap center"
        DrawTool.SPLINE   -> "Tap control points  |  Double-tap to finish"
        DrawTool.POLYLINE -> "Tap vertices  |  Double-tap to finish"
    }
}
