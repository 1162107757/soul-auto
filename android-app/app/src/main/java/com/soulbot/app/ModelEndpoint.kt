package com.soulbot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import kotlin.math.min

data class ModelEndpoint(
    val id: String,
    val displayName: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val enabled: Boolean,
) {
    fun normalized(): ModelEndpoint = copy(
        displayName = displayName.trim(),
        providerName = providerName.trim(),
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        apiKey = apiKey.trim(),
    )

    fun shortLabel(): String = "${displayName.ifBlank { providerName }} / $model"
}

data class ModelEndpointHealth(
    val consecutiveFailures: Int = 0,
    val qualityFailures: Int = 0,
    val cooldownUntil: Long = 0,
    val blockedReason: String = "",
    val lastError: String = "",
    val lastSuccessAt: Long = 0,
)

object ModelEndpointRules {
    const val MAX_ENDPOINTS = 5

    fun validationError(endpoint: ModelEndpoint): String? {
        val value = endpoint.normalized()
        if (value.displayName.isBlank()) return "请填写配置名称"
        if (value.displayName.length > 30) return "配置名称不能超过 30 个字"
        if (value.baseUrl.isBlank()) return "请填写接口地址"
        if (value.model.isBlank()) return "请填写模型名称"
        if (value.apiKey.isBlank()) return "请填写 API Key"
        val uri = runCatching { URI(value.baseUrl) }.getOrNull()
            ?: return "接口地址格式不正确"
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "接口地址必须是完整的 http 或 https 地址"
        }
        if (value.baseUrl.contains("/chat/completions", ignoreCase = true)) {
            return "请填写 API 根地址，不要包含 /chat/completions"
        }
        return null
    }

    fun isRunnable(endpoint: ModelEndpoint): Boolean =
        endpoint.enabled && validationError(endpoint) == null

    fun reordered(
        endpoints: List<ModelEndpoint>,
        endpointId: String,
        targetIndex: Int,
    ): List<ModelEndpoint> {
        val result = endpoints.toMutableList()
        val sourceIndex = result.indexOfFirst { it.id == endpointId }
        if (sourceIndex < 0 || targetIndex !in result.indices || sourceIndex == targetIndex) {
            return endpoints
        }
        val endpoint = result.removeAt(sourceIndex)
        result.add(targetIndex, endpoint)
        return result
    }
}

object ModelFailoverPolicy {
    private const val MAX_COOLDOWN_MS = 10 * 60_000L

    fun orderedCandidates(
        endpoints: List<ModelEndpoint>,
        health: Map<String, ModelEndpointHealth>,
        now: Long,
    ): List<ModelEndpoint> = endpoints.filter { endpoint ->
        if (!ModelEndpointRules.isRunnable(endpoint)) return@filter false
        val state = health[endpoint.id] ?: ModelEndpointHealth()
        state.blockedReason.isBlank() && state.cooldownUntil <= now
    }

    fun cooldownMs(
        failureType: ModelFailureType,
        consecutiveFailures: Int,
        retryAfterMs: Long = 0,
    ): Long = when (failureType) {
        ModelFailureType.CONFIGURATION -> 0
        ModelFailureType.RATE_LIMIT -> retryAfterMs.takeIf { it > 0 } ?: 60_000L
        ModelFailureType.TEMPORARY -> {
            val shift = (consecutiveFailures - 1).coerceIn(0, 5)
            min(30_000L * (1L shl shift), MAX_COOLDOWN_MS)
        }
    }

    fun secondsUntilNextCandidate(
        endpoints: List<ModelEndpoint>,
        health: Map<String, ModelEndpointHealth>,
        now: Long,
    ): Long? = endpoints
        .filter(ModelEndpointRules::isRunnable)
        .mapNotNull { endpoint ->
            val state = health[endpoint.id] ?: return@mapNotNull null
            state.cooldownUntil.takeIf { state.blockedReason.isBlank() && it > now }
        }
        .minOrNull()
        ?.let { ((it - now) + 999) / 1000 }
}

object ModelEndpointMigration {
    fun build(
        selectedName: String,
        configs: List<ModelConfig>,
        keyFor: (String) -> String,
        customBaseUrl: String,
        customModel: String,
    ): List<ModelEndpoint> {
        val selectedConfig = configs.firstOrNull { it.name == selectedName } ?: configs.first()
        val selected = endpointFrom(
            id = "legacy-primary",
            config = selectedConfig,
            apiKey = keyFor(selectedConfig.name),
            enabled = true,
            customBaseUrl = customBaseUrl,
            customModel = customModel,
        )
        val result = mutableListOf(selected)

        if (selected.baseUrl.contains("apinebula.ai", ignoreCase = true) &&
            selected.model == "gpt-5.6-sol" && selected.apiKey.isNotBlank()
        ) {
            result += selected.copy(
                id = "legacy-apinebula-terra",
                displayName = "中转站备用",
                model = "gpt-5.6-terra",
            )
        }

        configs.asSequence()
            .filter { it.name != selectedConfig.name }
            .mapNotNull { config ->
                val key = keyFor(config.name)
                if (key.isBlank()) null else endpointFrom(
                    id = "legacy-${config.name.hashCode().toUInt()}",
                    config = config,
                    apiKey = key,
                    enabled = false,
                    customBaseUrl = customBaseUrl,
                    customModel = customModel,
                )
            }
            .take(ModelEndpointRules.MAX_ENDPOINTS - result.size)
            .forEach(result::add)
        return result.take(ModelEndpointRules.MAX_ENDPOINTS)
    }

    private fun endpointFrom(
        id: String,
        config: ModelConfig,
        apiKey: String,
        enabled: Boolean,
        customBaseUrl: String,
        customModel: String,
    ): ModelEndpoint = ModelEndpoint(
        id = id,
        displayName = config.name,
        providerName = config.name,
        baseUrl = if (config.isCustom) customBaseUrl else config.baseUrl,
        model = if (config.isCustom) customModel else config.model,
        apiKey = apiKey,
        enabled = enabled,
    ).normalized()
}

object ModelEndpointPrefs {
    private const val PREFS = "soulbot_prefs"
    private const val KEY_ENDPOINTS = "model_endpoints_v1"
    private const val KEY_HEALTH = "model_endpoint_health_v1"

    @Synchronized
    fun getEndpoints(ctx: Context): List<ModelEndpoint> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ENDPOINTS, null)
        if (stored != null) return decodeEndpoints(stored)

        val migrated = ModelEndpointMigration.build(
            selectedName = Prefs.getSelectedModel(ctx),
            configs = ModelList.MODELS,
            keyFor = { Prefs.getApiKey(ctx, it) },
            customBaseUrl = Prefs.getCustomBaseUrl(ctx),
            customModel = Prefs.getCustomModel(ctx),
        )
        saveEndpoints(ctx, migrated)
        return migrated
    }

    @Synchronized
    fun saveEndpoints(ctx: Context, endpoints: List<ModelEndpoint>) {
        val unique = endpoints
            .map(ModelEndpoint::normalized)
            .distinctBy(ModelEndpoint::id)
            .take(ModelEndpointRules.MAX_ENDPOINTS)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENDPOINTS, encodeEndpoints(unique))
            .apply()
        pruneHealth(ctx, unique.mapTo(mutableSetOf(), ModelEndpoint::id))
    }

    fun newEndpoint(provider: ModelConfig = ModelList.MODELS.first()): ModelEndpoint =
        ModelEndpoint(
            id = UUID.randomUUID().toString(),
            displayName = provider.name,
            providerName = provider.name,
            baseUrl = provider.baseUrl,
            model = provider.model,
            apiKey = "",
            enabled = false,
        )

    @Synchronized
    fun getHealth(ctx: Context): Map<String, ModelEndpointHealth> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HEALTH, "{}").orEmpty()
        return decodeHealth(raw)
    }

    @Synchronized
    fun clearHealth(ctx: Context, endpointId: String) {
        val health = getHealth(ctx).toMutableMap()
        health.remove(endpointId)
        saveHealth(ctx, health)
    }

    @Synchronized
    fun recordSuccess(ctx: Context, endpointId: String, accepted: Boolean = false) {
        val health = getHealth(ctx).toMutableMap()
        val previous = health[endpointId] ?: ModelEndpointHealth()
        health[endpointId] = previous.copy(
            consecutiveFailures = 0,
            qualityFailures = if (accepted) 0 else previous.qualityFailures,
            cooldownUntil = 0,
            blockedReason = "",
            lastError = "",
            lastSuccessAt = System.currentTimeMillis(),
        )
        saveHealth(ctx, health)
    }

    @Synchronized
    fun recordFailure(ctx: Context, endpointId: String, result: ModelCallResult) {
        val now = System.currentTimeMillis()
        val health = getHealth(ctx).toMutableMap()
        val previous = health[endpointId] ?: ModelEndpointHealth()
        val failures = previous.consecutiveFailures + 1
        val blocked = if (result.failureType == ModelFailureType.CONFIGURATION) {
            result.error.ifBlank { "配置异常" }
        } else {
            ""
        }
        val cooldown = if (blocked.isBlank()) {
            now + ModelFailoverPolicy.cooldownMs(
                result.failureType,
                failures,
                result.retryAfterMs,
            )
        } else {
            0
        }
        health[endpointId] = previous.copy(
            consecutiveFailures = failures,
            cooldownUntil = cooldown,
            blockedReason = blocked,
            lastError = result.error,
        )
        saveHealth(ctx, health)
    }

    @Synchronized
    fun recordQualityRejected(ctx: Context, endpointId: String): Boolean {
        val health = getHealth(ctx).toMutableMap()
        val previous = health[endpointId] ?: ModelEndpointHealth()
        val failures = previous.qualityFailures + 1
        val shouldRotate = failures >= 2
        health[endpointId] = previous.copy(
            qualityFailures = if (shouldRotate) 0 else failures,
            cooldownUntil = if (shouldRotate) {
                maxOf(previous.cooldownUntil, System.currentTimeMillis() + 30_000L)
            } else {
                previous.cooldownUntil
            },
            lastError = if (shouldRotate) "连续生成内容不合格" else previous.lastError,
        )
        saveHealth(ctx, health)
        return shouldRotate
    }

    fun statusText(health: ModelEndpointHealth?, now: Long = System.currentTimeMillis()): String = when {
        health == null -> "未测试"
        health.blockedReason.isNotBlank() -> "需修复 · ${health.blockedReason.take(24)}"
        health.cooldownUntil > now -> "冷却中 · ${((health.cooldownUntil - now + 999) / 1000)} 秒"
        health.lastError.isNotBlank() -> "上次失败 · ${health.lastError.take(24)}"
        health.lastSuccessAt > 0 -> "最近调用成功"
        else -> "未测试"
    }

    private fun pruneHealth(ctx: Context, retainedIds: Set<String>) {
        val retained = getHealth(ctx).filterKeys(retainedIds::contains)
        saveHealth(ctx, retained)
    }

    private fun saveHealth(ctx: Context, health: Map<String, ModelEndpointHealth>) {
        val json = JSONObject()
        health.forEach { (id, value) ->
            json.put(id, JSONObject().apply {
                put("consecutiveFailures", value.consecutiveFailures)
                put("qualityFailures", value.qualityFailures)
                put("cooldownUntil", value.cooldownUntil)
                put("blockedReason", value.blockedReason)
                put("lastError", value.lastError)
                put("lastSuccessAt", value.lastSuccessAt)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HEALTH, json.toString())
            .apply()
    }

    private fun encodeEndpoints(endpoints: List<ModelEndpoint>): String = JSONArray().apply {
        endpoints.forEach { endpoint ->
            put(JSONObject().apply {
                put("id", endpoint.id)
                put("displayName", endpoint.displayName)
                put("providerName", endpoint.providerName)
                put("baseUrl", endpoint.baseUrl)
                put("model", endpoint.model)
                put("apiKey", endpoint.apiKey)
                put("enabled", endpoint.enabled)
            })
        }
    }.toString()

    private fun decodeEndpoints(raw: String): List<ModelEndpoint> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until min(array.length(), ModelEndpointRules.MAX_ENDPOINTS)) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ModelEndpoint(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        displayName = item.optString("displayName"),
                        providerName = item.optString("providerName"),
                        baseUrl = item.optString("baseUrl"),
                        model = item.optString("model"),
                        apiKey = item.optString("apiKey"),
                        enabled = item.optBoolean("enabled", false),
                    ).normalized(),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun decodeHealth(raw: String): Map<String, ModelEndpointHealth> = runCatching {
        val json = JSONObject(raw)
        buildMap {
            json.keys().forEach { id ->
                val value = json.optJSONObject(id) ?: return@forEach
                put(
                    id,
                    ModelEndpointHealth(
                        consecutiveFailures = value.optInt("consecutiveFailures"),
                        qualityFailures = value.optInt("qualityFailures"),
                        cooldownUntil = value.optLong("cooldownUntil"),
                        blockedReason = value.optString("blockedReason"),
                        lastError = value.optString("lastError"),
                        lastSuccessAt = value.optLong("lastSuccessAt"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())
}
