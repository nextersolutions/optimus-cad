package com.nextersolutions.optimus.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.nextersolutions.optimus.CadState
import com.nextersolutions.optimus.model.CadEntity
import com.nextersolutions.optimus.model.Geometry
import com.nextersolutions.optimus.model.Vec2
import com.nextersolutions.optimus.model.Vec3
import com.nextersolutions.optimus.model.entityHandles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// OpenGL ES 2.0 renderer.
// Supports two modes:
//   2D — orthographic projection, flat XY grid, all sketch entities drawn
//   3D — perspective projection, orbit camera, solid meshes + wireframe sketch
// ─────────────────────────────────────────────────────────────────────────────
class CadRenderer : GLSurfaceView.Renderer {

    // Written from the main thread, read from the GL thread — @Volatile ensures visibility
    @Volatile
    var cadState: CadState = CadState()

    // ── GL handles ────────────────────────────────────────────────────────────
    private var program2D = 0    // shader program used for 2D flat drawing
    private var program3D = 0    // shader program used for 3D lit drawing
    private var viewWidth = 1    // current viewport width in pixels
    private var viewHeight = 1    // current viewport height in pixels

    // Pre-allocated matrix arrays (FloatArray avoids repeated heap allocation per frame)
    private val mvpMatrix = FloatArray(16)  // final model-view-projection matrix sent to shader
    private val projMatrix = FloatArray(16)  // projection matrix (ortho or perspective)
    private val viewMatrix = FloatArray(16)  // camera/view matrix (identity in 2D, orbit in 3D)
    private val modelMatrix =
        FloatArray(16)  // model transform (identity — all geometry in world space)
    private val normalMatrix = FloatArray(16) // 3×3 normal matrix for lighting (padded to 4×4)

    // ── Vertex shader (2D flat) ───────────────────────────────────────────────
    // Accepts 2D positions (x, y) packed as vec4 with z=0, w=1
    private val vertSrc2D = """
        uniform mat4 uMVP;          // combined model-view-projection matrix
        attribute vec4 aPosition;   // vertex position (x, y, 0, 1) in world space
        void main() {
            gl_Position  = uMVP * aPosition;  // transform to clip space
            gl_PointSize = 14.0;              // size of GL_POINTS in pixels
        }
    """.trimIndent()

    // ── Fragment shader (2D flat) ─────────────────────────────────────────────
    // Single uniform colour — no lighting
    private val fragSrc2D = """
        precision mediump float;
        uniform vec4 uColor;        // RGBA colour set per draw call
        void main() { gl_FragColor = uColor; }
    """.trimIndent()

    // ── Vertex shader (3D lit) ────────────────────────────────────────────────
    // Passes world-space normal to fragment shader for per-fragment lighting
    private val vertSrc3D = """
        uniform mat4 uMVP;          // model-view-projection
        uniform mat4 uModel;        // model matrix (world transform)
        attribute vec4 aPosition;   // vertex position in model space
        attribute vec3 aNormal;     // vertex normal in model space
        varying vec3 vNormal;       // interpolated normal passed to fragment shader
        varying vec3 vWorldPos;     // interpolated world position for lighting
        void main() {
            gl_Position = uMVP * aPosition;                          // clip space position
            vNormal     = normalize(mat3(uModel) * aNormal);         // transform normal to world space
            vWorldPos   = vec3(uModel * aPosition);                  // world space position
        }
    """.trimIndent()

    // ── Fragment shader (3D lit) ──────────────────────────────────────────────
    // Simple diffuse + ambient Lambertian shading from a fixed directional light
    private val fragSrc3D = """
        precision mediump float;
        uniform vec4 uColor;               // base colour of the solid
        varying vec3 vNormal;              // interpolated surface normal
        varying vec3 vWorldPos;            // world-space fragment position (unused but available)
        void main() {
            vec3 lightDir   = normalize(vec3(0.6, 0.8, 1.0));        // fixed directional light
            float diffuse   = max(dot(normalize(vNormal), lightDir), 0.0); // Lambertian term
            float ambient   = 0.35;                                   // minimum brightness in shadow
            float intensity = ambient + (1.0 - ambient) * diffuse;   // combine ambient + diffuse
            gl_FragColor    = vec4(uColor.rgb * intensity, uColor.a); // apply intensity to colour
        }
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.07f, 0.10f, 1f)  // dark navy background
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)           // enable depth testing for correct 3D overlap
        GLES20.glEnable(GLES20.GL_BLEND)                // enable alpha blending
        GLES20.glBlendFunc(
            GLES20.GL_SRC_ALPHA,
            GLES20.GL_ONE_MINUS_SRC_ALPHA
        ) // standard alpha blend
        GLES20.glLineWidth(1.5f)                         // line width for 2D entities
        program2D = buildProgram(vertSrc2D, fragSrc2D)  // compile and link 2D shader
        program3D = buildProgram(vertSrc3D, fragSrc3D)  // compile and link 3D shader
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height) // update viewport to full surface size
        viewWidth = width
        viewHeight = height
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main render function — called once per frame by the GL thread
    // ─────────────────────────────────────────────────────────────────────────
    override fun onDrawFrame(gl: GL10?) {
        // Clear colour and depth buffers at the start of each frame
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = cadState                               // snapshot state (written from other thread)

        if (s.is3DMode) drawScene3D(s) else drawScene2D(s)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2D scene — orthographic projection, flat XY grid, all 2D entities
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawScene2D(s: CadState) {
        GLES20.glUseProgram(program2D)
        buildMVP2D(s)

        // Everything in 2D is at Z=0, so depth testing causes random draw-order
        // wins between grid, axes, entities and handles. Disable it entirely for 2D —
        // we control draw order manually (grid → axes → entities → handles).
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        if (s.showGrid) drawGrid(s)

        drawLines2D(
            listOf(Vec2(-100000f, 0f), Vec2(100000f, 0f)),
            floatArrayOf(0.9f, 0.2f, 0.2f, 1f)
        )  // X red
        drawLines2D(
            listOf(Vec2(0f, -100000f), Vec2(0f, 100000f)),
            floatArrayOf(0.2f, 0.9f, 0.2f, 1f)
        )  // Y green

        for (e in s.entities) if (e !is CadEntity.Solid3D) draw2DEntity(e)
        s.previewEntity?.let { if (it !is CadEntity.Solid3D) draw2DEntity(it) }
        for (p in s.inputPoints) drawPoints2D(listOf(p), floatArrayOf(1f, 1f, 0f, 1f))
        drawHandles2D(s)

        // Re-enable depth testing before returning so 3D mode still works
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3D scene — perspective projection, orbit camera, solid meshes + sketch overlay
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawScene3D(s: CadState) {
        // ── Build perspective + orbit camera matrices ─────────────────────
        val aspect = viewWidth.toFloat() / viewHeight.toFloat() // screen aspect ratio
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 1f, 20000f) // 45° FOV perspective

        // Convert orbit angles (degrees) to radians for trig
        val yawRad = Math.toRadians(s.orbitYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(s.orbitPitch.toDouble()).toFloat()

        // Convert spherical (yaw, pitch, distance) to Cartesian camera position
        val camX = s.orbitDist * cos(pitchRad) * sin(yawRad)  // camera X in world space
        val camY = s.orbitDist * cos(pitchRad) * cos(yawRad)  // camera Y in world space
        val camZ = s.orbitDist * sin(pitchRad)                 // camera Z (elevation)

        // Build a look-at view matrix: camera at (camX,camY,camZ), looking at origin, up=+Z
        Matrix.setLookAtM(
            viewMatrix, 0,
            camX, camY, camZ,    // eye position
            0f, 0f, 0f,          // look-at target (world origin)
            0f, 0f, 1f           // up vector (+Z is up in CAD convention)
        )

        // Model matrix is identity (all geometry already in world space)
        Matrix.setIdentityM(modelMatrix, 0)

        // MVP = Projection × View × Model
        val tempMat = FloatArray(16)
        Matrix.multiplyMM(tempMat, 0, projMatrix, 0, viewMatrix, 0) // P × V
        Matrix.multiplyMM(mvpMatrix, 0, tempMat, 0, modelMatrix, 0) // (P×V) × M

        // ── Draw 3D world axes (depth test off so they always show over the grid) ─
        GLES20.glUseProgram(program2D)
        setMVP2D()
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        drawLines3D(
            listOf(Vec3(0f, 0f, 0f), Vec3(500f, 0f, 0f)),
            floatArrayOf(0.8f, 0.2f, 0.2f, 0.9f)
        ) // X red
        drawLines3D(
            listOf(Vec3(0f, 0f, 0f), Vec3(0f, 500f, 0f)),
            floatArrayOf(0.2f, 0.8f, 0.2f, 0.9f)
        ) // Y green
        drawLines3D(
            listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 500f)),
            floatArrayOf(0.4f, 0.4f, 1.0f, 0.9f)
        ) // Z blue
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // ── Draw 3D grid on XY plane ──────────────────────────────────────
        if (s.showGrid) drawGrid3D(s)

        // ── Draw 3D solids with lighting ──────────────────────────────────
        GLES20.glUseProgram(program3D)                  // switch to lit shader
        for (e in s.entities) {
            if (e is CadEntity.Solid3D) drawSolid3D(e, s) // draw each solid
        }

        // ── Draw 2D sketch entities as wireframe overlay ──────────────────
        GLES20.glUseProgram(program2D)                  // back to flat shader
        setMVP2D()                                       // reuse the same 3D MVP (sketch is at Z=0)
        for (e in s.entities) {
            if (e !is CadEntity.Solid3D) draw2DEntityAs3D(e) // project 2D entity onto Z=0 plane
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Draw a Solid3D entity using the lit 3D shader
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawSolid3D(solid: CadEntity.Solid3D, s: CadState) {
        val mesh = solid.mesh
        if (mesh.vertices.isEmpty()) return               // nothing to draw

        // Build a flat Float array: positions (x,y,z) interleaved for GPU upload
        val posBuf = mesh.vertices.toVec3FloatBuffer()  // vertex position buffer
        val normBuf = mesh.normals.toVec3FloatBuffer()   // vertex normal buffer

        // Retrieve shader attribute/uniform locations
        val posHandle = GLES20.glGetAttribLocation(program3D, "aPosition") // vertex position attrib
        val normHandle = GLES20.glGetAttribLocation(program3D, "aNormal")   // vertex normal attrib
        val mvpHandle = GLES20.glGetUniformLocation(program3D, "uMVP")      // MVP matrix uniform
        val modelHandle =
            GLES20.glGetUniformLocation(program3D, "uModel")    // model matrix uniform
        val colorHandle = GLES20.glGetUniformLocation(program3D, "uColor")    // colour uniform

        val isSelected = solid.id == s.selectedEntityId   // highlight if selected

        // Upload matrices and colour to the GPU
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0) // upload MVP
        GLES20.glUniformMatrix4fv(modelHandle, 1, false, modelMatrix, 0) // upload Model
        GLES20.glUniform4fv(
            colorHandle, 1,
            if (isSelected) floatArrayOf(0.5f, 0.8f, 1f, 1f)            // bright blue when selected
            else solid.color, 0
        )                              // normal colour otherwise

        // Bind position attribute
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(
            posHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            0,
            posBuf
        ) // stride=0 tightly packed

        // Bind normal attribute
        GLES20.glEnableVertexAttribArray(normHandle)
        GLES20.glVertexAttribPointer(normHandle, 3, GLES20.GL_FLOAT, false, 0, normBuf)

        // Draw all triangles
        GLES20.glDrawArrays(
            GLES20.GL_TRIANGLES,
            0,
            mesh.vertices.size
        ) // one call draws the whole mesh

        // Unbind attributes to avoid leaking state
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(normHandle)

        // ── Wireframe edges (drawn slightly darker on top of the filled face) ──
        GLES20.glUseProgram(program2D)                    // switch to flat shader for edges
        setMVP2D()                                        // reuse current MVP
        val edgeColor = floatArrayOf(0f, 0.5f, 0.8f, 0.6f) // semi-transparent blue edge
        // Draw the profile loop at Z=0 and at Z=height as wireframe
        val profile3d = solid.profilePoints.map { Vec3(it.x, it.y, 0f) }         // bottom loop
        val profileTop = solid.profilePoints.map { Vec3(it.x, it.y, solid.height) } // top loop
        drawLineLoop3D(profile3d, edgeColor)               // bottom perimeter
        drawLineLoop3D(profileTop, edgeColor)              // top perimeter
        // Draw vertical edges connecting bottom to top at each profile vertex
        for (p in solid.profilePoints) {
            drawLines3D(listOf(Vec3(p.x, p.y, 0f), Vec3(p.x, p.y, solid.height)), edgeColor)
        }
        GLES20.glUseProgram(program3D)                    // restore lit shader for subsequent solids
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Draw a 2D entity (all coordinates at Z=0) using the current 3D MVP
    // Used in 3D view to show the sketch plane as a wireframe overlay
    // ─────────────────────────────────────────────────────────────────────────
    private fun draw2DEntityAs3D(e: CadEntity) {
        val color = e.color
        when (e) {
            is CadEntity.Line -> drawLines3D(listOf(e.start.toVec3(), e.end.toVec3()), color)
            is CadEntity.Arc -> drawLineStrip3D(
                Geometry.arcPoints(
                    e.center,
                    e.radius,
                    e.startAngle,
                    e.endAngle
                ).map { it.toVec3() }, color
            )

            is CadEntity.Circle -> drawLineStrip3D(
                Geometry.circlePoints(e.center, e.radius).map { it.toVec3() }, color
            )

            is CadEntity.Ellipse -> drawLineStrip3D(
                Geometry.ellipsePoints(
                    e.center,
                    e.rx,
                    e.ry,
                    e.rotation
                ).map { it.toVec3() }, color
            )

            is CadEntity.Spline -> if (e.points.size >= 2) drawLineStrip3D(
                Geometry.catmullRomPoints(
                    e.points
                ).map { it.toVec3() }, color
            )

            is CadEntity.Polyline -> {
                val pts =
                    if (e.closed && e.points.isNotEmpty()) e.points + e.points.first() else e.points
                drawLineStrip3D(pts.map { it.toVec3() }, color)
            }

            else -> {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2D entity drawing helpers (use program2D, 2D Vec2 coordinates)
    // ─────────────────────────────────────────────────────────────────────────

    private fun draw2DEntity(e: CadEntity) {
        when (e) {
            is CadEntity.Line -> drawLines2D(listOf(e.start, e.end), e.color)
            is CadEntity.Arc -> drawLineStrip2D(
                Geometry.arcPoints(
                    e.center,
                    e.radius,
                    e.startAngle,
                    e.endAngle
                ), e.color
            )

            is CadEntity.Circle -> drawLineStrip2D(
                Geometry.circlePoints(e.center, e.radius),
                e.color
            )

            is CadEntity.Ellipse -> drawLineStrip2D(
                Geometry.ellipsePoints(
                    e.center,
                    e.rx,
                    e.ry,
                    e.rotation
                ), e.color
            )

            is CadEntity.Spline -> if (e.points.size >= 2) drawLineStrip2D(
                Geometry.catmullRomPoints(
                    e.points
                ), e.color
            )

            is CadEntity.Polyline -> {
                val pts =
                    if (e.closed && e.points.isNotEmpty()) e.points + e.points.first() else e.points
                drawLineStrip2D(pts, e.color)
            }

            else -> {}
        }
    }

    // Draws vertex handle dots for the selected entity in 2D mode
    private fun drawHandles2D(s: CadState) {
        if (s.selectedEntityId == null) return           // nothing selected
        val entity = s.entities.find { it.id == s.selectedEntityId } ?: return
        val handles = entityHandles(entity)              // get all editable handles

        for (h in handles) {
            val isHovered = s.hoveredHandle?.let {
                it.entityId == h.entityId && it.pointIndex == h.pointIndex
            } == true                                    // true if this handle is the active one

            drawPoints2D(
                listOf(h.pos),
                if (isHovered) floatArrayOf(1f, 1f, 0f, 1f)     // yellow when hovered/active
                else floatArrayOf(0f, 0.9f, 1f, 1f)
            )  // cyan otherwise
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Grid drawing
    // ─────────────────────────────────────────────────────────────────────────

    // 2D grid on XY plane — lines extend exactly to the viewport edges
    private fun drawGrid(s: CadState) {
        val g = s.gridSize
        val halfW = viewWidth / 2f / s.zoom             // half-width in world units
        val halfH = viewHeight / 2f / s.zoom             // half-height in world units
        // Compute world-space bounds of the visible area, accounting for pan
        val left = -halfW - s.panX
        val right = halfW - s.panX
        val bottom = -halfH - s.panY
        val top = halfH - s.panY
        // Round out to the nearest grid line outside the viewport
        val x0 = floor(left / g) * g
        val x1 = ceil(right / g) * g
        val y0 = floor(bottom / g) * g
        val y1 = ceil(top / g) * g
        val c = floatArrayOf(0.20f, 0.20f, 0.25f, 1f)  // dark grid colour

        var x = x0
        while (x <= x1 + g * 0.01f) {
            drawLines2D(listOf(Vec2(x, y0 - g), Vec2(x, y1 + g)), c) // vertical line
            x += g
        }
        var y = y0
        while (y <= y1 + g * 0.01f) {
            drawLines2D(listOf(Vec2(x0 - g, y), Vec2(x1 + g, y)), c) // horizontal line
            y += g
        }
    }

    // 3D grid on the XY plane — extent scales with camera distance so it always fills the floor
    private fun drawGrid3D(s: CadState) {
        // Scale grid cell size with distance so density stays comfortable at any zoom level
        val g = s.gridSize * max(1f, s.orbitDist / 400f)
        // Extend grid far enough to reach the horizon at the current camera elevation.
        // At low pitch angles the horizon is very far, so we use a large multiplier.
        val span = (s.orbitDist * 3f / g).toInt().coerceIn(20, 300)
        val half = span * g                               // half-extent in world units
        val c = floatArrayOf(0.18f, 0.18f, 0.22f, 0.8f)

        for (i in -span..span) {
            val t = i * g                                 // position of this grid line
            drawLines3D(listOf(Vec3(t, -half, 0f), Vec3(t, half, 0f)), c)   // parallel to Y
            drawLines3D(listOf(Vec3(-half, t, 0f), Vec3(half, t, 0f)), c)   // parallel to X
        }
    }

// ─────────────────────────────────────────────────────────────────────────
// Low-level draw calls for 2D geometry (Vec2, Z=0 implied, program2D active)
// ─────────────────────────────────────────────────────────────────────────

    // Draws disconnected line segments: pts[0]→pts[1], pts[2]→pts[3], …
    private fun drawLines2D(pts: List<Vec2>, color: FloatArray) {
        if (pts.size < 2) return
        val buf = pts.toFloatBuffer()            // pack Vec2 list into native float buffer
        val posHandle = GLES20.glGetAttribLocation(program2D, "aPosition")
        val mvpHandle = GLES20.glGetUniformLocation(program2D, "uMVP")
        val colorHandle = GLES20.glGetUniformLocation(program2D, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)  // upload MVP
        GLES20.glUniform4fv(colorHandle, 1, color, 0)                   // upload colour
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(
            posHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            buf
        ) // 2 floats per vertex
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, pts.size)               // draw pairs as lines
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    // Draws a connected strip of line segments: pts[0]→pts[1]→pts[2]→…
    private fun drawLineStrip2D(pts: List<Vec2>, color: FloatArray) {
        if (pts.size < 2) return
        val buf = pts.toFloatBuffer()
        val posHandle = GLES20.glGetAttribLocation(program2D, "aPosition")
        val mvpHandle = GLES20.glGetUniformLocation(program2D, "uMVP")
        val colorHandle = GLES20.glGetUniformLocation(program2D, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, pts.size)          // connected strip
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    // Draws square point sprites at the given world positions
    private fun drawPoints2D(pts: List<Vec2>, color: FloatArray) {
        if (pts.isEmpty()) return
        val buf = pts.toFloatBuffer()
        val posHandle = GLES20.glGetAttribLocation(program2D, "aPosition")
        val mvpHandle = GLES20.glGetUniformLocation(program2D, "uMVP")
        val colorHandle = GLES20.glGetUniformLocation(program2D, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pts.size)              // one square per point
        GLES20.glDisableVertexAttribArray(posHandle)
    }

// ─────────────────────────────────────────────────────────────────────────
// Low-level draw calls for 3D geometry (Vec3, program2D active, uses 3D MVP)
// ─────────────────────────────────────────────────────────────────────────

    private fun drawLines3D(pts: List<Vec3>, color: FloatArray) {
        if (pts.size < 2) return
        val buf = pts.toVec3FloatBuffer()        // pack Vec3 list into native float buffer
        val posHandle = GLES20.glGetAttribLocation(program2D, "aPosition")
        val mvpHandle = GLES20.glGetUniformLocation(program2D, "uMVP")
        val colorHandle = GLES20.glGetUniformLocation(program2D, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(
            posHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            0,
            buf
        ) // 3 floats per vertex
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, pts.size)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    private fun drawLineStrip3D(pts: List<Vec3>, color: FloatArray) {
        if (pts.size < 2) return
        val buf = pts.toVec3FloatBuffer()
        val posHandle = GLES20.glGetAttribLocation(program2D, "aPosition")
        val mvpHandle = GLES20.glGetUniformLocation(program2D, "uMVP")
        val colorHandle = GLES20.glGetUniformLocation(program2D, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, pts.size)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    // Draws a closed polygon loop (last vertex connects back to first)
    private fun drawLineLoop3D(pts: List<Vec3>, color: FloatArray) {
        if (pts.size < 2) return
        val buf = pts.toVec3FloatBuffer()
        val posHandle = GLES20.glGetAttribLocation(program2D, "aPosition")
        val mvpHandle = GLES20.glGetUniformLocation(program2D, "uMVP")
        val colorHandle = GLES20.glGetUniformLocation(program2D, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, pts.size)           // last→first automatic
        GLES20.glDisableVertexAttribArray(posHandle)
    }

// ─────────────────────────────────────────────────────────────────────────
// Matrix helpers
// ─────────────────────────────────────────────────────────────────────────

    // Builds a 2D orthographic MVP and stores it in mvpMatrix
    private fun buildMVP2D(s: CadState) {
        val halfW = viewWidth / 2f / s.zoom   // half viewport width in world units
        val halfH = viewHeight / 2f / s.zoom   // half viewport height in world units
        // Orthographic projection: maps world coords directly to clip space
        Matrix.orthoM(
            projMatrix, 0,
            -halfW - s.panX, halfW - s.panX,  // left, right
            -halfH - s.panY, halfH - s.panY,  // bottom, top
            -1f, 1f
        )                             // near, far (flat so tiny range is fine)
        Matrix.setIdentityM(mvpMatrix, 0)        // start with identity
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvpMatrix, 0) // MVP = Proj × Identity
    }

    // Uploads the current mvpMatrix to the active flat shader (program2D must be current)
    private fun setMVP2D() {
        val mvpHandle = GLES20.glGetUniformLocation(program2D, "uMVP")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0) // upload current mvpMatrix
    }

// ─────────────────────────────────────────────────────────────────────────
// Shader compilation and linking
// ─────────────────────────────────────────────────────────────────────────

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs) // compile vertex shader
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs) // compile fragment shader
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v)   // attach vertex shader to program
            GLES20.glAttachShader(it, f)   // attach fragment shader to program
            GLES20.glLinkProgram(it)       // link: resolves attribute/uniform locations
        }
    }

    private fun compileShader(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)   // upload GLSL source code
            GLES20.glCompileShader(it)       // compile to GPU bytecode
        }

    // Returns the current viewport dimensions (used by ViewModel for coordinate conversion)
    fun getViewDimensions() = Pair(viewWidth, viewHeight)
}

// ─────────────────────────────────────────────────────────────────────────────
// Extension functions: pack Kotlin lists into native byte buffers for OpenGL
// ─────────────────────────────────────────────────────────────────────────────

// Packs a List<Vec2> into a FloatBuffer (2 floats per vertex: x, y)
fun List<Vec2>.toFloatBuffer(): FloatBuffer {
    val buf = ByteBuffer.allocateDirect(size * 2 * 4)  // 2 floats × 4 bytes each
        .order(ByteOrder.nativeOrder())                 // must match GPU byte order
        .asFloatBuffer()
    forEach { buf.put(it.x); buf.put(it.y) }           // interleave x, y
    buf.position(0)                                     // rewind to start before passing to GL
    return buf
}

// Packs a List<Vec3> into a FloatBuffer (3 floats per vertex: x, y, z)
fun List<Vec3>.toVec3FloatBuffer(): FloatBuffer {
    val buf = ByteBuffer.allocateDirect(size * 3 * 4)  // 3 floats × 4 bytes each
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    forEach { buf.put(it.x); buf.put(it.y); buf.put(it.z) } // interleave x, y, z
    buf.position(0)
    return buf
}
