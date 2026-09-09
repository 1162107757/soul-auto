package com.soulbot.app

data class ModelConfig(
    val name: String,      // 显示名
    val baseUrl: String,   // API 地址（OpenAI 兼容）
    val model: String,     // 模型名
    val isCustom: Boolean = false,  // 是否自定义中转站
)

object ModelList {
    val MODELS = listOf(
        ModelConfig("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
        ModelConfig("智谱GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        ModelConfig("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        ModelConfig("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-turbo"),
        ModelConfig("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        ModelConfig("中转站", "", "", isCustom = true),
    )

    fun findByName(name: String): ModelConfig =
        MODELS.firstOrNull { it.name == name } ?: MODELS[0]
}
