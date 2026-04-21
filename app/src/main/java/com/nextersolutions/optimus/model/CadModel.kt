package com.nextersolutions.optimus.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// 2D point / vector — used for all sketch geometry on the XY plane
// ─────────────────────────────────────────────────────────────────────────────
data class Vec2(val x: Float, val y: Float) {
    // Vector addition: translates a point by another vector
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    // Vector subtraction: gives the vector from o to this
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    // Scalar multiplication: scales the vector
    operator fun times(s: Float) = Vec2(x * s, y * s)
    // Euclidean distance between two 2D points
    fun distTo(o: Vec2) = sqrt((x - o.x).pow(2) + (y - o.y).pow(2))
    // Magnitude of this vector
    fun length() = sqrt(x * x + y * y)
    // Returns a unit vector in the same direction (safe: returns zero if length==0)
    fun normalized(): Vec2 {
        val l = length()
        return if (l == 0f) Vec2(0f, 0f) else Vec2(x / l, y / l)
    }
    // Converts this 2D point to 3D by setting Z=0 (lies on the XY plane)
    fun toVec3() = Vec3(x, y, 0f)
}

// ─────────────────────────────────────────────────────────────────────────────
// 3D point / vector — used for all 3D solid geometry
// ─────────────────────────────────────────────────────────────────────────────
data class Vec3(val x: Float, val y: Float, val z: Float) {
    // Vector addition in 3D
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    // Vector subtraction in 3D
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    // Scalar multiplication in 3D
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    // 3D Euclidean distance
    fun distTo(o: Vec3) = sqrt((x-o.x).pow(2) + (y-o.y).pow(2) + (z-o.z).pow(2))
    // Magnitude of 3D vector
    fun length() = sqrt(x*x + y*y + z*z)
    // Unit vector; safe against zero-length
    fun normalized(): Vec3 {
        val l = length()
        return if (l == 0f) Vec3(0f, 0f, 0f) else Vec3(x/l, y/l, z/l)
    }
    // Cross product: gives a vector perpendicular to both this and o
    fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,  // x component of cross product
        z * o.x - x * o.z,  // y component
        x * o.y - y * o.x   // z component
    )
    // Dot product: scalar projection; used for lighting and angle calculations
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    // Drops Z, returning the 2D projection onto the XY plane
    fun toVec2() = Vec2(x, y)
}

// ─────────────────────────────────────────────────────────────────────────────
// A tessellated 3D mesh: flat list of triangles ready for OpenGL
// Each triangle = 3 consecutive Vec3 vertices (positions)
// Normals list has the same count — one normal per vertex
// ─────────────────────────────────────────────────────────────────────────────
data class Mesh3D(
    val vertices: List<Vec3>,  // flat triangle list: every 3 entries = one triangle
    val normals:  List<Vec3>   // per-vertex normals for basic diffuse lighting
)

// ─────────────────────────────────────────────────────────────────────────────
// All available tools in the toolbar
// ─────────────────────────────────────────────────────────────────────────────
enum class DrawTool {
    SELECT,    // select & edit vertices
    LINE,      // 2-point line segment
    ARC,       // 3-point arc
    CIRCLE,    // center + radius circle
    ELLIPSE,   // center + major + minor ellipse
    SPLINE,    // Catmull-Rom spline (N points)
    POLYLINE,  // open/closed polyline (N points)
    EXTRUDE    // extrude a selected closed profile into a 3D solid
}

// ─────────────────────────────────────────────────────────────────────────────
// Sealed hierarchy of all CAD entities (both 2D and 3D)
// id < 0 means "preview / temporary" entity — not committed to the model
// ─────────────────────────────────────────────────────────────────────────────
sealed class CadEntity(open val id: Long, open val color: FloatArray) {

    // ── 2D sketch entities ────────────────────────────────────────────────────

    // A straight line segment defined by its two endpoints
    data class Line(
        override val id: Long,
        val start: Vec2,                                        // first endpoint
        val end: Vec2,                                          // second endpoint
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    // A circular arc defined by center, radius, and angular sweep (CCW positive)
    data class Arc(
        override val id: Long,
        val center: Vec2,                                       // arc center in world space
        val radius: Float,                                      // radius in world units
        val startAngle: Float,                                  // start angle in radians
        val endAngle: Float,                                    // end angle in radians (CCW)
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    // A full circle defined by center and radius
    data class Circle(
        override val id: Long,
        val center: Vec2,                                       // center point
        val radius: Float,                                      // radius
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    // An ellipse defined by center, semi-axes, and rotation angle
    data class Ellipse(
        override val id: Long,
        val center: Vec2,                                       // center point
        val rx: Float,                                          // semi-major axis length
        val ry: Float,                                          // semi-minor axis length
        val rotation: Float = 0f,                               // rotation of major axis (radians)
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    // A smooth curve through control points using Catmull-Rom interpolation
    data class Spline(
        override val id: Long,
        val points: List<Vec2>,                                 // control points (interpolated through)
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    // A multi-segment line through a sequence of vertices
    data class Polyline(
        override val id: Long,
        val points: List<Vec2>,                                 // ordered vertices
        val closed: Boolean = false,                            // if true, last connects back to first
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    // ── 3D solid entities ─────────────────────────────────────────────────────

    // A solid produced by extruding a 2D profile along the Z axis
    data class Solid3D(
        override val id: Long,
        val profilePoints: List<Vec2>,                          // original 2D profile (XY plane)
        val height: Float,                                      // extrusion height along +Z
        val mesh: Mesh3D,                                       // tessellated triangles for rendering
        override val color: FloatArray = floatArrayOf(0.3f, 0.6f, 1f, 1f)
    ) : CadEntity(id, color)
}

// ─────────────────────────────────────────────────────────────────────────────
// Edit handle: one draggable/editable vertex on a committed entity
// ─────────────────────────────────────────────────────────────────────────────
data class EditHandle(
    val entityId: Long,      // which entity this handle belongs to
    val pointIndex: Int,     // index meaning depends on entity type (see entityHandles)
    val pos: Vec2,           // current world-space 2D position of the handle
    val label: String        // human-readable name shown in tooltip/dialog ("Start", "P2", etc.)
)

// Returns all editable handles for an entity (2D entities only for now)
fun entityHandles(e: CadEntity): List<EditHandle> = when (e) {
    // Line has two handles: start and end
    is CadEntity.Line -> listOf(
        EditHandle(e.id, 0, e.start, "Start"),
        EditHandle(e.id, 1, e.end,   "End")
    )
    // Arc has three handles: the two arc tips and the center
    is CadEntity.Arc -> listOf(
        EditHandle(e.id, 0,
            Vec2(e.center.x + cos(e.startAngle) * e.radius,
                e.center.y + sin(e.startAngle) * e.radius), "Start"),
        EditHandle(e.id, 1,
            Vec2(e.center.x + cos(e.endAngle)   * e.radius,
                e.center.y + sin(e.endAngle)   * e.radius), "End"),
        EditHandle(e.id, 2, e.center, "Center")
    )
    // Circle has two handles: center and a radius drag point (rightward)
    is CadEntity.Circle -> listOf(
        EditHandle(e.id, 0, e.center, "Center"),
        EditHandle(e.id, 1, Vec2(e.center.x + e.radius, e.center.y), "Radius")
    )
    // Ellipse: center, major-axis end, minor-axis end
    is CadEntity.Ellipse -> listOf(
        EditHandle(e.id, 0, e.center, "Center"),
        EditHandle(e.id, 1, Vec2(
            e.center.x + cos(e.rotation) * e.rx,
            e.center.y + sin(e.rotation) * e.rx), "MajorEnd"),
        EditHandle(e.id, 2, Vec2(
            e.center.x - sin(e.rotation) * e.ry,
            e.center.y + cos(e.rotation) * e.ry), "MinorEnd")
    )
    // Spline/Polyline: one handle per control point, labelled P1, P2, …
    is CadEntity.Spline   -> e.points.mapIndexed { i, p -> EditHandle(e.id, i, p, "P${i+1}") }
    is CadEntity.Polyline -> e.points.mapIndexed { i, p -> EditHandle(e.id, i, p, "P${i+1}") }
    // 3D solids don't expose 2D handles
    is CadEntity.Solid3D  -> emptyList()
}

// Applies a moved handle position back to the entity, returning the updated entity
fun applyHandle(e: CadEntity, index: Int, newPos: Vec2): CadEntity = when (e) {
    // Move start or end of a line
    is CadEntity.Line -> when (index) {
        0    -> e.copy(start = newPos)           // index 0 = start point
        else -> e.copy(end   = newPos)           // index 1 = end point
    }
    // Arc: rebuild center/radius/angles from the three defining handle positions
    is CadEntity.Arc -> {
        val handles = entityHandles(e)           // get current handle positions
        val pts = handles.map { it.pos }.toMutableList() // copy to mutable list
        pts[index] = newPos                      // replace the moved handle
        val startTip = pts[0]                    // arc start tip world position
        val endTip   = pts[1]                    // arc end tip world position
        val centre   = pts[2]                    // arc center world position
        val newRadius = centre.distTo(startTip)  // new radius = distance center→start
        // Recompute start and end angles from updated positions
        e.copy(center     = centre,
            radius     = newRadius,
            startAngle = atan2(startTip.y - centre.y, startTip.x - centre.x),
            endAngle   = atan2(endTip.y   - centre.y, endTip.x   - centre.x))
    }
    // Circle: move center or drag radius handle
    is CadEntity.Circle -> when (index) {
        0    -> e.copy(center = newPos)          // index 0 = center
        else -> e.copy(radius = e.center.distTo(newPos)) // index 1 = radius handle
    }
    // Ellipse: move center, drag major end, or drag minor end
    is CadEntity.Ellipse -> when (index) {
        0 -> e.copy(center = newPos)             // index 0 = center
        1 -> {
            // Major axis end moved: recompute rx and rotation angle
            val dx = newPos.x - e.center.x
            val dy = newPos.y - e.center.y
            val newRx  = sqrt((dx*dx + dy*dy).toDouble()).toFloat() // new semi-major
            val newRot = atan2(dy, dx)           // new rotation of major axis
            e.copy(rx = newRx, rotation = newRot)
        }
        else -> {
            // Minor axis end moved: recompute ry only
            val dx = newPos.x - e.center.x
            val dy = newPos.y - e.center.y
            val newRy = sqrt((dx*dx + dy*dy).toDouble()).toFloat()  // new semi-minor
            e.copy(ry = newRy)
        }
    }
    // Spline/Polyline: replace the specific control point at index
    is CadEntity.Spline   -> e.copy(points = e.points.toMutableList().also { it[index] = newPos })
    is CadEntity.Polyline -> e.copy(points = e.points.toMutableList().also { it[index] = newPos })
    // 3D solids are not editable via 2D handles
    is CadEntity.Solid3D  -> e
}

// ─────────────────────────────────────────────────────────────────────────────
// Extrusion: turns a closed 2D profile into a Mesh3D solid
// profile  — ordered list of 2D points forming a closed polygon (auto-closed)
// height   — extrusion distance along +Z
// ─────────────────────────────────────────────────────────────────────────────
object Extrude {

    fun extrude(profile: List<Vec2>, height: Float): Mesh3D {
        val verts   = mutableListOf<Vec3>()   // accumulated triangle vertices
        val normals = mutableListOf<Vec3>()   // accumulated per-vertex normals

        // ── Bottom cap (Z = 0, normal pointing down −Z) ───────────────────
        val bottomNormal = Vec3(0f, 0f, -1f)  // faces toward −Z
        triangulatePolygon(profile).forEach { tri ->
            // Each tri is three Vec2 indices; add at Z=0
            verts   += tri.toVec3()
            normals += bottomNormal
        }

        // ── Top cap (Z = height, normal pointing up +Z) ───────────────────
        val topNormal = Vec3(0f, 0f, 1f)      // faces toward +Z
        // Reverse winding for top cap so it faces outward (upward)
        triangulatePolygon(profile).reversed().forEach { tri ->
            verts   += Vec3(tri.x, tri.y, height) // lift each vertex to height
            normals += topNormal
        }

        // ── Side walls: one quad (two triangles) per edge of the profile ──
        val n = profile.size
        for (i in 0 until n) {
            val a2 = profile[i]                        // bottom-left of this quad
            val b2 = profile[(i + 1) % n]              // bottom-right (wraps around)
            val a0 = a2.toVec3()                       // bottom-left  at Z=0
            val b0 = b2.toVec3()                       // bottom-right at Z=0
            val a1 = Vec3(a2.x, a2.y, height)         // top-left  at Z=height
            val b1 = Vec3(b2.x, b2.y, height)         // top-right at Z=height

            // Outward face normal = cross product of two edge vectors of the quad
            val edge1  = b0 - a0                       // along the bottom edge
            val edge2  = a1 - a0                       // up the side
            val normal = edge1.cross(edge2).normalized() // perpendicular, normalised

            // First triangle of the quad: a0, b0, b1
            verts   += a0; normals += normal
            verts   += b0; normals += normal
            verts   += b1; normals += normal

            // Second triangle of the quad: a0, b1, a1
            verts   += a0; normals += normal
            verts   += b1; normals += normal
            verts   += a1; normals += normal
        }

        return Mesh3D(verts, normals)
    }

    // Simple fan triangulation of a convex or near-convex polygon.
    // Returns a flat list of Vec2 vertices (every 3 = one triangle).
    // Note: for concave polygons this may produce artefacts — acceptable for CAD profiles.
    private fun triangulatePolygon(pts: List<Vec2>): List<Vec2> {
        if (pts.size < 3) return emptyList()    // need at least a triangle
        val result = mutableListOf<Vec2>()
        val pivot = pts[0]                       // fan pivot = first vertex
        for (i in 1 until pts.size - 1) {
            result += pivot                      // all triangles share the pivot
            result += pts[i]                     // current vertex
            result += pts[i + 1]                 // next vertex
        }
        return result
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure geometry helpers (tessellation, arc math, etc.)
// ─────────────────────────────────────────────────────────────────────────────
object Geometry {

    // Catmull-Rom spline tessellation into a polyline
    // pts   — control points (interpolated through)
    // steps — number of line segments per span (higher = smoother)
    fun catmullRomPoints(pts: List<Vec2>, steps: Int = 20): List<Vec2> {
        if (pts.size < 2) return pts             // need at least 2 points to draw
        val result = mutableListOf<Vec2>()
        // Extend with phantom endpoints to handle tangent at first/last point
        val ext = listOf(pts.first()) + pts + listOf(pts.last())
        for (i in 1 until ext.size - 2) {
            val p0 = ext[i - 1]; val p1 = ext[i]
            val p2 = ext[i + 1]; val p3 = ext[i + 2]
            for (s in 0..steps) {
                val t  = s / steps.toFloat()     // parametric value 0→1 along this span
                val t2 = t * t                   // t squared
                val t3 = t2 * t                  // t cubed
                // Catmull-Rom formula (Barry & Goldman formulation)
                val x = 0.5f * ((2 * p1.x) +
                        (-p0.x + p2.x) * t +
                        (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
                        (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3)
                val y = 0.5f * ((2 * p1.y) +
                        (-p0.y + p2.y) * t +
                        (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
                        (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3)
                result.add(Vec2(x, y))
            }
        }
        return result
    }

    // Tessellates a circular arc into a polyline
    // steps — number of line segments (higher = smoother arc)
    fun arcPoints(center: Vec2, radius: Float, startAngle: Float, endAngle: Float, steps: Int = 60): List<Vec2> {
        val result = mutableListOf<Vec2>()
        var span = endAngle - startAngle         // angular extent of the arc
        if (span < 0) span += (2 * PI).toFloat() // ensure positive sweep (CCW)
        for (i in 0..steps) {
            val a = startAngle + span * i / steps // angle at this sample point
            result.add(Vec2(center.x + cos(a) * radius, center.y + sin(a) * radius))
        }
        return result
    }

    // Tessellates a full circle into a closed polyline
    fun circlePoints(center: Vec2, radius: Float, steps: Int = 72): List<Vec2> {
        return (0..steps).map { i ->
            val a = 2 * PI.toFloat() * i / steps // angle evenly distributed around full circle
            Vec2(center.x + cos(a) * radius, center.y + sin(a) * radius)
        }
    }

    // Tessellates an ellipse into a closed polyline
    // rotation — angle of the major axis relative to +X (radians)
    fun ellipsePoints(center: Vec2, rx: Float, ry: Float, rotation: Float, steps: Int = 72): List<Vec2> {
        return (0..steps).map { i ->
            val a  = 2 * PI.toFloat() * i / steps
            val lx = cos(a) * rx                // local X before rotation
            val ly = sin(a) * ry                // local Y before rotation
            // Rotate the local point by the ellipse orientation angle
            val rx2 = lx * cos(rotation) - ly * sin(rotation)
            val ry2 = lx * sin(rotation) + ly * cos(rotation)
            Vec2(center.x + rx2, center.y + ry2)
        }
    }

    // Computes the circumcircle center (= arc center) of three 2D points.
    // Returns null if the points are collinear (no unique circle).
    fun arcCenterFrom3Points(p1: Vec2, p2: Vec2, p3: Vec2): Pair<Vec2, Float>? {
        val ax = p1.x; val ay = p1.y
        val bx = p2.x; val by = p2.y
        val cx = p3.x; val cy = p3.y
        val d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (abs(d) < 1e-6f) return null          // points are collinear → no circle
        // Circumcircle center formula (derived from perpendicular bisectors)
        val ux = ((ax*ax + ay*ay) * (by-cy) + (bx*bx + by*by) * (cy-ay) + (cx*cx + cy*cy) * (ay-by)) / d
        val uy = ((ax*ax + ay*ay) * (cx-bx) + (bx*bx + by*by) * (ax-cx) + (cx*cx + cy*cy) * (bx-ax)) / d
        val center = Vec2(ux, uy)
        val radius = center.distTo(p1)           // any of the 3 points gives the same radius
        return Pair(center, radius)
    }
}
