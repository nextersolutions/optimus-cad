package com.nextersolutions.optimus.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nextersolutions.optimus.CadViewModel
import com.nextersolutions.optimus.model.DrawTool
import com.nextersolutions.optimus.model.EditHandle
import com.nextersolutions.optimus.model.Vec2
import kotlin.math.cos
import kotlin.math.sin

// ── Colour tokens ─────────────────────────────────────────────────────────────
private val BgDark        = Color(0xFF0D0D12)  // very dark background behind the GL view
private val Surface1      = Color(0xFF161620)  // tool palette background
private val Surface2      = Color(0xFF1E1E2E)  // top bar and dialog chip backgrounds
private val Surface3      = Color(0xFF252535)  // tooltip / dialog field backgrounds
private val Accent        = Color(0xFF3DE8FF)  // primary highlight (cyan)
private val AccentDim     = Color(0xFF1A6875)  // dimmed accent for active tool background
private val Accent3D      = Color(0xFFFFAA33)  // orange accent for 3D mode indicator
private val TextPrimary   = Color(0xFFE0E0F0)  // main text colour
private val TextSecondary = Color(0xFF7070A0)  // subdued text colour
private val ErrorColor    = Color(0xFFFF6060)  // red for destructive actions

// ─────────────────────────────────────────────────────────────────────────────
// Root composable — the entire app UI lives here.
// glView is the AndroidView wrapping the GLSurfaceView, passed in as a slot.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CadMainUI(
    viewModel: CadViewModel,            // the single source of truth
    glView: @Composable () -> Unit      // the OpenGL canvas slot
) {
    val state by viewModel.state.collectAsState() // observe state as Compose State

    Box(Modifier.fillMaxSize().background(BgDark)) { // root container fills the screen

        // ── OpenGL canvas (full screen, behind all overlays) ───────────────
        glView()

        // ── Top bar ────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()              // avoid system status bar
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // App title — turns orange in 3D mode to indicate the mode visually
            Text(
                if (state.is3DMode) "CAD · 3D" else "CAD · 2D",
                color      = if (state.is3DMode) Accent3D else Accent,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Grid toggle button
                TopBarButton(
                    icon  = if (state.showGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                    tint  = if (state.showGrid) Accent else TextSecondary,
                    onClick = { viewModel.toggleGrid() }
                )
                // Snap-to-grid toggle button
                TopBarButton(
                    icon  = Icons.Default.Straighten,
                    tint  = if (state.snapToGrid) Accent else TextSecondary,
                    onClick = { viewModel.toggleSnap() }
                )
                // Undo button
                TopBarButton(icon = Icons.Default.Undo,        tint = TextPrimary, onClick = { viewModel.undo() })
                // Clear all button (red to signal danger)
                TopBarButton(icon = Icons.Default.DeleteSweep, tint = ErrorColor,  onClick = { viewModel.clearAll() })

                // ── 2D / 3D toggle button ──────────────────────────────────
                // Displays "3D" or "2D" label; highlighted in orange in 3D mode
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (state.is3DMode) Color(0xFF3D2200) else Surface2) // tinted bg in 3D
                        .border(1.dp,
                            if (state.is3DMode) Accent3D else Color.Transparent,
                            RoundedCornerShape(8.dp))
                        .clickable { viewModel.toggle3D() }          // toggle 2D ↔ 3D
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (state.is3DMode) "2D" else "3D",          // label shows what you'll switch TO
                        color      = if (state.is3DMode) Accent3D else TextSecondary,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // ── Status bar (top centre) ────────────────────────────────────────
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Surface2.copy(alpha = 0.9f))
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(state.statusText,
                color      = TextSecondary,
                fontSize   = 12.sp,
                fontFamily = FontFamily.Monospace)
        }

        // ── Vertex tooltip (bottom centre, shown when a handle is tapped) ──
        state.hoveredHandle?.let { h ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface3.copy(alpha = 0.95f))
                    .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment    = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(h.label,   // handle name e.g. "Start", "P3"
                        color = Accent, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("X: ${"%.3f".format(h.pos.x)}  Y: ${"%.3f".format(h.pos.y)}",
                        color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("double-tap to edit",
                        color = TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── Tool palette (left side vertical strip) ────────────────────────
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface1.copy(alpha = 0.92f))
                .padding(vertical = 8.dp, horizontal = 6.dp),
            verticalArrangement   = Arrangement.spacedBy(4.dp),
            horizontalAlignment   = Alignment.CenterHorizontally
        ) {
            // SELECT tool
            ToolButton(DrawTool.SELECT,   Icons.Default.NearMe,              "Sel",   state.selectedTool, viewModel)
            HorizontalDivider(color = Surface2, thickness = 1.dp, modifier = Modifier.width(36.dp))
            // 2D sketch tools
            ToolButton(DrawTool.LINE,     Icons.Default.ShowChart,           "Line",  state.selectedTool, viewModel)
            ToolButton(DrawTool.ARC,      Icons.Default.Architecture,        "Arc",   state.selectedTool, viewModel)
            ToolButton(DrawTool.CIRCLE,   Icons.Default.RadioButtonUnchecked,"Circ",  state.selectedTool, viewModel)
            ToolButton(DrawTool.ELLIPSE,  Icons.Default.Adjust,              "Ellip", state.selectedTool, viewModel)
            ToolButton(DrawTool.SPLINE,   Icons.Default.Timeline,            "Spln",  state.selectedTool, viewModel)
            ToolButton(DrawTool.POLYLINE, Icons.Default.Polyline,            "Poly",  state.selectedTool, viewModel)
            HorizontalDivider(color = Surface2, thickness = 1.dp, modifier = Modifier.width(36.dp))
            // 3D operation tools
            ExtrudeButton(state.selectedTool, viewModel) // special button that opens extrude dialog
        }

        // ── Info overlay (bottom right): zoom level and entity count ───────
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface1.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Show zoom in 2D, show orbit angles in 3D
            if (state.is3DMode) {
                Text("yaw: ${state.orbitYaw.toInt()}°  pitch: ${state.orbitPitch.toInt()}°",
                    color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("dist: ${state.orbitDist.toInt()}",
                    color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("zoom: ${"%.2f".format(state.zoom)}×",
                    color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text("entities: ${state.entities.size}",
                color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        // ── Vertex coordinate edit dialog ─────────────────────────────────
        // Shown when the user double-taps a vertex handle
        state.editDialogHandle?.let { handle ->
            CoordEditDialog(
                handle    = handle,
                lastPos   = state.hoveredHandle?.pos ?: handle.pos,
                onConfirm = { newPos -> viewModel.applyHandleEdit(handle, newPos) },
                onDismiss = { viewModel.dismissEditDialog() }
            )
        }

        // ── Extrude depth dialog ───────────────────────────────────────────
        // Shown when the user taps the Extrude button
        if (state.extrudeDialogOpen) {
            ExtrudeDialog(
                onConfirm = { depth -> viewModel.confirmExtrude(depth) },
                onDismiss = { viewModel.dismissExtrudeDialog() }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Extrude button — special tool button that opens the extrude dialog
// Highlighted in orange to visually distinguish the 3D operation
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ExtrudeButton(activeTool: DrawTool, viewModel: CadViewModel) {
    val isActive = activeTool == DrawTool.EXTRUDE // true when extrude tool is selected
    Column(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) Color(0xFF3D2200) else Color.Transparent) // warm tint when active
            .border(1.dp,
                if (isActive) Accent3D else Color.Transparent,
                RoundedCornerShape(10.dp))
            .clickable {
                viewModel.selectTool(DrawTool.EXTRUDE) // select the tool
                viewModel.openExtrudeDialog()           // immediately open depth dialog
            }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Height,                // "Height" icon represents extrusion direction
            contentDescription = "Extrude",
            tint     = if (isActive) Accent3D else TextSecondary,
            modifier = Modifier.size(20.dp))
        Text("Ext",                               // abbreviated label to fit the button
            color      = if (isActive) Accent3D else TextSecondary,
            fontSize   = 7.sp,
            fontFamily = FontFamily.Monospace)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Extrude depth dialog — user enters how tall to make the solid
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ExtrudeDialog(
    onConfirm: (Float) -> Unit,   // called with the parsed depth value
    onDismiss: () -> Unit          // called on cancel
) {
    var depthStr by remember { mutableStateOf("100") } // default depth
    var error    by remember { mutableStateOf<String?>(null) } // validation error message

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Surface2)
                .padding(20.dp)
                .widthIn(min = 260.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Dialog title
            Row(
                verticalAlignment      = Alignment.CenterVertically,
                horizontalArrangement  = Arrangement.SpaceBetween,
                modifier               = Modifier.fillMaxWidth()
            ) {
                Text("Extrude",
                    color = Accent3D, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                Icon(Icons.Default.Close, contentDescription = "Close",
                    tint     = TextSecondary,
                    modifier = Modifier.clickable { onDismiss() }.size(20.dp))
            }

            Text("Height (world units):",
                color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

            // Depth input field
            OutlinedTextField(
                value       = depthStr,
                onValueChange = { depthStr = it; error = null }, // clear error on edit
                singleLine  = true,
                modifier    = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Accent3D,
                    unfocusedBorderColor = Surface3,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary,
                    cursorColor          = Accent3D
                ),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            )

            // Show validation error if the user typed a non-number
            error?.let {
                Text(it, color = ErrorColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            // Action buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick  = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) { Text("Cancel", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

                Button(
                    onClick = {
                        val d = depthStr.toFloatOrNull()
                        if (d == null || d <= 0f) {
                            error = "Enter a positive number"  // validation failed
                        } else {
                            onConfirm(d)                       // valid depth → extrude
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3D2200),    // warm dark orange container
                        contentColor   = Accent3D              // orange text
                    )
                ) {
                    Text("Extrude",
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Coordinate edit dialog (vertex editing)
// Three input modes: Absolute (X,Y), Relative (ΔX,ΔY), Polar (distance, angle°)
// ─────────────────────────────────────────────────────────────────────────────
private enum class CoordMode { ABSOLUTE, RELATIVE, POLAR }

@Composable
private fun CoordEditDialog(
    handle:    EditHandle,             // which vertex is being edited
    lastPos:   Vec2,                   // current position (used as base for relative/polar)
    onConfirm: (Vec2) -> Unit,         // called with the resolved new position
    onDismiss: () -> Unit
) {
    var mode     by remember { mutableStateOf(CoordMode.ABSOLUTE) }
    var xStr     by remember { mutableStateOf("%.4f".format(handle.pos.x)) }
    var yStr     by remember { mutableStateOf("%.4f".format(handle.pos.y)) }
    var dxStr    by remember { mutableStateOf("0") }
    var dyStr    by remember { mutableStateOf("0") }
    var distStr  by remember { mutableStateOf("0") }
    var angleStr by remember { mutableStateOf("0") }
    var error    by remember { mutableStateOf<String?>(null) }

    // Resolves the current field values into a Vec2 (returns null if invalid)
    fun resolve(): Vec2? = try {
        when (mode) {
            CoordMode.ABSOLUTE -> Vec2(xStr.toFloat(), yStr.toFloat())
            CoordMode.RELATIVE -> Vec2(lastPos.x + dxStr.toFloat(), lastPos.y + dyStr.toFloat())
            CoordMode.POLAR    -> {
                val d = distStr.toFloat()                       // distance from base point
                val a = Math.toRadians(angleStr.toDouble())     // angle in radians
                Vec2(lastPos.x + d * cos(a).toFloat(),
                    lastPos.y + d * sin(a).toFloat())
            }
        }
    } catch (e: NumberFormatException) { null }                 // invalid input

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Surface2)
                .padding(20.dp)
                .widthIn(min = 300.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: handle label + close button
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier.fillMaxWidth()
            ) {
                Text("Edit: ${handle.label}",
                    color = Accent, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Icon(Icons.Default.Close, contentDescription = "Close",
                    tint     = TextSecondary,
                    modifier = Modifier.clickable { onDismiss() }.size(20.dp))
            }

            // Current position display
            Text("Current: (${"%g".format(handle.pos.x)}, ${"%g".format(handle.pos.y)})",
                color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

            // Mode selector tabs
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface1),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CoordMode.entries.forEach { m ->
                    val active = m == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) AccentDim else Color.Transparent)
                            .clickable { mode = m; error = null } // switch mode, clear error
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(m.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (active) Accent else TextSecondary,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            // Input fields — change based on selected mode
            when (mode) {
                CoordMode.ABSOLUTE -> {
                    CoordField("X", xStr, "absolute X") { xStr = it; error = null }
                    CoordField("Y", yStr, "absolute Y") { yStr = it; error = null }
                }
                CoordMode.RELATIVE -> {
                    // Show the base point as context
                    Text("Base: (${"%g".format(lastPos.x)}, ${"%g".format(lastPos.y)})",
                        color = TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    CoordField("ΔX", dxStr, "delta X") { dxStr = it; error = null }
                    CoordField("ΔY", dyStr, "delta Y") { dyStr = it; error = null }
                }
                CoordMode.POLAR -> {
                    Text("Base: (${"%g".format(lastPos.x)}, ${"%g".format(lastPos.y)})",
                        color = TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    CoordField("Distance", distStr, "distance from base") { distStr = it; error = null }
                    CoordField("Angle °",  angleStr, "angle in degrees")  { angleStr = it; error = null }
                }
            }

            // Validation error message
            error?.let { Text(it, color = ErrorColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }

            // Cancel / Apply buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick  = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) { Text("Cancel", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

                Button(
                    onClick = {
                        val pos = resolve()
                        if (pos == null) error = "Invalid number"
                        else onConfirm(pos)
                    },
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = AccentDim,
                        contentColor   = Accent)
                ) {
                    Text("Apply",
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Single labelled text field used inside the coordinate dialog
@Composable
private fun CoordField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value         = value,
        onValueChange = onChange,
        label         = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
        placeholder   = { Text(placeholder, color = TextSecondary, fontSize = 11.sp) },
        singleLine    = true,
        modifier      = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Accent,
            unfocusedBorderColor = Surface3,
            focusedLabelColor    = Accent,
            unfocusedLabelColor  = TextSecondary,
            focusedTextColor     = TextPrimary,
            unfocusedTextColor   = TextPrimary,
            cursorColor          = Accent
        ),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Tool palette button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ToolButton(
    tool:       DrawTool,
    icon:       ImageVector,
    label:      String,
    activeTool: DrawTool,
    viewModel:  CadViewModel
) {
    val isActive = tool == activeTool
    Column(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) AccentDim else Color.Transparent) // highlight active tool
            .border(1.dp,
                if (isActive) Accent else Color.Transparent,
                RoundedCornerShape(10.dp))
            .clickable { viewModel.selectTool(tool) }   // select this tool on tap
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label,
            tint     = if (isActive) Accent else TextSecondary,
            modifier = Modifier.size(20.dp))
        Text(label,
            color      = if (isActive) Accent else TextSecondary,
            fontSize   = 7.sp,
            fontFamily = FontFamily.Monospace)
    }
}

// Small square button used in the top bar
@Composable
private fun TopBarButton(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}
