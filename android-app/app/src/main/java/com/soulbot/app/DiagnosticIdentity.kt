package com.soulbot.app

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/** Stable only inside this installation, so diagnostics can correlate flows without storing names. */
object DiagnosticIdentity {
    private const val SALT_FILE = "diagnostic-id.salt"
    @Volatile private var cachedSalt: String? = null

    @Synchronized
    fun contactId(context: Context, name: String): String {
        if (name.isBlank()) return "unknown"
        val salt = cachedSalt ?: runCatching {
            val file = java.io.File(context.noBackupFilesDir, SALT_FILE)
            file.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim()
                ?.takeIf(String::isNotBlank)
                ?: UUID.randomUUID().toString().also { file.writeText(it, Charsets.UTF_8) }
        }.getOrElse { UUID.randomUUID().toString() }.also { cachedSalt = it }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$salt\u0000$name".toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
