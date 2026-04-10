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

// ── Color palette ─────────────────────────────────────────────────────────────
private val BgDark       = Color(0xFF0D0D12)
private val Surface1     = Color(0xFF161620)
private val Surface2     = Color(0xFF1E1E2E)
private val Surface3     = Color(0xFF252535)
private val Accent       = Color(0xFF3DE8FF)
private val AccentDim    = Color(0xFF1A6875)
private val TextPrimary  = Color(0xFFE0E0F0)
private val TextSecondary = Color(0xFF7070A0)
private val ErrorColor   = Color(0xFFFF6060)

@Composable
fun CadMainUI(
    viewModel: CadViewModel,
    glView: @Composable () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Box(Modifier.fillMaxSize().background(BgDark)) {

        glView()

        // ── Top bar ────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("CAD", color = Accent, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopBarButton(
                    icon  = if (state.showGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                    tint  = if (state.showGrid) Accent else TextSecondary,
                    onClick = { viewModel.toggleGrid() }
                )
                TopBarButton(
                    icon  = Icons.Default.Straighten,
                    tint  = if (state.snapToGrid) Accent else TextSecondary,
                    onClick = { viewModel.toggleSnap() }
                )
                TopBarButton(icon = Icons.Default.Undo,        tint = TextPrimary,  onClick = { viewModel.undo() })
                TopBarButton(icon = Icons.Default.DeleteSweep, tint = ErrorColor,   onClick = { viewModel.clearAll() })
            }
        }

        // ── Status bar ─────────────────────────────────────────────────────
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Surface2.copy(alpha = 0.9f))
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(state.statusText, color = TextSecondary,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        // ── Vertex tooltip (hovered handle coords) ─────────────────────────
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
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(h.label, color = Accent,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("X: ${"%.3f".format(h.pos.x)}  Y: ${"%.3f".format(h.pos.y)}",
                        color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("double-tap to edit", color = TextSecondary,
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── Tool palette (left side) ───────────────────────────────────────
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface1.copy(alpha = 0.92f))
                .padding(vertical = 8.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ToolButton(DrawTool.SELECT,   Icons.Default.NearMe,              "Sel",   state.selectedTool, viewModel)
            HorizontalDivider(color = Surface2, thickness = 1.dp, modifier = Modifier.width(36.dp))
            ToolButton(DrawTool.LINE,     Icons.Default.ShowChart,           "Line",  state.selectedTool, viewModel)
            ToolButton(DrawTool.ARC,      Icons.Default.Architecture,        "Arc",   state.selectedTool, viewModel)
            ToolButton(DrawTool.CIRCLE,   Icons.Default.RadioButtonUnchecked,"Circ",  state.selectedTool, viewModel)
            ToolButton(DrawTool.ELLIPSE,  Icons.Default.Adjust,              "Ellip", state.selectedTool, viewModel)
            ToolButton(DrawTool.SPLINE,   Icons.Default.Timeline,            "Spln",  state.selectedTool, viewModel)
            ToolButton(DrawTool.POLYLINE, Icons.Default.Polyline,            "Poly",  state.selectedTool, viewModel)
        }

        // ── Stats overlay (bottom right) ──────────────────────────────────
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
            Text("zoom: ${"%.2f".format(state.zoom)}x",
                color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("entities: ${state.entities.size}",
                color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        // ── Coordinate edit dialog ─────────────────────────────────────────
        state.editDialogHandle?.let { handle ->
            CoordEditDialog(
                handle   = handle,
                lastPos  = state.hoveredHandle?.pos ?: handle.pos,
                onConfirm = { newPos -> viewModel.applyHandleEdit(handle, newPos) },
                onDismiss = { viewModel.dismissEditDialog() }
            )
        }
    }
}

// ── Coordinate edit dialog ────────────────────────────────────────────────────
private enum class CoordMode { ABSOLUTE, RELATIVE, POLAR }

@Composable
private fun CoordEditDialog(
    handle: EditHandle,
    lastPos: Vec2,
    onConfirm: (Vec2) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(CoordMode.ABSOLUTE) }

    // Field state
    var xStr     by remember { mutableStateOf("%.4f".format(handle.pos.x)) }
    var yStr     by remember { mutableStateOf("%.4f".format(handle.pos.y)) }
    var dxStr    by remember { mutableStateOf("0") }
    var dyStr    by remember { mutableStateOf("0") }
    var distStr  by remember { mutableStateOf("0") }
    var angleStr by remember { mutableStateOf("0") }

    var error by remember { mutableStateOf<String?>(null) }

    fun resolve(): Vec2? {
        return try {
            when (mode) {
                CoordMode.ABSOLUTE -> Vec2(xStr.toFloat(), yStr.toFloat())
                CoordMode.RELATIVE -> Vec2(lastPos.x + dxStr.toFloat(), lastPos.y + dyStr.toFloat())
                CoordMode.POLAR -> {
                    val d = distStr.toFloat()
                    val a = Math.toRadians(angleStr.toDouble())
                    Vec2(lastPos.x + d * cos(a).toFloat(), lastPos.y + d * sin(a).toFloat())
                }
            }
        } catch (e: NumberFormatException) { null }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Surface2)
                .padding(20.dp)
                .widthIn(min = 300.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Edit: ${handle.label}", color = Accent,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Icon(Icons.Default.Close, contentDescription = "Close",
                    tint = TextSecondary, modifier = Modifier.clickable { onDismiss() }.size(20.dp))
            }

            // Current position chip
            Text("Current: (${"%g".format(handle.pos.x)}, ${"%g".format(handle.pos.y)})",
                color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

            // Mode tabs
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface1),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                CoordMode.entries.forEach { m ->
                    val active = m == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) AccentDim else Color.Transparent)
                            .clickable { mode = m; error = null }
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

            // Input fields
            when (mode) {
                CoordMode.ABSOLUTE -> {
                    CoordField("X", xStr, "absolute X") { xStr = it; error = null }
                    CoordField("Y", yStr, "absolute Y") { yStr = it; error = null }
                }
                CoordMode.RELATIVE -> {
                    Text("Base: (${"%g".format(lastPos.x)}, ${"%g".format(lastPos.y)})",
                        color = TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    CoordField("ΔX", dxStr, "delta X") { dxStr = it; error = null }
                    CoordField("ΔY", dyStr, "delta Y") { dyStr = it; error = null }
                }
                CoordMode.POLAR -> {
                    Text("Base: (${"%g".format(lastPos.x)}, ${"%g".format(lastPos.y)})",
                        color = TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    CoordField("Distance", distStr, "distance") { distStr = it; error = null }
                    CoordField("Angle °",  angleStr, "angle in degrees") { angleStr = it; error = null }
                }
            }

            error?.let {
                Text(it, color = ErrorColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            // Buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) { Text("Cancel", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }

                Button(
                    onClick = {
                        val pos = resolve()
                        if (pos == null) error = "Invalid number"
                        else onConfirm(pos)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentDim, contentColor = Accent)
                ) { Text("Apply", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun CoordField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
        placeholder = { Text(placeholder, color = TextSecondary, fontSize = 11.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
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

// ── Sub-components ────────────────────────────────────────────────────────────
@Composable
private fun ToolButton(
    tool: DrawTool,
    icon: ImageVector,
    label: String,
    activeTool: DrawTool,
    viewModel: CadViewModel
) {
    val isActive = tool == activeTool
    Column(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) AccentDim else Color.Transparent)
            .border(
                width = if (isActive) 1.dp else 0.dp,
                color = if (isActive) Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { viewModel.selectTool(tool) }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label,
            tint = if (isActive) Accent else TextSecondary, modifier = Modifier.size(20.dp))
        Text(label, color = if (isActive) Accent else TextSecondary,
            fontSize = 7.sp, fontFamily = FontFamily.Monospace)
    }
}

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
