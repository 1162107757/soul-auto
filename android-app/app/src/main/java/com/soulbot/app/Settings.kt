package com.soulbot.app

import android.content.Context

enum class IdleSurface {
    SOUL_PLANET,
    LOCAL_SQUARE,
}

enum class AutomationMode(
    val displayName: String,
    val handlesChatReplies: Boolean,
    val handlesSoulMatch: Boolean,
    val handlesSquareDm: Boolean,
    val idleSurface: IdleSurface,
) {
    PLANET_CHAT("星球与聊天", true, true, false, IdleSurface.SOUL_PLANET),
    SQUARE_DM("广场与私聊", true, false, true, IdleSurface.LOCAL_SQUARE),
}

object Prefs {
    private const val PREFS = "soulbot_prefs"
    private const val KEY_SELECTED_MODEL = "selected_model"
    private const val KEY_AUTOMATION_MODE = "automation_mode"
    private const val KEY_TASK_SHOULD_RUN = "task_should_run"
    private const val KEY_LAST_STOP_REASON = "last_stop_reason"
    private const val KEY_SELF_GENDER = "self_gender"
    private const val KEY_SELF_AGE = "self_age"
    private const val KEY_SELF_REGION = "self_region"
    private const val KEY_SELF_ZODIAC = "self_zodiac"
    private const val KEY_SELF_OCCUPATION = "self_occupation"
    private const val KEY_SELF_DETAILS = "self_details"

    fun getSelectedModel(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_MODEL, "DeepSeek") ?: "DeepSeek"

    fun setSelectedModel(ctx: Context, model: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_MODEL, model).apply()
    }

    fun getApiKey(ctx: Context, model: String): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString("key_$model", "") ?: ""
        if (key.isNotEmpty()) return key
        // 兼容旧版：DeepSeek 的 key 原来存成 deepseek_api_key
        if (model == "DeepSeek") {
            return prefs.getString("deepseek_api_key", "") ?: ""
        }
        return ""
    }

    fun setApiKey(ctx: Context, model: String, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("key_$model", key.trim()).apply()
    }

    fun getCustomBaseUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("custom_base_url", "") ?: ""

    fun setCustomBaseUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("custom_base_url", url.trim()).apply()
    }

    fun getCustomModel(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("custom_model", "") ?: ""

    fun setCustomModel(ctx: Context, model: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("custom_model", model.trim()).apply()
    }

    private fun getInt(ctx: Context, key: String, def: Int): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, def)

    private fun setInt(ctx: Context, key: String, value: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(key, value).apply()
    }

    fun getAutomationMode(ctx: Context): AutomationMode {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AUTOMATION_MODE, AutomationMode.PLANET_CHAT.name)
        return runCatching { AutomationMode.valueOf(stored.orEmpty()) }
            .getOrDefault(AutomationMode.PLANET_CHAT)
    }

    fun setAutomationMode(ctx: Context, mode: AutomationMode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUTOMATION_MODE, mode.name).apply()
    }

    fun getTaskShouldRun(ctx: Context): Boolean =
        getBoolean(ctx, KEY_TASK_SHOULD_RUN, false)

    fun setTaskShouldRun(ctx: Context, shouldRun: Boolean) =
        setBoolean(ctx, KEY_TASK_SHOULD_RUN, shouldRun)

    fun getLastStopReason(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STOP_REASON, "已停止") ?: "已停止"

    fun setLastStopReason(ctx: Context, reason: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_STOP_REASON, reason).apply()
    }

    private fun getBoolean(ctx: Context, key: String, def: Boolean): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, def)

    private fun setBoolean(ctx: Context, key: String, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    fun getSelfProfile(ctx: Context): SelfProfile {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SelfProfile(
            gender = prefs.getString(KEY_SELF_GENDER, "男") ?: "男",
            age = prefs.getString(KEY_SELF_AGE, "25") ?: "25",
            region = prefs.getString(KEY_SELF_REGION, "") ?: "",
            zodiac = prefs.getString(KEY_SELF_ZODIAC, "") ?: "",
            occupation = prefs.getString(KEY_SELF_OCCUPATION, "") ?: "",
            details = prefs.getString(KEY_SELF_DETAILS, "") ?: "",
        )
    }

    fun setSelfProfile(ctx: Context, profile: SelfProfile) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SELF_GENDER, profile.gender.trim())
            .putString(KEY_SELF_AGE, profile.age.trim())
            .putString(KEY_SELF_REGION, profile.region.trim())
            .putString(KEY_SELF_ZODIAC, profile.zodiac.trim())
            .putString(KEY_SELF_OCCUPATION, profile.occupation.trim())
            .putString(KEY_SELF_DETAILS, profile.details.trim())
            .apply()
    }

    // 广场私信间隔（秒）
    fun getSquareInterval(ctx: Context) = getInt(ctx, "square_interval", 240)
    fun setSquareInterval(ctx: Context, v: Int) = setInt(ctx, "square_interval", v)

    // 回复延迟范围（秒）
    fun getThinkMin(ctx: Context) = getInt(ctx, "think_min", 5)
    fun setThinkMin(ctx: Context, v: Int) = setInt(ctx, "think_min", v)

    fun getThinkMax(ctx: Context) = getInt(ctx, "think_max", 15)
    fun setThinkMax(ctx: Context, v: Int) = setInt(ctx, "think_max", v)

    // 打招呼去重间隔（小时）
    fun getGreetIntervalHours(ctx: Context) = getInt(ctx, "greet_interval_hours", 24)
    fun setGreetIntervalHours(ctx: Context, v: Int) = setInt(ctx, "greet_interval_hours", v)

    // 本地聊天风格学习与联系人记忆
    fun getStyleLearningEnabled(ctx: Context) = getBoolean(ctx, "style_learning_enabled", true)
    fun setStyleLearningEnabled(ctx: Context, enabled: Boolean) =
        setBoolean(ctx, "style_learning_enabled", enabled)

    fun getContactMemoryEnabled(ctx: Context) = getBoolean(ctx, "contact_memory_enabled", true)
    fun setContactMemoryEnabled(ctx: Context, enabled: Boolean) =
        setBoolean(ctx, "contact_memory_enabled", enabled)
}
