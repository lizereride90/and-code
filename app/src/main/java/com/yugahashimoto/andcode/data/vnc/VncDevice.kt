package com.yugahashimoto.andcode.data.vnc

import kotlinx.serialization.Serializable
import java.util.UUID

/** A saved VNC (remote desktop) connection profile. */
@Serializable
data class VncDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String = "localhost",
    val port: Int = 5901,
    val password: String = "",
    /** How the remote framebuffer is mapped onto the phone screen: fit | stretch | one. */
    val scaling: String = "fit",
) {
    val hostPort: String get() = "$host:$port"
    val isPasswordSet: Boolean get() = password.isNotBlank()

    fun validate(): String? =
        when {
            name.isBlank() -> "Name is required"
            host.isBlank() -> "Host is required"
            port !in 1..65535 -> "Port must be between 1 and 65535"
            else -> null
        }
}

object VncScaling {
    const val FIT = "fit"
    const val STRETCH = "stretch"
    const val ONE = "one"

    val options: List<String> = listOf(FIT, STRETCH, ONE)
}