package com.nextersolutions.optimus

import androidx.lifecycle.ViewModel
import com.nextersolutions.optimus.model.CadEntity
import com.nextersolutions.optimus.model.DrawTool
import com.nextersolutions.optimus.model.EditHandle
import com.nextersolutions.optimus.model.Extrude
import com.nextersolutions.optimus.model.Geometry
import com.nextersolutions.optimus.model.Vec2
import com.nextersolutions.optimus.model.applyHandle
import com.nextersolutions.optimus.model.entityHandles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.round

// Screen-pixel radius used for handle hit-testing in SELECT mode
private const val HIT_PX = 32f

// ─────────────────────────────────────────────────────────────────────────────
// Immutable snapshot of the entire application state.
// The ViewModel emits a new copy whenever anything changes.
// ─────────────────────────────────────────────────────────────────────────────
data class CadState(
    // ── Sketch entities (2D + 3D solids live in the same list) ───────────────
    val entities: List<CadEntity> = emptyList(),        // all committed entities
    val selectedTool: DrawTool = DrawTool.LINE,          // currently active tool
    val previewEntity: CadEntity? = null,                // ghost entity while drawing
    val inputPoints: List<Vec2> = emptyList(),           // points placed so far for the current shape
    val statusText: String = "Select a tool and tap to draw", // bottom status hint

    // ── 2D viewport (used when is3DMode == false) ─────────────────────────────
    val panX: Float = 0f,                               // world-space horizontal offset
    val panY: Float = 0f,                               // world-space vertical offset
    val zoom: Float = 1f,                               // pixels per world unit
    val showGrid: Boolean = true,                        // grid visibility toggle
    val snapToGrid: Boolean = true,                      // snap cursor to grid intersections
    val gridSize: Float = 20f,                           // grid cell size in world units

    // ── 3D viewport (used when is3DMode == true) ──────────────────────────────
    val is3DMode: Boolean = false,                       // true = 3D perspective view
    val orbitYaw: Float = 30f,                         // camera yaw angle  (degrees, around Z)
    val orbitPitch: Float = 25f,                         // camera pitch angle (degrees, elevation)
    val orbitDist: Float = 800f,                        // camera distance from world origin

    // ── Selection / vertex editing ────────────────────────────────────────────
    val selectedEntityId: Long? = null,                  // id of the currently selected entity
    val hoveredHandle: EditHandle? = null,               // handle under the user's finger (tooltip)
    val editDialogHandle: EditHandle? = null,            // handle currently open in the edit dialog

    // ── Extrude operation ─────────────────────────────────────────────────────
    val extrudeDialogOpen: Boolean = false,              // true = depth input dialog is showing
)

class CadViewModel : ViewModel() {

    private val _state = MutableStateFlow(CadState())   // mutable internal state
    val state: StateFlow<CadState> = _state.asStateFlow() // read-only public state

    private var nextId =
        1L                              // monotonically increasing entity id counter

    // ─────────────────────────────────────────────────────────────────────────
    // Tool selection — resets any in-progress drawing
    // ─────────────────────────────────────────────────────────────────────────
    fun selectTool(tool: DrawTool) {
        _state.value = _state.value.copy(
            selectedTool = tool,            // switch to the chosen tool
            inputPoints = emptyList(),     // discard any partially entered shape
            previewEntity = null,            // remove the preview ghost
            statusText = toolPrompt(tool) // update the status bar hint
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cancel the last placed input point (Back button / Undo while drawing)
    // ─────────────────────────────────────────────────────────────────────────
    fun cancelLastPoint() {
        val s = _state.value
        if (s.inputPoints.isEmpty()) return           // nothing to cancel
        val remaining = s.inputPoints.dropLast(1)     // remove the most recently placed point
        _state.value = s.copy(
            inputPoints = remaining,
            previewEntity = null,
            statusText = if (remaining.isEmpty())
                toolPrompt(s.selectedTool)            // back to initial prompt
            else
                "${remaining.size} point(s) — tap next or Back to remove"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single-tap: place a point or select an entity, depending on active tool
    // ─────────────────────────────────────────────────────────────────────────
    fun onTap(screenX: Float, screenY: Float, viewWidth: Int, viewHeight: Int) {
        val world = screenToWorld(screenX, screenY, viewWidth, viewHeight) // screen → world coords
        val snapped =
            if (_state.value.snapToGrid) snap(world) else world    // optionally snap to grid
        val pts = _state.value.inputPoints + snapped                     // append new point

        when (_state.value.selectedTool) {
            DrawTool.SELECT -> handleSelect(world, viewWidth, viewHeight) // hit-test, no snapping
            DrawTool.LINE -> handleLine(pts)
            DrawTool.ARC -> handleArc(pts)
            DrawTool.CIRCLE -> handleCircle(pts)
            DrawTool.ELLIPSE -> handleEllipse(pts)
            DrawTool.SPLINE -> handleSpline(pts)
            DrawTool.POLYLINE -> handlePolyline(pts)
            DrawTool.EXTRUDE -> { /* extrude is dialog-driven, not tap-driven */
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Double-tap: finish multi-point shapes, or open vertex edit dialog
    // ─────────────────────────────────────────────────────────────────────────
    fun onDoubleTap(screenX: Float, screenY: Float, viewWidth: Int, viewHeight: Int) {
        when (_state.value.selectedTool) {
            DrawTool.SELECT -> onDoubleTapSelect(screenX, screenY, viewWidth, viewHeight)
            DrawTool.SPLINE -> finishSpline()   // double-tap ends spline input
            DrawTool.POLYLINE -> finishPolyline() // double-tap ends polyline input
            else -> {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Finger move: update the preview ghost entity
    // ─────────────────────────────────────────────────────────────────────────
    fun onMove(screenX: Float, screenY: Float, viewWidth: Int, viewHeight: Int) {
        val world = screenToWorld(screenX, screenY, viewWidth, viewHeight)
        val snapped = if (_state.value.snapToGrid) snap(world) else world
        updatePreview(snapped)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pan gesture (SELECT mode, 2D only): shift the viewport
    // dx, dy are in screen pixels; we divide by zoom to convert to world units
    // ─────────────────────────────────────────────────────────────────────────
    fun onPan(dx: Float, dy: Float) {
        val s = _state.value
        _state.value = s.copy(
            panX = s.panX + dx / s.zoom,   // horizontal pan in world space
            panY = s.panY + dy / s.zoom    // vertical pan in world space
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pinch zoom gesture (SELECT mode, 2D only)
    // factor > 1 = zoom in, factor < 1 = zoom out; clamped to sane range
    // ─────────────────────────────────────────────────────────────────────────
    fun onZoom(factor: Float, focusX: Float, focusY: Float, viewWidth: Int, viewHeight: Int) {
        val s = _state.value
        val newZoom = (s.zoom * factor).coerceIn(0.05f, 50f) // clamp to [5%, 5000%]
        _state.value = s.copy(zoom = newZoom)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Orbit gesture (SELECT mode, 3D only): rotate the camera around the origin
    // dYaw   — horizontal drag → rotates around Z axis
    // dPitch — vertical drag   → changes elevation angle; clamped to avoid gimbal flip
    // ─────────────────────────────────────────────────────────────────────────
    fun onOrbit(dYaw: Float, dPitch: Float) {
        val s = _state.value
        _state.value = s.copy(
            orbitYaw = s.orbitYaw + dYaw,                         // accumulate yaw freely
            orbitPitch = (s.orbitPitch + dPitch).coerceIn(-89f, 89f)  // clamp pitch to avoid flip
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pinch zoom in 3D: moves the camera closer/farther from the origin
    // ─────────────────────────────────────────────────────────────────────────
    fun onOrbitZoom(factor: Float) {
        val s = _state.value
        _state.value = s.copy(
            orbitDist = (s.orbitDist / factor).coerceIn(50f, 5000f) // clamp distance
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Toggle between 2D sketch mode and 3D viewing mode
    // ─────────────────────────────────────────────────────────────────────────
    fun toggle3D() {
        val s = _state.value
        _state.value = s.copy(
            is3DMode = !s.is3DMode,                                 // flip the mode flag
            inputPoints = emptyList(),                              // cancel any drawing in progress
            previewEntity = null,
            statusText = if (!s.is3DMode)
                "3D View — drag to orbit  |  pinch to zoom"
            else
                toolPrompt(s.selectedTool)                            // restore 2D prompt
        )
    }

    fun toggleGrid() {
        _state.value = _state.value.copy(showGrid = !_state.value.showGrid)
    }

    fun toggleSnap() {
        _state.value = _state.value.copy(snapToGrid = !_state.value.snapToGrid)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Undo: cancel last input point while drawing, or delete last entity
    // ─────────────────────────────────────────────────────────────────────────
    fun undo() {
        val s = _state.value
        when {
            s.inputPoints.isNotEmpty() -> cancelLastPoint()          // in-progress: remove last point
            s.entities.isNotEmpty() ->                            // committed: remove last entity
                _state.value = s.copy(
                    entities = s.entities.dropLast(1),
                    statusText = "Undone"
                )
        }
    }

    fun clearAll() {
        _state.value = _state.value.copy(
            entities = emptyList(),
            inputPoints = emptyList(),
            previewEntity = null,
            statusText = "Canvas cleared"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Extrude dialog: open, confirm, cancel
    // ─────────────────────────────────────────────────────────────────────────

    // Opens the extrude depth dialog. Works in both 2D and 3D mode.
    // If a specific extrudable entity is selected it uses that; otherwise falls back
    // to the first extrudable entity in the list so the user can extrude from 3D view too.
    fun openExtrudeDialog() {
        val s = _state.value

        // Try the explicitly selected entity first, then any extrudable entity
        val candidate = s.entities.find { it.id == s.selectedEntityId }
            ?: s.entities.firstOrNull { it is CadEntity.Polyline || it is CadEntity.Circle || it is CadEntity.Ellipse }

        val canExtrude = when (candidate) {
            is CadEntity.Circle -> true
            is CadEntity.Ellipse -> true
            is CadEntity.Polyline -> candidate.points.size >= 3  // need at least a triangle
            else -> false
        }

        if (!canExtrude || candidate == null) {
            _state.value = s.copy(statusText = "Draw a Circle, Ellipse, or Polyline first")
            return
        }

        // Auto-select the candidate so confirmExtrude can find it
        _state.value = s.copy(
            extrudeDialogOpen = true,
            selectedEntityId = candidate.id
        )
    }

    // Performs the extrusion with the given depth and replaces the 2D profile with a Solid3D
    fun confirmExtrude(depth: Float) {
        val s = _state.value
        val selected = s.entities.find { it.id == s.selectedEntityId } ?: return

        // Extract the profile polygon depending on entity type
        val profile: List<Vec2>? = when (selected) {
            is CadEntity.Polyline -> if (selected.points.size >= 3) selected.points else null
            is CadEntity.Circle -> Geometry.circlePoints(
                selected.center,
                selected.radius
            ) // tessellate to polygon
            is CadEntity.Ellipse -> Geometry.ellipsePoints(
                selected.center,
                selected.rx,
                selected.ry,
                selected.rotation
            )

            else -> null
        }

        if (profile == null) {
            _state.value =
                s.copy(extrudeDialogOpen = false, statusText = "Profile needs ≥ 3 points")
            return
        }

        val mesh = Extrude.extrude(profile, depth)              // tessellate the solid
        val solid = CadEntity.Solid3D(nextId++, profile, depth, mesh) // create the entity

        _state.value = s.copy(
            entities = (s.entities - selected) + solid, // replace 2D profile with 3D solid
            extrudeDialogOpen = false,
            selectedEntityId = solid.id,                        // keep the new solid selected
            is3DMode = true,                            // switch to 3D view automatically
            statusText = "Extruded ${fmtF(depth)} units — drag to orbit"
        )
    }

    fun dismissExtrudeDialog() {
        _state.value = _state.value.copy(extrudeDialogOpen = false) // close without doing anything
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool input handlers
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleLine(pts: List<Vec2>) {
        if (pts.size == 1) {
            // First point placed — wait for the second
            _state.value = _state.value.copy(
                inputPoints = pts,
                statusText = "Tap end point  |  Back = remove last"
            )
        } else {
            // Both points placed — commit the line
            commit(CadEntity.Line(nextId++, pts[0], pts[1]))
            reset()
        }
    }

    private fun handleArc(pts: List<Vec2>) {
        when (pts.size) {
            1 -> _state.value = _state.value.copy(
                inputPoints = pts,
                statusText = "Tap mid-point on arc  |  Back = remove last"
            )

            2 -> _state.value = _state.value.copy(
                inputPoints = pts,
                statusText = "Tap end point  |  Back = remove last"
            )

            3 -> {
                // All three points placed — compute the circumcircle
                val res = Geometry.arcCenterFrom3Points(pts[0], pts[1], pts[2])
                if (res == null) {
                    // Points are collinear — cannot form a circle
                    _state.value = _state.value.copy(
                        inputPoints = emptyList(),
                        statusText = "Points collinear — retry"
                    )
                    return
                }
                val (center, radius) = res
                val startA = atan2(pts[0].y - center.y, pts[0].x - center.x) // angle to start point
                val endA = atan2(pts[2].y - center.y, pts[2].x - center.x) // angle to end point
                commit(CadEntity.Arc(nextId++, center, radius, startA, endA))
                reset()
            }

            else -> _state.value = _state.value.copy(inputPoints = pts)
        }
    }

    private fun handleCircle(pts: List<Vec2>) {
        if (pts.size == 1) {
            _state.value = _state.value.copy(
                inputPoints = pts,
                statusText = "Tap radius point  |  Back = remove last"
            )
        } else {
            // Second point: distance from center = radius
            commit(CadEntity.Circle(nextId++, pts[0], pts[0].distTo(pts[1])))
            reset()
        }
    }

    private fun handleEllipse(pts: List<Vec2>) {
        when (pts.size) {
            1 -> _state.value = _state.value.copy(
                inputPoints = pts,
                statusText = "Tap major axis end  |  Back = remove last"
            )

            2 -> _state.value = _state.value.copy(
                inputPoints = pts,
                statusText = "Tap minor axis end  |  Back = remove last"
            )

            3 -> {
                val rx = pts[0].distTo(pts[1])                 // semi-major axis
                val axisDir = (pts[1] - pts[0]).normalized()        // direction of major axis
                val perp = Vec2(-axisDir.y, axisDir.x)           // perpendicular direction
                val v = pts[2] - pts[0]                       // vector from center to minor point
                val ry = abs(v.x * perp.x + v.y * perp.y)     // project onto perp → semi-minor
                val rotation = atan2(axisDir.y, axisDir.x)          // angle of major axis
                commit(CadEntity.Ellipse(nextId++, pts[0], rx, ry, rotation))
                reset()
            }

            else -> _state.value = _state.value.copy(inputPoints = pts)
        }
    }

    private fun handleSpline(pts: List<Vec2>) {
        _state.value = _state.value.copy(
            inputPoints = pts,
            statusText = "Tap more points  |  Double-tap to finish  |  Back = remove last  (${pts.size} pts)"
        )
        updatePreview(pts.last()) // show rubber-band preview from last point to cursor
    }

    private fun finishSpline() {
        val pts = _state.value.inputPoints
        if (pts.size >= 2) commit(CadEntity.Spline(nextId++, pts)) // need at least 2 control points
        reset()
    }

    private fun handlePolyline(pts: List<Vec2>) {
        _state.value = _state.value.copy(
            inputPoints = pts,
            statusText = "Tap more points  |  Double-tap to finish  |  Back = remove last  (${pts.size} pts)"
        )
        updatePreview(pts.last())
    }

    private fun finishPolyline() {
        val pts = _state.value.inputPoints
        if (pts.size >= 2) commit(CadEntity.Polyline(nextId++, pts)) // need at least 2 vertices
        reset()
    }

    // SELECT tool — behaves differently in 2D vs 3D:
    // • 2D: accurate world-space hit test against vertex handles and entity proximity
    // • 3D: screen-space coordinates are unreliable (perspective), so a tap on empty
    //   space cycles through all entities one by one, making any entity selectable
    private fun handleSelect(pt: Vec2, viewWidth: Int, viewHeight: Int) {
        val s = _state.value

        // ── 3D mode: cycle-select through entities on each tap ────────────────
        if (s.is3DMode) {
            if (s.entities.isEmpty()) {
                _state.value = s.copy(statusText = "No entities to select")
                return
            }
            val currentIndex = s.entities.indexOfFirst { it.id == s.selectedEntityId }
            val nextIndex = (currentIndex + 1) % s.entities.size   // wrap around
            val next = s.entities[nextIndex]
            val desc = when (next) {
                is CadEntity.Line -> "Line"
                is CadEntity.Arc -> "Arc"
                is CadEntity.Circle -> "Circle (r=${fmtF(next.radius)})"
                is CadEntity.Ellipse -> "Ellipse"
                is CadEntity.Spline -> "Spline (${next.points.size} pts)"
                is CadEntity.Polyline -> "Polyline (${next.points.size} pts)"
                is CadEntity.Solid3D -> "Solid3D (h=${fmtF(next.height)})"
            }
            _state.value = s.copy(
                selectedEntityId = next.id,
                hoveredHandle = null,
                statusText = "Selected: $desc  |  Tap again to cycle  |  Extrude to make 3D"
            )
            return
        }

        // ── 2D mode: accurate hit test against handles then entity proximity ───
        val hitRadius = HIT_PX / s.zoom    // world-space hit radius

        // Prioritise handles of the already-selected entity, then all others
        val allHandles = if (s.selectedEntityId != null)
            s.entities.filter { it.id == s.selectedEntityId }.flatMap { entityHandles(it) } +
                    s.entities.filter { it.id != s.selectedEntityId }.flatMap { entityHandles(it) }
        else
            s.entities.flatMap { entityHandles(it) }

        // Find the nearest handle within hit radius
        val hit = allHandles.minByOrNull { it.pos.distTo(pt) }
            ?.takeIf { it.pos.distTo(pt) <= hitRadius }

        if (hit != null) {
            // Tapped on a vertex handle → show its coordinates in the status bar
            _state.value = s.copy(
                selectedEntityId = hit.entityId,
                hoveredHandle = hit,
                statusText = "${hit.label}: (${fmtF(hit.pos.x)}, ${fmtF(hit.pos.y)})"
            )
        } else {
            // Tapped near an entity (looser radius) → select that entity
            val entityHit = s.entities.minByOrNull { e ->
                entityHandles(e).minOfOrNull { h -> h.pos.distTo(pt) } ?: Float.MAX_VALUE
            }?.takeIf { e ->
                entityHandles(e).any { h -> h.pos.distTo(pt) <= hitRadius * 4f }
            }
            _state.value = s.copy(
                selectedEntityId = entityHit?.id,
                hoveredHandle = null,
                statusText = if (entityHit != null)
                    "Entity selected — tap a vertex to see coords, double-tap to edit"
                else
                    "Tap an entity or vertex to select"
            )
        }
    }

    // Double-tap in SELECT mode → open coordinate edit dialog for nearest handle
    fun onDoubleTapSelect(screenX: Float, screenY: Float, viewWidth: Int, viewHeight: Int) {
        val s = _state.value
        val pt = screenToWorld(screenX, screenY, viewWidth, viewHeight)
        val hitRadius = HIT_PX / s.zoom

        // Only search handles of the selected entity (or all, if nothing selected)
        val handles = if (s.selectedEntityId != null)
            s.entities.filter { it.id == s.selectedEntityId }.flatMap { entityHandles(it) }
        else
            s.entities.flatMap { entityHandles(it) }

        val hit = handles.minByOrNull { it.pos.distTo(pt) }
            ?.takeIf { it.pos.distTo(pt) <= hitRadius }

        if (hit != null) {
            _state.value = s.copy(editDialogHandle = hit, selectedEntityId = hit.entityId)
        }
    }

    // Called when the user confirms a vertex position from the coordinate dialog
    fun applyHandleEdit(handle: EditHandle, newPos: Vec2) {
        val s = _state.value
        val updated = s.entities.map { e ->
            if (e.id == handle.entityId) applyHandle(e, handle.pointIndex, newPos) else e
        }
        _state.value = s.copy(
            entities = updated,
            editDialogHandle = null,
            hoveredHandle = handle.copy(pos = newPos),  // refresh tooltip with new position
            statusText = "${handle.label} → (${fmtF(newPos.x)}, ${fmtF(newPos.y)})"
        )
    }

    fun dismissEditDialog() {
        _state.value = _state.value.copy(editDialogHandle = null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Preview ghost entity updated on every finger move
    // ─────────────────────────────────────────────────────────────────────────
    private fun updatePreview(cursor: Vec2) {
        val s = _state.value
        val pts = s.inputPoints
        if (pts.isEmpty()) return                                    // nothing started yet

        val preview: CadEntity? = when (s.selectedTool) {
            DrawTool.LINE -> CadEntity.Line(
                -1, pts[0], cursor,
                floatArrayOf(1f, 0.8f, 0f, 0.7f)
            )                   // yellow semi-transparent

            DrawTool.ARC -> when (pts.size) {
                1 -> CadEntity.Line(-1, pts[0], cursor, floatArrayOf(1f, 0.8f, 0f, 0.7f))
                2 -> {
                    val res = Geometry.arcCenterFrom3Points(pts[0], pts[1], cursor)
                    if (res != null) {
                        val (c, r) = res
                        CadEntity.Arc(
                            -1, c, r,
                            atan2(pts[0].y - c.y, pts[0].x - c.x),
                            atan2(cursor.y - c.y, cursor.x - c.x),
                            floatArrayOf(1f, 0.8f, 0f, 0.7f)
                        )
                    } else null
                }

                else -> null
            }

            DrawTool.CIRCLE -> CadEntity.Circle(
                -1, pts[0], pts[0].distTo(cursor),
                floatArrayOf(1f, 0.8f, 0f, 0.7f)
            )

            DrawTool.ELLIPSE -> when (pts.size) {
                1 -> CadEntity.Line(-1, pts[0], cursor, floatArrayOf(1f, 0.8f, 0f, 0.7f))
                2 -> {
                    val rx = pts[0].distTo(pts[1])
                    val axisDir = (pts[1] - pts[0]).normalized()
                    val perp = Vec2(-axisDir.y, axisDir.x)
                    val v = cursor - pts[0]
                    val ry = abs(v.x * perp.x + v.y * perp.y)
                    CadEntity.Ellipse(
                        -1, pts[0], rx, ry,
                        atan2(axisDir.y, axisDir.x), floatArrayOf(1f, 0.8f, 0f, 0.7f)
                    )
                }

                else -> null
            }

            DrawTool.SPLINE -> if (pts.isNotEmpty())
                CadEntity.Spline(-1, pts + cursor, floatArrayOf(1f, 0.8f, 0f, 0.7f)) else null

            DrawTool.POLYLINE -> if (pts.isNotEmpty())
                CadEntity.Polyline(
                    -1,
                    pts + cursor,
                    color = floatArrayOf(1f, 0.8f, 0f, 0.7f)
                ) else null

            else -> null
        }
        _state.value = s.copy(previewEntity = preview)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun commit(e: CadEntity) {
        _state.value = _state.value.copy(entities = _state.value.entities + e)
    }

    private fun reset() {
        val s = _state.value
        _state.value = s.copy(
            inputPoints = emptyList(),
            previewEntity = null,
            statusText = toolPrompt(s.selectedTool)
        )
    }

    // Converts a screen pixel coordinate to world-space 2D coordinate
    // The screen centre maps to (0, 0) + pan; Y is flipped (screen Y grows downward)
    fun screenToWorld(sx: Float, sy: Float, vw: Int, vh: Int): Vec2 {
        val s = _state.value
        val nx = sx / vw - 0.5f            // normalised x: -0.5 (left) to +0.5 (right)
        val ny = -(sy / vh - 0.5f)          // normalised y: flipped so +Y is up
        return Vec2(
            nx * (vw / s.zoom) - s.panX,   // scale by world size then offset by pan
            ny * (vh / s.zoom) - s.panY
        )
    }

    // Snaps a world-space point to the nearest grid intersection
    private fun snap(p: Vec2): Vec2 {
        val g = _state.value.gridSize
        return Vec2(round(p.x / g) * g, round(p.y / g) * g)
    }

    // Formats a float for display: integer if whole number, otherwise 2 decimal places
    private fun fmtF(v: Float) =
        if (v == floor(v.toDouble()).toFloat()) v.toInt().toString() else "%.2f".format(v)

    // Returns the status bar hint for the first step of each tool
    private fun toolPrompt(tool: DrawTool) = when (tool) {
        DrawTool.SELECT -> "Tap entity or vertex to select  |  Drag to pan  |  Pinch to zoom"
        DrawTool.LINE -> "Tap start point"
        DrawTool.ARC -> "Tap start point"
        DrawTool.CIRCLE -> "Tap center"
        DrawTool.ELLIPSE -> "Tap center"
        DrawTool.SPLINE -> "Tap control points  |  Double-tap to finish"
        DrawTool.POLYLINE -> "Tap vertices  |  Double-tap to finish"
        DrawTool.EXTRUDE -> "Select a closed shape, then tap Extrude"
    }
}
