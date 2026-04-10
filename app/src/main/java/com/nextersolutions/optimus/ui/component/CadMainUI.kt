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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Architecture
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
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextersolutions.optimus.CadViewModel
import com.nextersolutions.optimus.model.DrawTool

// ── Color palette ─────────────────────────────────────────────────────────────
private val BgDark = Color(0xFF0D0D12)
private val Surface1 = Color(0xFF161620)
private val Surface2 = Color(0xFF1E1E2E)
private val Accent = Color(0xFF3DE8FF)
private val AccentDim = Color(0xFF1A6875)
private val TextPrimary = Color(0xFFE0E0F0)
private val TextSecondary = Color(0xFF7070A0)
private val ToolActive = Color(0xFF3DE8FF)
private val ToolInactive = Color(0xFF303050)

@Composable
fun CadMainUI(
    viewModel: CadViewModel,
    glView: @Composable () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showProperties by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(BgDark)) {

        // ── OpenGL canvas (full screen) ────────────────────────────────────
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
            // Title
            Text(
                "CAD",
                color = Accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            // Top action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopBarButton(
                    icon = if (state.showGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                    tint = if (state.showGrid) Accent else TextSecondary,
                    onClick = { viewModel.toggleGrid() }
                )
                TopBarButton(
                    icon = Icons.Default.Straighten,
                    tint = if (state.snapToGrid) Accent else TextSecondary,
                    onClick = { viewModel.toggleSnap() }
                )
                TopBarButton(
                    icon = Icons.Default.Undo,
                    tint = TextPrimary,
                    onClick = { viewModel.undo() }
                )
                TopBarButton(
                    icon = Icons.Default.DeleteSweep,
                    tint = Color(0xFFFF6060),
                    onClick = { viewModel.clearAll() }
                )
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
            Text(
                state.statusText,
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
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
            ToolButton(DrawTool.SELECT, Icons.Default.NearMe, "Sel", state.selectedTool, viewModel)
            Divider(color = Surface2, thickness = 1.dp, modifier = Modifier.width(36.dp))
            ToolButton(DrawTool.LINE, Icons.Default.ShowChart, "Line", state.selectedTool, viewModel)
            ToolButton(DrawTool.ARC, Icons.Default.Architecture, "Arc", state.selectedTool, viewModel)
            ToolButton(DrawTool.CIRCLE, Icons.Default.RadioButtonUnchecked, "Circ", state.selectedTool, viewModel)
            ToolButton(DrawTool.ELLIPSE, Icons.Default.Adjust, "Ellip", state.selectedTool, viewModel)
            ToolButton(DrawTool.SPLINE, Icons.Default.Timeline, "Spln", state.selectedTool, viewModel)
            ToolButton(DrawTool.POLYLINE, Icons.Default.Polyline, "Poly", state.selectedTool, viewModel)
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
    }
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
