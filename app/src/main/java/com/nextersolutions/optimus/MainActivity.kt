package com.nextersolutions.optimus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.nextersolutions.optimus.gl.CadGLSurfaceView
import com.nextersolutions.optimus.gl.CadRenderer
import com.nextersolutions.optimus.ui.component.CadMainUI
import com.nextersolutions.optimus.ui.theme.OptimusCADTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CadViewModel by viewModels()
    private lateinit var renderer: CadRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        renderer = CadRenderer()

        // Back button: if we have pending input points → cancel the last one.
        // Otherwise, let the system handle it (exits the app).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val state = viewModel.state.value
                if (state.inputPoints.isNotEmpty()) {
                    viewModel.cancelLastPoint()
                } else {
                    // No points in progress — default behaviour (exit)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setContent {
            val state by viewModel.state.collectAsState()

            // Keep renderer in sync with state
            LaunchedEffect(state) {
                renderer.cadState = state
            }

            OptimusCADTheme {
                CadMainUI(viewModel = viewModel) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            CadGLSurfaceView(
                                context        = ctx,
                                renderer       = renderer,
                                getCurrentTool = { viewModel.state.value.selectedTool },
                                onTap = { x, y ->
                                    val (w, h) = renderer.getViewDimensions()
                                    viewModel.onTap(x, y, w, h)
                                },
                                onDoubleTap = { x, y ->
                                    val (w, h) = renderer.getViewDimensions()
                                    viewModel.onDoubleTap(x, y, w, h)
                                },
                                onMove = { x, y ->
                                    val (w, h) = renderer.getViewDimensions()
                                    viewModel.onMove(x, y, w, h)
                                },
                                onPan  = { dx, dy -> viewModel.onPan(dx, dy) },
                                onZoom = { factor, fx, fy ->
                                    val (w, h) = renderer.getViewDimensions()
                                    viewModel.onZoom(factor, fx, fy, w, h)
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}
