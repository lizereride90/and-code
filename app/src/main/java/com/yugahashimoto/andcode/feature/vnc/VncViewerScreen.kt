package com.yugahashimoto.andcode.feature.vnc

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.vnc.VncConnectionState
import com.yugahashimoto.andcode.data.vnc.VncDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VncViewerScreen(
    device: VncDevice,
    viewModel: VncViewerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val connected = state.connectionState == VncConnectionState.CONNECTED

    var vncView by remember { mutableStateOf<VncView?>(null) }
    var ctrlHeld by remember { mutableStateOf(false) }
    var altHeld by remember { mutableStateOf(false) }
    var panMode by remember { mutableStateOf(false) }
    var rightClick by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.connect() }

    DisposableEffect(Unit) {
        onDispose { viewModel.disconnect() }
    }

    LaunchedEffect(connected, device.scaling) {
        if (connected) {
            vncView?.framebuffer = viewModel.framebuffer
            vncView?.scaling = device.scaling
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = statusLabel(state.connectionState),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    if (state.connectionState == VncConnectionState.FAILED) {
                        IconButton(onClick = viewModel::connect) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.vnc_retry))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.connectionState) {
                VncConnectionState.CONNECTING -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.vnc_connecting),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }

                VncConnectionState.CONNECTED -> {
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { context ->
                            VncView(context).also { view ->
                                vncView = view
                                view.framebuffer = viewModel.framebuffer
                                view.scaling = device.scaling
                                view.pointerListener = { mask, x, y -> viewModel.sendPointer(mask, x, y) }
                            }
                        },
                        update = { view ->
                            if (view.panMode != panMode) view.panMode = panMode
                            if (view.rightClickMode != rightClick) view.rightClickMode = rightClick
                            if (view.scaling != device.scaling) view.scaling = device.scaling
                        },
                    )
                    ViewerControlBar(
                        ctrlHeld = ctrlHeld,
                        altHeld = altHeld,
                        panMode = panMode,
                        rightClick = rightClick,
                        inputText = inputText,
                        onToggleCtrl = { ctrlHeld = !ctrlHeld },
                        onToggleAlt = { altHeld = !altHeld },
                        onTogglePan = { panMode = !panMode },
                        onToggleRightClick = { rightClick = !rightClick },
                        onInputChange = { newValue ->
                            val previous = inputText
                            inputText = newValue
                            when {
                                newValue.length > previous.length -> {
                                    val added = newValue.substring(previous.length)
                                    added.forEach { viewModel.sendText(it, ctrlHeld, altHeld) }
                                }

                                newValue.length == previous.length - 1 && previous.isNotEmpty() -> {
                                    viewModel.tapKey(0xFF08) // BackSpace
                                }
                            }
                        },
                        onTapKey = { keysym ->
                            when (keysym) {
                                VncViewerViewModel.KEYSYM_CTRL -> {
                                    ctrlHeld = !ctrlHeld
                                    viewModel.sendKey(ctrlHeld, keysym)
                                }

                                VncViewerViewModel.KEYSYM_ALT -> {
                                    altHeld = !altHeld
                                    viewModel.sendKey(altHeld, keysym)
                                }

                                else -> viewModel.tapKey(keysym)
                            }
                        },
                    )
                }

                VncConnectionState.FAILED -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.vnc_connection_failed), style = MaterialTheme.typography.titleMedium)
                        state.errorMessage?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = viewModel::connect) {
                            Text(stringResource(R.string.vnc_retry))
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun ViewerControlBar(
    ctrlHeld: Boolean,
    altHeld: Boolean,
    panMode: Boolean,
    rightClick: Boolean,
    inputText: String,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onTogglePan: () -> Unit,
    onToggleRightClick: () -> Unit,
    onInputChange: (String) -> Unit,
    onTapKey: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            placeholder = { Text(stringResource(R.string.vnc_type_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyChip(text = stringResource(R.string.vnc_key_esc), onClick = { onTapKey(VncViewerViewModel.KEYSYM_ESC) })
            KeyChip(text = stringResource(R.string.vnc_key_tab), onClick = { onTapKey(VncViewerViewModel.KEYSYM_TAB) })
            KeyChip(text = "Ctrl", selected = ctrlHeld, onClick = onToggleCtrl)
            KeyChip(text = "Alt", selected = altHeld, onClick = onToggleAlt)
            KeyChip(text = "←", onClick = { onTapKey(VncViewerViewModel.KEYSYM_LEFT) })
            KeyChip(text = "→", onClick = { onTapKey(VncViewerViewModel.KEYSYM_RIGHT) })
            FilterChip(
                selected = panMode,
                onClick = onTogglePan,
                label = { Text(stringResource(R.string.vnc_pan_mode)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.PanTool,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
            )
            FilterChip(
                selected = rightClick,
                onClick = onToggleRightClick,
                label = { Text(stringResource(R.string.vnc_right_click)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun KeyChip(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        contentColor =
            if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun statusLabel(state: VncConnectionState): String =
    when (state) {
        VncConnectionState.CONNECTING -> stringResource(R.string.vnc_status_connecting)
        VncConnectionState.CONNECTED -> stringResource(R.string.vnc_status_connected)
        VncConnectionState.FAILED -> stringResource(R.string.vnc_status_failed)
        VncConnectionState.DISCONNECTED -> stringResource(R.string.vnc_status_disconnected)
    }