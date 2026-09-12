package com.soulbot.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

enum class SoulUiCapability(
    val key: String,
    val label: String,
) {
    BOTTOM_NAV("bottom_nav", "底部导航"),
    CHAT_LIST("chat_list", "聊天列表"),
    CHAT_INPUT("chat_input", "聊天输入框"),
    SEND_BUTTON("send_button", "发送按钮"),
    SQUARE("square", "广场列表"),
    LOCAL_CHANNEL("local_channel", "同城频道"),
    SOUL_MATCH("soul_match", "灵魂匹配"),
    VOICE_MESSAGE("voice_message", "语音消息"),
    QIYU("qiyu", "奇遇铃"),
    PROFILE_DM("profile_dm", "主页私聊"),
    ;

    companion object {
        fun fromKey(key: String): SoulUiCapability? = entries.firstOrNull { it.key == key }
    }
}

data class DeviceProfileSnapshot(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val densityDpi: Int,
    val density: Float,
    val fontScale: Float,
    val orientation: String,
    val soulVersion: String,
) {
    val deviceKey: String
        get() = listOf(
            manufacturer,
            model,
            sdk,
            displayWidthPx,
            displayHeightPx,
            densityDpi,
            fontScale,
            soulVersion,
        ).joinToString("|").hashCode().toUInt().toString(16)

    fun displayName(): String = listOf(manufacturer, model)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { "未知设备" }

    companion object {
        fun capture(context: Context): DeviceProfileSnapshot {
            val metrics = context.resources.displayMetrics
            val config = context.resources.configuration
            val orientation = when (config.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> "横屏"
                Configuration.ORIENTATION_PORTRAIT -> "竖屏"
                else -> "未知"
            }
            val soulVersion = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SoulBotService.SOUL_PACKAGE, 0).versionName
            }.getOrNull().orEmpty().ifBlank { "未安装或无法读取" }
            return DeviceProfileSnapshot(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                androidVersion = Build.VERSION.RELEASE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                displayWidthPx = metrics.widthPixels,
                displayHeightPx = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                density = metrics.density,
                fontScale = config.fontScale,
                orientation = orientation,
                soulVersion = soulVersion,
            )
        }
    }
}

data class SoulUiDiagnosticSnapshot(
    val capturedAt: Long,
    val page: String,
    val rootBounds: UiRegion,
    val effectiveBounds: UiRegion,
    val visibleNodeCount: Int,
    val capabilities: Set<SoulUiCapability>,
)

data class DeviceCompatibilityState(
    val latest: SoulUiDiagnosticSnapshot?,
    val observedCapabilities: Set<SoulUiCapability>,
)

object DeviceCompatibilityStore {
    private const val PREFS = "device_compatibility"

    @Synchronized
    fun record(context: Context, snapshot: SoulUiDiagnosticSnapshot) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = DeviceProfileSnapshot.capture(context).deviceKey
        val observed = readCapabilityKeys(prefs.getString("observed_$key", "[]"))
            .toMutableSet()
            .apply { addAll(snapshot.capabilities) }
        prefs.edit()
            .putString("latest_$key", encode(snapshot).toString())
            .putString("observed_$key", JSONArray(observed.map { it.key }).toString())
            .apply()
    }

    fun read(context: Context): DeviceCompatibilityState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = DeviceProfileSnapshot.capture(context).deviceKey
        val latest = prefs.getString("latest_$key", null)?.let(::decode)
        val observed = readCapabilityKeys(prefs.getString("observed_$key", "[]"))
        return DeviceCompatibilityState(latest, observed)
    }

    @Synchronized
    fun recordVoiceMenuOffset(
        context: Context,
        voiceBounds: UiRegion,
        menuBounds: UiRegion,
        activeArea: UiRegion,
    ) {
        val offset = AdaptiveUiPolicy.relativeOffset(voiceBounds, menuBounds, activeArea) ?: return
        val key = DeviceProfileSnapshot.capture(context).deviceKey
        val value = JSONObject().apply {
            put("horizontal", offset.horizontal.toDouble())
            put("vertical", offset.vertical.toDouble())
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("voice_menu_offset_$key", value.toString())
            .apply()
    }

    fun learnedVoiceMenuPoint(
        context: Context,
        voiceBounds: UiRegion,
        activeArea: UiRegion,
        edgeInset: Int,
    ): UiPoint? {
        val key = DeviceProfileSnapshot.capture(context).deviceKey
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("voice_menu_offset_$key", null)
            ?: return null
        val offset = runCatching {
            val json = JSONObject(raw)
            UiOffsetRatio(
                horizontal = json.getDouble("horizontal").toFloat(),
                vertical = json.getDouble("vertical").toFloat(),
            )
        }.getOrNull() ?: return null
        return AdaptiveUiPolicy.pointFromOffset(voiceBounds, offset, activeArea, edgeInset)
    }

    fun hasLearnedVoiceMenuOffset(context: Context): Boolean {
        val key = DeviceProfileSnapshot.capture(context).deviceKey
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains("voice_menu_offset_$key")
    }

    @Synchronized
    fun clearCurrentDevice(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = DeviceProfileSnapshot.capture(context).deviceKey
        prefs.edit()
            .remove("latest_$key")
            .remove("observed_$key")
            .remove("voice_menu_offset_$key")
            .apply()
    }

    private fun encode(snapshot: SoulUiDiagnosticSnapshot): JSONObject = JSONObject().apply {
        put("capturedAt", snapshot.capturedAt)
        put("page", snapshot.page)
        put("rootBounds", encodeRegion(snapshot.rootBounds))
        put("effectiveBounds", encodeRegion(snapshot.effectiveBounds))
        put("visibleNodeCount", snapshot.visibleNodeCount)
        put("capabilities", JSONArray(snapshot.capabilities.map { it.key }))
    }

    private fun decode(raw: String): SoulUiDiagnosticSnapshot? = runCatching {
        val json = JSONObject(raw)
        SoulUiDiagnosticSnapshot(
            capturedAt = json.optLong("capturedAt"),
            page = json.optString("page", "未知页面"),
            rootBounds = decodeRegion(json.optJSONObject("rootBounds")),
            effectiveBounds = decodeRegion(json.optJSONObject("effectiveBounds")),
            visibleNodeCount = json.optInt("visibleNodeCount"),
            capabilities = readCapabilityKeys(json.optJSONArray("capabilities")?.toString()),
        )
    }.getOrNull()

    private fun encodeRegion(region: UiRegion): JSONObject = JSONObject().apply {
        put("left", region.left)
        put("top", region.top)
        put("right", region.right)
        put("bottom", region.bottom)
    }

    private fun decodeRegion(json: JSONObject?): UiRegion = UiRegion(
        left = json?.optInt("left") ?: 0,
        top = json?.optInt("top") ?: 0,
        right = json?.optInt("right") ?: 0,
        bottom = json?.optInt("bottom") ?: 0,
    )

    private fun readCapabilityKeys(raw: String?): Set<SoulUiCapability> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildSet {
            for (index in 0 until array.length()) {
                SoulUiCapability.fromKey(array.optString(index))?.let(::add)
            }
        }
    }.getOrDefault(emptySet())
}
