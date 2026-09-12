package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSanitizerTest {
    @Test
    fun redactsAuthorizationBearerAndStandaloneBearerValues() {
        val source = "Authorization: Bearer super.secret-token Bearer another-secret"

        val result = DiagnosticLogSanitizer.redactSecrets(source)

        assertEquals("Authorization: [REDACTED] Bearer [REDACTED]", result)
        assertFalse(result.contains("super.secret-token"))
        assertFalse(result.contains("another-secret"))
    }

    @Test
    fun redactsApiKeysTokensAndOpenAiStyleKeysInTextAndUrls() {
        val source =
            "api_key=my-secret-key&token=other-secret " +
                "accessToken: abcdefgh refresh-token=ijklmnop sk-live_SECRET123"

        val result = DiagnosticLogSanitizer.redactSecrets(source)

        assertFalse(result.contains("my-secret-key"))
        assertFalse(result.contains("other-secret"))
        assertFalse(result.contains("abcdefgh"))
        assertFalse(result.contains("ijklmnop"))
        assertFalse(result.contains("sk-live_SECRET123"))
        assertEquals(5, Regex.fromLiteral("[REDACTED]").findAll(result).count())
    }

    @Test
    fun redactsJsonCredentialsAndBasicAuthUrls() {
        val source =
            "{\"apiKey\":\"json-secret\",\"Authorization\":\"Bearer jwt.value\"} " +
                "Authorization=Basic dXNlcjpwYXNz " +
                "https://user:password@example.com/v1"

        val result = DiagnosticLogSanitizer.redactSecrets(source)

        assertFalse(result.contains("json-secret"))
        assertFalse(result.contains("jwt.value"))
        assertFalse(result.contains("dXNlcjpwYXNz"))
        assertFalse(result.contains("password"))
        assertTrue(result.contains("https://[REDACTED]@example.com/v1"))
    }

    @Test
    fun singleLineNormalizesControlsAndAppliesHardLimit() {
        val result = DiagnosticLogSanitizer.singleLine(" first\nsecond\r\u0000third ", 18)

        assertEquals(18, result.length)
        assertEquals("first second  thi…", result)
        assertFalse(result.contains('\n'))
    }

    @Test
    fun safeDiagnosticTextIsPreserved() {
        val source = "mode=SOUL_MATCH attempts=2 durationMs=814"

        assertEquals(source, DiagnosticLogSanitizer.singleLine(source))
    }

    @Test
    fun redactsGenericKeyQueryParameters() {
        val source = "https://example.test/v1?key=plain-secret&mode=chat&api-key=second-secret"

        val result = DiagnosticLogSanitizer.redactSecrets(source)

        assertFalse(result.contains("plain-secret"))
        assertFalse(result.contains("second-secret"))
        assertEquals(2, Regex.fromLiteral("[REDACTED]").findAll(result).count())
    }
}
