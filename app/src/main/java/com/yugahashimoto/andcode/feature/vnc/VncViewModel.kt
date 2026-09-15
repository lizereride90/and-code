package com.yugahashimoto.andcode.feature.vnc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.data.vnc.Framebuffer
import com.yugahashimoto.andcode.data.vnc.VncConnectionState
import com.yugahashimoto.andcode.data.vnc.VncDevice
import com.yugahashimoto.andcode.data.vnc.VncDeviceStore
import com.yugahashimoto.andcode.data.vnc.VncRfbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VncListUiState(
    val devices: List<VncDevice> = emptyList(),
)

class VncDeviceListViewModel(
    private val store: VncDeviceStore,
) : ViewModel() {
    private val _state = MutableStateFlow(VncListUiState(devices = store.devices()))
    val state: StateFlow<VncListUiState> = _state.asStateFlow()

    fun delete(deviceId: String) {
        store.delete(deviceId)
        _state.update { it.copy(devices = store.devices()) }
    }

    fun refresh() {
        _state.update { it.copy(devices = store.devices()) }
    }
}

data class VncEditUiState(
    val existing: VncDevice? = null,
    val name: String = "",
    val host: String = "localhost",
    val portText: String = "5901",
    val password: String = "",
    val scaling: String = "fit",
)

class VncDeviceEditViewModel(
    private val store: VncDeviceStore,
    deviceId: String?,
) : ViewModel() {
    private val existing = deviceId?.let(store::device)

    private val _state = MutableStateFlow(
        VncEditUiState(
            existing = existing,
            name = existing?.name.orEmpty(),
            host = existing?.host ?: "localhost",
            portText = (existing?.port ?: 5901).toString(),
            password = existing?.password.orEmpty(),
            scaling = existing?.scaling ?: "fit",
        ),
    )
    val state: StateFlow<VncEditUiState> = _state.asStateFlow()

    val isEditing: Boolean get() = existing != null

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setHost(value: String) = _state.update { it.copy(host = value) }

    fun setPort(value: String) = _state.update { it.copy(portText = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun setScaling(value: String) = _state.update { it.copy(scaling = value) }

    /** Returns null on success, or an error message the screen should show. */
    fun save(): String? {
        val current = _state.value
        val port = current.portText.trim().toIntOrNull() ?: -1
        val device =
            VncDevice(
                id = current.existing?.id ?: java.util.UUID.randomUUID().toString(),
                name = current.name.trim(),
                host = current.host.trim(),
                port = port,
                password = current.password,
                scaling = current.scaling,
            )
        device.validate()?.let { return it }
        store.upsert(device)
        return null
    }

    fun delete() {
        existing?.let { store.delete(it.id) }
    }
}

data class VncViewerUiState(
    val connectionState: VncConnectionState = VncConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    val serverName: String? = null,
)

class VncViewerViewModel(
    private val device: VncDevice,
) : ViewModel() {
    private val _state = MutableStateFlow(VncViewerUiState())
    val state: StateFlow<VncViewerUiState> = _state.asStateFlow()

    val framebuffer: Framebuffer = Framebuffer(1, 1)

    private var client: VncRfbClient? = null
    private var started = false

    fun connect() {
        if (started) return
        started = true
        _state.update { it.copy(connectionState = VncConnectionState.CONNECTING, errorMessage = null) }
        client =
            VncRfbClient(
                device = device,
                framebuffer = framebuffer,
                scope = viewModelScope,
                onState = { state, message -> handleState(state, message) },
                onServerName = { name -> _state.update { it.copy(serverName = name) } },
            )
        client?.start()
    }

    fun disconnect() {
        client?.disconnect()
        client = null
        started = false
        _state.update { it.copy(connectionState = VncConnectionState.DISCONNECTED) }
    }

    fun sendPointer(
        buttonMask: Int,
        x: Int,
        y: Int,
    ) {
        client?.sendPointerEvent(buttonMask, x, y)
    }

    /** Raw key event (used for modifiers that must stay held). */
    fun sendKey(
        down: Boolean,
        keysym: Int,
    ) {
        client?.sendKey(down, keysym)
    }

    /** Key press + release, sent atomically so they can never reorder. */
    fun tapKey(keysym: Int) {
        client?.sendKeySequence(listOf(true to keysym, false to keysym))
    }

    fun sendText(
        character: Char,
        ctrlHeld: Boolean = false,
        altHeld: Boolean = false,
    ) {
        client?.sendText(character, ctrlHeld, altHeld)
    }

    private fun handleState(
        state: VncConnectionState,
        message: String?,
    ) {
        _state.update { it.copy(connectionState = state, errorMessage = message) }
        if (state == VncConnectionState.FAILED) {
            client = null
            started = false
        }
    }

    override fun onCleared() {
        disconnect()
    }

    companion object {
        const val KEYSYM_CTRL = 0xFFE3
        const val KEYSYM_ALT = 0xFFE9
        const val KEYSYM_SHIFT = 0xFFE1
        const val KEYSYM_ESC = 0xFF1B
        const val KEYSYM_TAB = 0xFF09
        const val KEYSYM_LEFT = 0xFF51
        const val KEYSYM_UP = 0xFF52
        const val KEYSYM_RIGHT = 0xFF53
        const val KEYSYM_DOWN = 0xFF54
    }
}