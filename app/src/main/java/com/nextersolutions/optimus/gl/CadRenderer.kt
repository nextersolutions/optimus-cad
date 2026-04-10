package com.nextersolutions.optimus.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.nextersolutions.optimus.CadState
import com.nextersolutions.optimus.model.CadEntity
import com.nextersolutions.optimus.model.Geometry
import com.nextersolutions.optimus.model.Vec2
import com.nextersolutions.optimus.model.entityHandles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.floor

class CadRenderer : GLSurfaceView.Renderer {

    @Volatile
    var cadState: CadState = CadState()

    private var program = 0
    private val mvpMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private var viewWidth = 1
    private var viewHeight = 1

    private val vertexShader = """
        uniform mat4 uMVP;
        attribute vec4 aPosition;
        void main() { gl_Position = uMVP * aPosition; gl_PointSize = 14.0; }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        uniform vec4 uColor;
        void main() { gl_FragColor = uColor; }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.07f, 0.10f, 1f)
        GLES20.glLineWidth(1.5f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        program = buildProgram(vertexShader, fragmentShader)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = cadState
        buildMVP(s)
        GLES20.glUseProgram(program)

        if (s.showGrid) drawGrid(s)

        // X axis — red, Y axis — green
        drawLines(listOf(Vec2(-100000f, 0f), Vec2(100000f, 0f)), floatArrayOf(0.8f, 0.2f, 0.2f, 0.8f))
        drawLines(listOf(Vec2(0f, -100000f), Vec2(0f, 100000f)), floatArrayOf(0.2f, 0.8f, 0.2f, 0.8f))

        for (e in s.entities) drawEntity(e)
        s.previewEntity?.let { drawEntity(it) }
        for (p in s.inputPoints) drawPoints(listOf(p), floatArrayOf(1f, 1f, 0f, 1f))

        // Draw handles for the selected entity
        if (s.selectedEntityId != null) {
            s.entities.find { it.id == s.selectedEntityId }?.let { entity ->
                val handles = entityHandles(entity)
                for (h in handles) {
                    val isHovered = s.hoveredHandle?.let {
                        it.entityId == h.entityId && it.pointIndex == h.pointIndex
                    } == true
                    drawPoints(
                        listOf(h.pos),
                        if (isHovered) floatArrayOf(1f, 1f, 0f, 1f)
                        else           floatArrayOf(0f, 0.9f, 1f, 1f)
                    )
                }
            }
        }
    }

    private fun drawEntity(e: CadEntity) {
        when (e) {
            is CadEntity.Line     -> drawLines(listOf(e.start, e.end), e.color)
            is CadEntity.Arc      -> drawLineStrip(Geometry.arcPoints(e.center, e.radius, e.startAngle, e.endAngle), e.color)
            is CadEntity.Circle   -> drawLineStrip(Geometry.circlePoints(e.center, e.radius), e.color)
            is CadEntity.Ellipse  -> drawLineStrip(Geometry.ellipsePoints(e.center, e.rx, e.ry, e.rotation), e.color)
            is CadEntity.Spline   -> if (e.points.size >= 2) drawLineStrip(Geometry.catmullRomPoints(e.points), e.color)
            is CadEntity.Polyline -> {
                val pts = if (e.closed && e.points.isNotEmpty()) e.points + e.points.first() else e.points
                drawLineStrip(pts, e.color)
            }
        }
    }

    private fun drawGrid(s: CadState) {
        val g = s.gridSize
        // World-space half-extents of the viewport
        val halfW = (viewWidth  / 2f) / s.zoom
        val halfH = (viewHeight / 2f) / s.zoom
        // Visible world bounds (pan shifts the window)
        val left   = -halfW - s.panX
        val right  =  halfW - s.panX
        val bottom = -halfH - s.panY
        val top    =  halfH - s.panY

        val x0 = floor(left   / g) * g
        val x1 = ceil (right  / g) * g
        val y0 = floor(bottom / g) * g
        val y1 = ceil (top    / g) * g

        val gridColor = floatArrayOf(0.20f, 0.20f, 0.25f, 1f)
        var x = x0
        while (x <= x1 + g * 0.01f) {
            drawLines(listOf(Vec2(x, y0 - g), Vec2(x, y1 + g)), gridColor)
            x += g
        }
        var y = y0
        while (y <= y1 + g * 0.01f) {
            drawLines(listOf(Vec2(x0 - g, y), Vec2(x1 + g, y)), gridColor)
            y += g
        }
    }

    private fun drawLines(pts: List<Vec2>, color: FloatArray) {
        if (pts.size < 2) return
        val buf = pts.toFloatBuffer()
        val posHandle   = GLES20.glGetAttribLocation(program, "aPosition")
        val mvpHandle   = GLES20.glGetUniformLocation(program, "uMVP")
        val colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, pts.size)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    private fun drawLineStrip(pts: List<Vec2>, color: FloatArray) {
        if (pts.size < 2) return
        val buf = pts.toFloatBuffer()
        val posHandle   = GLES20.glGetAttribLocation(program, "aPosition")
        val mvpHandle   = GLES20.glGetUniformLocation(program, "uMVP")
        val colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, pts.size)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    private fun drawPoints(pts: List<Vec2>, color: FloatArray) {
        val buf = pts.toFloatBuffer()
        val posHandle   = GLES20.glGetAttribLocation(program, "aPosition")
        val mvpHandle   = GLES20.glGetUniformLocation(program, "uMVP")
        val colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pts.size)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    // Orthographic: 1 pixel = 1 world unit at zoom=1, origin at screen centre.
    private fun buildMVP(s: CadState) {
        val halfW = (viewWidth  / 2f) / s.zoom
        val halfH = (viewHeight / 2f) / s.zoom
        Matrix.orthoM(
            projMatrix, 0,
            -halfW - s.panX,  halfW - s.panX,
            -halfH - s.panY,  halfH - s.panY,
            -1f, 1f
        )
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvpMatrix, 0)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v)
            GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

    fun getViewDimensions() = Pair(viewWidth, viewHeight)
}

fun List<Vec2>.toFloatBuffer(): FloatBuffer {
    val buf = ByteBuffer.allocateDirect(size * 2 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    forEach { buf.put(it.x); buf.put(it.y) }
    buf.position(0)
    return buf
}
