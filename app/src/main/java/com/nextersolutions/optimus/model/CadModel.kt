package com.nextersolutions.optimus.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ── Point ────────────────────────────────────────────────────────────────────
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun distTo(o: Vec2) = sqrt((x - o.x).pow(2) + (y - o.y).pow(2))
    fun length() = sqrt(x * x + y * y)
    fun normalized(): Vec2 {
        val l = length(); return if (l == 0f) Vec2(0f, 0f) else Vec2(x / l, y / l)
    }
}

// ── Drawing tools ─────────────────────────────────────────────────────────────
enum class DrawTool {
    SELECT, LINE, ARC, CIRCLE, ELLIPSE, SPLINE, POLYLINE
}

// ── CAD Entities ─────────────────────────────────────────────────────────────
sealed class CadEntity(open val id: Long, open val color: FloatArray) {

    data class Line(
        override val id: Long,
        val start: Vec2,
        val end: Vec2,
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    /** 3-point arc: start, mid-point on arc, end */
    data class Arc(
        override val id: Long,
        val center: Vec2,
        val radius: Float,
        val startAngle: Float,   // radians
        val endAngle: Float,     // radians, CCW
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    data class Circle(
        override val id: Long,
        val center: Vec2,
        val radius: Float,
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    data class Ellipse(
        override val id: Long,
        val center: Vec2,
        val rx: Float,
        val ry: Float,
        val rotation: Float = 0f,  // radians
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    /** Catmull-Rom spline through control points */
    data class Spline(
        override val id: Long,
        val points: List<Vec2>,
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)

    data class Polyline(
        override val id: Long,
        val points: List<Vec2>,
        val closed: Boolean = false,
        override val color: FloatArray = floatArrayOf(0.2f, 0.8f, 1f, 1f)
    ) : CadEntity(id, color)
}

// ── Edit handle: one draggable/editable vertex on a committed entity ──────────
data class EditHandle(
    val entityId: Long,
    val pointIndex: Int,   // meaning depends on entity type — see entityHandles()
    val pos: Vec2,
    val label: String      // e.g. "Start", "P2", "Center"
)

/** Extract all editable handles from an entity. */
fun entityHandles(e: CadEntity): List<EditHandle> = when (e) {
    is CadEntity.Line -> listOf(
        EditHandle(e.id, 0, e.start, "Start"),
        EditHandle(e.id, 1, e.end,   "End")
    )
    is CadEntity.Arc -> listOf(
        // index 0 = startAngle tip, 1 = endAngle tip, 2 = center
        EditHandle(e.id, 0, Vec2(e.center.x + kotlin.math.cos(e.startAngle) * e.radius,
            e.center.y + kotlin.math.sin(e.startAngle) * e.radius), "Start"),
        EditHandle(e.id, 1, Vec2(e.center.x + kotlin.math.cos(e.endAngle)   * e.radius,
            e.center.y + kotlin.math.sin(e.endAngle)   * e.radius), "End"),
        EditHandle(e.id, 2, e.center, "Center")
    )
    is CadEntity.Circle -> listOf(
        EditHandle(e.id, 0, e.center, "Center"),
        // radius handle: point directly to the right
        EditHandle(e.id, 1, Vec2(e.center.x + e.radius, e.center.y), "Radius")
    )
    is CadEntity.Ellipse -> listOf(
        EditHandle(e.id, 0, e.center, "Center"),
        EditHandle(e.id, 1, Vec2(
            e.center.x + kotlin.math.cos(e.rotation) * e.rx,
            e.center.y + kotlin.math.sin(e.rotation) * e.rx), "MajorEnd"),
        EditHandle(e.id, 2, Vec2(
            e.center.x - kotlin.math.sin(e.rotation) * e.ry,
            e.center.y + kotlin.math.cos(e.rotation) * e.ry), "MinorEnd")
    )
    is CadEntity.Spline   -> e.points.mapIndexed   { i, p -> EditHandle(e.id, i, p, "P${i+1}") }
    is CadEntity.Polyline -> e.points.mapIndexed   { i, p -> EditHandle(e.id, i, p, "P${i+1}") }
}

/** Apply a moved handle back onto the entity, returning the updated entity. */
fun applyHandle(e: CadEntity, index: Int, newPos: Vec2): CadEntity = when (e) {
    is CadEntity.Line -> when (index) {
        0 -> e.copy(start = newPos)
        else -> e.copy(end = newPos)
    }
    is CadEntity.Arc -> {
        // Rebuild arc from the 3 defining points (start tip, end tip, centre)
        val handles = entityHandles(e)
        val pts = handles.map { it.pos }.toMutableList()
        pts[index] = newPos
        val startTip = pts[0]; val endTip = pts[1]; val centre = pts[2]
        val newRadius = centre.distTo(startTip)
        e.copy(center     = centre,
            radius     = newRadius,
            startAngle = kotlin.math.atan2(startTip.y - centre.y, startTip.x - centre.x),
            endAngle   = kotlin.math.atan2(endTip.y   - centre.y, endTip.x   - centre.x))
    }
    is CadEntity.Circle -> when (index) {
        0 -> e.copy(center = newPos)
        else -> e.copy(radius = e.center.distTo(newPos))
    }
    is CadEntity.Ellipse -> {
        when (index) {
            0 -> e.copy(center = newPos)
            1 -> {
                val dx = newPos.x - e.center.x; val dy = newPos.y - e.center.y
                val newRx  = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val newRot = kotlin.math.atan2(dy, dx)
                e.copy(rx = newRx, rotation = newRot)
            }
            else -> {
                val dx = newPos.x - e.center.x; val dy = newPos.y - e.center.y
                val newRy = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                e.copy(ry = newRy)
            }
        }
    }
    is CadEntity.Spline   -> e.copy(points = e.points.toMutableList().also { it[index] = newPos })
    is CadEntity.Polyline -> e.copy(points = e.points.toMutableList().also { it[index] = newPos })
}

// ── Geometry helpers ─────────────────────────────────────────────────────────
object Geometry {

    /** Catmull-Rom spline tessellation */
    fun catmullRomPoints(pts: List<Vec2>, steps: Int = 20): List<Vec2> {
        if (pts.size < 2) return pts
        val result = mutableListOf<Vec2>()
        val ext = listOf(pts.first()) + pts + listOf(pts.last())
        for (i in 1 until ext.size - 2) {
            val p0 = ext[i - 1]; val p1 = ext[i]
            val p2 = ext[i + 1]; val p3 = ext[i + 2]
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                val t2 = t * t; val t3 = t2 * t
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

    /** Arc tessellation */
    fun arcPoints(center: Vec2, radius: Float, startAngle: Float, endAngle: Float, steps: Int = 60): List<Vec2> {
        val result = mutableListOf<Vec2>()
        var span = endAngle - startAngle
        if (span < 0) span += (2 * PI).toFloat()
        for (i in 0..steps) {
            val a = startAngle + span * i / steps
            result.add(Vec2(center.x + cos(a) * radius, center.y + sin(a) * radius))
        }
        return result
    }

    /** Circle tessellation */
    fun circlePoints(center: Vec2, radius: Float, steps: Int = 72): List<Vec2> {
        return (0..steps).map { i ->
            val a = 2 * PI.toFloat() * i / steps
            Vec2(center.x + cos(a) * radius, center.y + sin(a) * radius)
        }
    }

    /** Ellipse tessellation */
    fun ellipsePoints(center: Vec2, rx: Float, ry: Float, rotation: Float, steps: Int = 72): List<Vec2> {
        return (0..steps).map { i ->
            val a = 2 * PI.toFloat() * i / steps
            val lx = cos(a) * rx; val ly = sin(a) * ry
            val rx2 = lx * cos(rotation) - ly * sin(rotation)
            val ry2 = lx * sin(rotation) + ly * cos(rotation)
            Vec2(center.x + rx2, center.y + ry2)
        }
    }

    /** Compute arc center from 3 points */
    fun arcCenterFrom3Points(p1: Vec2, p2: Vec2, p3: Vec2): Pair<Vec2, Float>? {
        val ax = p1.x; val ay = p1.y
        val bx = p2.x; val by = p2.y
        val cx = p3.x; val cy = p3.y
        val d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (abs(d) < 1e-6f) return null
        val ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
        val uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
        val center = Vec2(ux, uy)
        val radius = center.distTo(p1)
        return Pair(center, radius)
    }
}
