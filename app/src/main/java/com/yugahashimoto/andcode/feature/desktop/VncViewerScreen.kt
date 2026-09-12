package com.yugahashimoto.andcode.feature.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Full-screen VNC viewer. A single finger moves the pointer (drag holds the active mouse button and
 * a quick tap clicks), a long press right-clicks, a second finger drags/pinches the viewport and a
 * two-finger tap middle-clicks.
 */
@Composable
fun VncViewerScreen(
    state: VncViewerUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onInstallAndConnect: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onPointerModeChange: (VncPointerMode) -> Unit,
    onSendPointer: (buttons: Int, x: Int, y: Int) -> Unit,
    onSendKeysym: (keysym: Int, isDown: Boolean) -> Unit,
    onSendCharacter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSendLine: () -> Unit,
    onTypingBufferChange: (String) -> Unit,
) {
    val pointerButtons =
        when (state.pointerMode) {
            VncPointerMode.LEFT -> RfbButtons.LEFT
            VncPointerMode.MIDDLE -> RfbButtons.MIDDLE
            VncPointerMode.RIGHT -> RfbButtons.RIGHT
        }

    var lastDesktopSize by remember { mutableStateOf(IntSize.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    fun fitToViewport() {
        val imageWidth = state.desktopWidth
        val imageHeight = state.desktopHeight
        if (viewportSize.width == 0 || imageWidth == 0) return
        val fitScale =
            min(
                viewportSize.width.toFloat() / imageWidth,
                viewportSize.height.toFloat() / imageHeight,
            )
        scale = fitScale
        offsetX = (viewportSize.width - imageWidth * fitScale) / 2f
        offsetY = (viewportSize.height - imageHeight * fitScale) / 2f
    }

    LaunchedEffect(state.desktopWidth, state.desktopHeight) {
        val current = IntSize(state.desktopWidth, state.desktopHeight)
        if (current != lastDesktopSize && current.width > 0) {
            lastDesktopSize = current
            fitToViewport()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vnc_viewer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    val connected = state.phase is VncPhase.Connected
                    IconButton(onClick = onToggleKeyboard, enabled = connected) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = stringResource(R.string.vnc_keyboard_toggle),
                        )
                    }
                    IconButton(onClick = ::fitToViewport, enabled = connected) {
                        Icon(
                            Icons.Default.AspectRatio,
                            contentDescription = stringResource(R.string.vnc_zoom_fit),
                        )
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.vnc_disconnect))
                    }
                },
            )
        },
        bottomBar = {
            if (state.phase is VncPhase.Connected && state.keyboardVisible) {
                DesktopKeyboardBar(
                    typingBuffer = state.typingBuffer,
                    pointerMode = state.pointerMode,
                    onPointerModeChange = onPointerModeChange,
                    onTypingBufferChange = onTypingBufferChange,
                    onSendCharacter = onSendCharacter,
                    onBackspace = onBackspace,
                    onSendLine = onSendLine,
                    onSendKeysym = onSendKeysym,
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged { viewportSize = it },
        ) {
            when (val phase = state.phase) {
                is VncPhase.Connected -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        phase.image?.let { image ->
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            transformOrigin = TransformOrigin(0f, 0f)
                                            this.scaleX = scale
                                            this.scaleY = scale
                                            translationX = offsetX
                                            translationY = offsetY
                                        },
                                contentScale = ContentScale.None,
                            )
                        }
                        DesktopPointerGestures(
                            modifier = Modifier.fillMaxSize(),
                            desktopWidth = state.desktopWidth,
                            desktopHeight = state.desktopHeight,
                            scale = scale,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            pointerButtons = pointerButtons,
                            onViewportChanged = { newScale, newOffsetX, newOffsetY ->
                                scale = newScale
                                offsetX = newOffsetX
                                offsetY = newOffsetY
                            },
                            onSendPointer = onSendPointer,
                        )
                        Column(
                            modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp)
                                    .widthIn(max = 240.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val modeLabel =
                                when (state.pointerMode) {
                                    VncPointerMode.LEFT -> stringResource(R.string.vnc_pointer_mode_left)
                                    VncPointerMode.MIDDLE -> stringResource(R.string.vnc_pointer_mode_middle)
                                    VncPointerMode.RIGHT -> stringResource(R.string.vnc_pointer_mode_right)
                                }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            ) {
                                Text(
                                    modeLabel,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                VncPhase.Closed, VncPhase.NotInstalled, VncPhase.Installing, VncPhase.Starting, VncPhase.Connecting -> {
                    // Status overlays below.
                }
                is VncPhase.Failed ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(onClick = onRetry) {
                                Text(stringResource(R.string.vnc_retry))
                            }
                            OutlinedButton(onClick = onBack) {
                                Text(stringResource(R.string.vnc_back))
                            }
                            Text(
                                phase.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
            }

            when (val phase = state.phase) {
                is VncPhase.Installing, is VncPhase.Starting, is VncPhase.Connecting -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            when (phase) {
                                VncPhase.Installing -> stringResource(R.string.vnc_installing)
                                VncPhase.Starting -> stringResource(R.string.vnc_starting)
                                else -> stringResource(R.string.vnc_connecting)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                VncPhase.NotInstalled -> {
                    Column(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.vnc_desktop_not_installed),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onInstallAndConnect) {
                            Text(stringResource(R.string.vnc_install_button))
                        }
                    }
                }
                VncPhase.Closed, VncPhase.Connected -> Unit
                is VncPhase.Failed -> Unit
            }
        }
    }
}

@Composable
private fun DesktopPointerGestures(
    modifier: Modifier,
    desktopWidth: Int,
    desktopHeight: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    pointerButtons: Int,
    onViewportChanged: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    onSendPointer: (buttons: Int, x: Int, y: Int) -> Unit,
) {
    val currentScale = rememberUpdatedState(scale)
    val currentOffsetX = rememberUpdatedState(offsetX)
    val currentOffsetY = rememberUpdatedState(offsetY)
    val sendPointerRef = rememberUpdatedState(onSendPointer)
    val viewportChangedRef = rememberUpdatedState(onViewportChanged)

    fun toDesktop(position: Offset): Offset {
        val desktopX = (position.x - currentOffsetX.value) / currentScale.value
        val desktopY = (position.y - currentOffsetY.value) / currentScale.value
        val maxX = (desktopWidth - 1).coerceAtLeast(0)
        val maxY = (desktopHeight - 1).coerceAtLeast(0)
        return Offset(
            desktopX.coerceIn(0f, maxX.toFloat()),
            desktopY.coerceIn(0f, maxY.toFloat()),
        )
    }

    Box(
        modifier =
            modifier.pointerInput(desktopWidth, desktopHeight, pointerButtons) {
                val density = this.density
                val deadZone = 10 * density
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pointerCount = 1
                    var wasMultiTouch = false
                    var beganDrag = false
                    var pressHeld = false
                    var longPressSent = false
                    var lastSent = Offset.Zero
                    var pressedButtons = 0

                    fun sendButtons(
                        buttons: Int,
                        position: Offset,
                    ) {
                        val desktop = toDesktop(position)
                        sendPointerRef.value(buttons, desktop.x.roundToInt(), desktop.y.roundToInt())
                        lastSent = desktop
                    }

                    fun releaseButtons() {
                        if (pressHeld) {
                            sendPointerRef.value(0, lastSent.x.roundToInt(), lastSent.y.roundToInt())
                            pressHeld = false
                            pressedButtons = 0
                        }
                    }

                    val longPress =
                        launch {
                            delay(600)
                            if (pointerCount == 1 && !beganDrag && !wasMultiTouch && isActive) {
                                longPressSent = true
                                val desktop = toDesktop(down.position)
                                val x = desktop.x.roundToInt()
                                val y = desktop.y.roundToInt()
                                sendPointerRef.value(RfbButtons.RIGHT, x, y)
                                sendPointerRef.value(0, x, y)
                            }
                        }

                    var panStartScale = currentScale.value
                    var panStartOffsetX = currentOffsetX.value
                    var panStartOffsetY = currentOffsetY.value
                    var panStartCentroid = Offset.Zero
                    var panStartSpacing = 1f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        pointerCount = pressed.size

                        if (pointerCount >= 2) {
                            if (!wasMultiTouch) {
                                wasMultiTouch = true
                                longPress.cancel()
                                releaseButtons()
                                panStartScale = currentScale.value
                                panStartOffsetX = currentOffsetX.value
                                panStartOffsetY = currentOffsetY.value
                                panStartCentroid = centroid(pressed)
                                panStartSpacing = max(1f, spacing(pressed))
                            }
                            val centroidNow = centroid(pressed)
                            val spacingNow = max(1f, spacing(pressed))
                            val newScale =
                                (panStartScale * spacingNow / panStartSpacing).coerceIn(0.2f, 8f)
                            val anchored =
                                anchorDelta(
                                    startCentroid = panStartCentroid,
                                    currentCentroid = centroidNow,
                                    offsetX = panStartOffsetX,
                                    offsetY = panStartOffsetY,
                                    newScale = newScale,
                                    oldScale = panStartScale,
                                )
                            viewportChangedRef.value(
                                newScale,
                                anchored.x,
                                anchored.y,
                            )
                            releaseButtons()
                            event.changes.forEach { if (it.pressed) it.consume() }
                            continue
                        }

                        val change = pressed.first()
                        if (wasMultiTouch) {
                            wasMultiTouch = false
                            beganDrag = true
                            change.consume()
                            continue
                        }

                        if (!beganDrag && (change.position - down.position).getDistance() > deadZone) {
                            beganDrag = true
                            longPress.cancel()
                        }

                        if (longPressSent) {
                            sendButtons(0, change.position)
                            change.consume()
                            continue
                        }

                        if (beganDrag) {
                            if (!pressHeld) {
                                pressHeld = true
                                pressedButtons = pointerButtons
                            }
                            sendButtons(pressedButtons, change.position)
                        } else {
                            sendButtons(0, change.position)
                        }
                        change.consume()
                    }

                    longPress.cancel()
                    if (pointerCount == 1 && !wasMultiTouch && !beganDrag && !longPressSent) {
                        val desktop = toDesktop(down.position)
                        val x = desktop.x.roundToInt()
                        val y = desktop.y.roundToInt()
                        sendPointerRef.value(pointerButtons, x, y)
                        sendPointerRef.value(0, x, y)
                    } else {
                        releaseButtons()
                    }
                }
            },
    ) {}
}

private fun centroid(changes: List<PointerInputChange>): Offset {
    if (changes.isEmpty()) return Offset.Zero
    var x = 0f
    var y = 0f
    changes.forEach { change ->
        x += change.position.x
        y += change.position.y
    }
    return Offset(x / changes.size, y / changes.size)
}

private fun spacing(changes: List<PointerInputChange>): Float {
    if (changes.size < 2) return 1f
    val a = changes[0].position
    val b = changes[1].position
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}

private fun anchorDelta(
    startCentroid: Offset,
    currentCentroid: Offset,
    offsetX: Float,
    offsetY: Float,
    newScale: Float,
    oldScale: Float,
): Offset {
    val zoomAbout = startCentroid
    val scaleChangeAt =
        Offset(
            (zoomAbout.x - offsetX) * (newScale / oldScale - 1f),
            (zoomAbout.y - offsetY) * (newScale / oldScale - 1f),
        )
    val panned = currentCentroid - startCentroid
    return Offset(offsetX - scaleChangeAt.x + panned.x, offsetY - scaleChangeAt.y + panned.y)
}

@Composable
private fun DesktopKeyboardBar(
    typingBuffer: String,
    pointerMode: VncPointerMode,
    onPointerModeChange: (VncPointerMode) -> Unit,
    onTypingBufferChange: (String) -> Unit,
    onSendCharacter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSendLine: () -> Unit,
    onSendKeysym: (keysym: Int, isDown: Boolean) -> Unit,
) {
    KeyRow(
        items =
            listOf(
                KeySpec(0xFF1B, "Esc"),
                KeySpec(0xFF09, "Tab"),
                KeySpec(0xFF08, "Backspace"),
                KeySpec(0xFF0D, "Enter"),
                KeySpec(0xFF51, "←"),
                KeySpec(0xFF52, "↑"),
                KeySpec(0xFF53, "→"),
                KeySpec(0xFF54, "↓"),
            ),
        onSend = onSendKeysym,
    )
    val labels =
        when (pointerMode) {
            VncPointerMode.LEFT -> R.string.vnc_pointer_mode_left_short
            VncPointerMode.MIDDLE -> R.string.vnc_pointer_mode_middle_short
            VncPointerMode.RIGHT -> R.string.vnc_pointer_mode_right_short
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                onPointerModeChange(
                    when (pointerMode) {
                        VncPointerMode.LEFT -> VncPointerMode.MIDDLE
                        VncPointerMode.MIDDLE -> VncPointerMode.RIGHT
                        VncPointerMode.RIGHT -> VncPointerMode.LEFT
                    },
                )
            },
            modifier = Modifier.height(44.dp),
        ) {
            Text(stringResource(labels))
        }
        OutlinedTextField(
            value = typingBuffer,
            onValueChange = onTypingBufferChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.vnc_typing_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSendLine() }),
        )
        Button(
            onClick = onSendLine,
            modifier = Modifier.height(44.dp),
        ) {
            Text(stringResource(R.string.vnc_enter_button))
        }
    }
}

private data class KeySpec(
    val keysym: Int,
    val label: String,
)

@Composable
private fun KeyRow(
    items: List<KeySpec>,
    onSend: (keysym: Int, isDown: Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { spec ->
            TextButton(
                onClick = {
                    onSend(spec.keysym, true)
                    onSend(spec.keysym, false)
                },
            ) {
                Text(spec.label, fontFamily = FontFamily.Monospace)
            }
        }
    }
}