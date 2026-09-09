package com.soulbot.app

import android.content.Context

object Prefs {
    private const val PREFS = "soulbot_prefs"
    private const val KEY_SELECTED_MODEL = "selected_model"

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
}
