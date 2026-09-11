package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEndpointTest {
    private fun endpoint(
        id: String,
        enabled: Boolean = true,
        baseUrl: String = "https://example.com/v1",
        model: String = "chat-model",
        apiKey: String = "key",
    ) = ModelEndpoint(id, id, "中转站", baseUrl, model, apiKey, enabled)

    @Test
    fun validationRequiresCompleteOpenAiCompatibleConfiguration() {
        assertNull(ModelEndpointRules.validationError(endpoint("primary")))
        assertTrue(ModelEndpointRules.validationError(endpoint("bad", baseUrl = "example.com"))!!.contains("地址"))
        assertTrue(ModelEndpointRules.validationError(endpoint("bad", apiKey = ""))!!.contains("API Key"))
        assertTrue(
            ModelEndpointRules.validationError(
                endpoint("bad", baseUrl = "https://example.com/v1/chat/completions"),
            )!!.contains("根地址"),
        )
    }

    @Test
    fun candidatesKeepPriorityAndSkipDisabledCoolingOrBlockedEndpoints() {
        val endpoints = listOf(
            endpoint("p1"),
            endpoint("p2"),
            endpoint("p3", enabled = false),
            endpoint("p4"),
        )
        val now = 1_000L
        val health = mapOf(
            "p1" to ModelEndpointHealth(cooldownUntil = now + 30_000),
            "p4" to ModelEndpointHealth(blockedReason = "HTTP 401"),
        )

        assertEquals(
            listOf("p2"),
            ModelFailoverPolicy.orderedCandidates(endpoints, health, now).map { it.id },
        )
        assertEquals(30L, ModelFailoverPolicy.secondsUntilNextCandidate(endpoints, health, now))
    }

    @Test
    fun reorderMovesOneEndpointWithoutDroppingOthers() {
        val endpoints = listOf(endpoint("p1"), endpoint("p2"), endpoint("p3"))

        assertEquals(
            listOf("p2", "p3", "p1"),
            ModelEndpointRules.reordered(endpoints, "p1", 2).map { it.id },
        )
        assertEquals(endpoints, ModelEndpointRules.reordered(endpoints, "missing", 1))
    }

    @Test
    fun temporaryCooldownBacksOffAndRateLimitHonorsRetryAfter() {
        assertEquals(30_000L, ModelFailoverPolicy.cooldownMs(ModelFailureType.TEMPORARY, 1))
        assertEquals(60_000L, ModelFailoverPolicy.cooldownMs(ModelFailureType.TEMPORARY, 2))
        assertEquals(10 * 60_000L, ModelFailoverPolicy.cooldownMs(ModelFailureType.TEMPORARY, 20))
        assertEquals(
            90_000L,
            ModelFailoverPolicy.cooldownMs(ModelFailureType.RATE_LIMIT, 1, retryAfterMs = 90_000L),
        )
        assertEquals(0L, ModelFailoverPolicy.cooldownMs(ModelFailureType.CONFIGURATION, 1))
    }

    @Test
    fun migrationKeepsSelectedFirstAndMakesExistingNebulaFallbackVisible() {
        val migrated = ModelEndpointMigration.build(
            selectedName = "中转站",
            configs = ModelList.MODELS,
            keyFor = { name ->
                when (name) {
                    "中转站" -> "nebula-key"
                    "DeepSeek" -> "deepseek-key"
                    else -> ""
                }
            },
            customBaseUrl = "https://apinebula.ai/v1",
            customModel = "gpt-5.6-sol",
        )

        assertEquals("gpt-5.6-sol", migrated[0].model)
        assertTrue(migrated[0].enabled)
        assertEquals("gpt-5.6-terra", migrated[1].model)
        assertTrue(migrated[1].enabled)
        assertEquals("DeepSeek", migrated[2].providerName)
        assertTrue(!migrated[2].enabled)
    }
}
