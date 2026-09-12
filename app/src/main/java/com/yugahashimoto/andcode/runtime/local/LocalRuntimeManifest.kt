package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocalRuntimeManifest(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("runtimeVersion") val runtimeVersion: String,
    @SerialName("openCodeVersion") val openCodeVersion: String,
    @SerialName("debianVersion") val debianVersion: String,
    @SerialName("port") val port: Int,
    @SerialName("architectures") val architectures: Map<String, LocalRuntimeArchitecture>,
) {
    fun architecture(abi: String): LocalRuntimeArchitecture =
        requireNotNull(architectures[abi]) { "Local runtime does not support ABI $abi" }

    fun validate() {
        require(schemaVersion == 1) { "Unsupported local runtime manifest schema: $schemaVersion" }
        require(runtimeVersion.isNotBlank()) { "Runtime version is missing" }
        require(openCodeVersion.isNotBlank()) { "OpenCode version is missing" }
        require(debianVersion.isNotBlank()) { "Debian guest version is missing" }
        require(port in 1024..65535) { "Invalid local OpenCode port: $port" }
        require(architectures.isNotEmpty()) { "Runtime manifest has no architectures" }
        architectures.forEach { (abi, item) -> item.validate(abi) }
    }
}

@Serializable
data class LocalRuntimeArchitecture(
    @SerialName("debianUrl") val debianUrl: String,
    @SerialName("debianSha256") val debianSha256: String,
    @SerialName("debianSizeBytes") val debianSizeBytes: Long,
    @SerialName("openCodeUrl") val openCodeUrl: String,
    @SerialName("openCodeSha256") val openCodeSha256: String,
    /**
     * Asset path inside the APK for a pre-installed rootfs (packages + desktop + VNC already
     * baked in). When present the installer extracts this and never runs apt on the device;
     * stock builds leave it absent and fall back to downloading the slim image.
     */
    @SerialName("desktopBakedAsset") val desktopBakedAsset: String? = null,
) {
    fun validate(abi: String) {
        require(debianUrl.startsWith("https://")) { "Debian rootfs URL for $abi must use HTTPS" }
        require(openCodeUrl.startsWith("https://")) { "OpenCode URL for $abi must use HTTPS" }
        require(SHA256.matches(debianSha256)) { "Invalid Debian rootfs SHA-256 for $abi" }
        require(SHA256.matches(openCodeSha256)) { "Invalid OpenCode SHA-256 for $abi" }
        require(debianSizeBytes > 0L) { "Invalid Debian rootfs size for $abi" }
        desktopBakedAsset?.let { require(it.isNotBlank()) { "Baked desktop asset for $abi must not be blank" } }
    }

    companion object {
        private val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}

class LocalRuntimeManifestReader(
    private val context: Context,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    fun read(): LocalRuntimeManifest {
        val payload = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return json.decodeFromString<LocalRuntimeManifest>(payload).also { it.validate() }
    }

    companion object {
        private const val ASSET_NAME = "local-runtime-manifest.json"
    }
}
