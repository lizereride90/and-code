package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalRuntimeManifestTest {
    private val architecture =
        LocalRuntimeArchitecture(
            debianUrl = "https://registry-1.docker.io/v2/library/debian/blobs/sha256:abc",
            debianSha256 = "a".repeat(64),
            debianSizeBytes = 28_117_255,
            openCodeUrl = "https://example.com/opencode.tar.gz",
            openCodeSha256 = "b".repeat(64),
        )

    @Test
    fun `valid manifest resolves architecture`() {
        val manifest =
            LocalRuntimeManifest(
                schemaVersion = 1,
                runtimeVersion = "2026.09.12.1",
                openCodeVersion = "1.18.30",
                debianVersion = "12-bookworm-slim",
                port = 4097,
                architectures = mapOf("arm64-v8a" to architecture),
            )

        manifest.validate()

        assertEquals(architecture, manifest.architecture("arm64-v8a"))
    }

    @Test
    fun `manifest rejects insecure Debian download URL`() {
        val invalid = architecture.copy(debianUrl = "http://example.com/debian.tar.gz")

        assertThrows(IllegalArgumentException::class.java) {
            invalid.validate("arm64-v8a")
        }
    }

    @Test
    fun `manifest rejects invalid hash`() {
        val invalid = architecture.copy(openCodeSha256 = "not-a-hash")

        assertThrows(IllegalArgumentException::class.java) {
            invalid.validate("arm64-v8a")
        }
    }

    @Test
    fun `manifest rejects non-positive size`() {
        val invalid = architecture.copy(debianSizeBytes = 0)

        assertThrows(IllegalArgumentException::class.java) {
            invalid.validate("arm64-v8a")
        }
    }
}