package com.soulbot.app

import kotlin.math.ceil

object FloatingStatusFormatter {
    private val blockingStatuses = setOf("未配置 API Key", "模型配置不完整")

    fun title(running: Boolean, serviceAvailable: Boolean, statusString: String): String = when {
        !serviceAvailable -> "尚未就绪"
        running -> "运行中"
        statusString in blockingStatuses -> "无法启动"
        else -> "已停止"
    }

    fun detail(
        running: Boolean,
        serviceAvailable: Boolean,
        statusString: String,
        deadline: Long,
        now: Long,
    ): String {
        if (!serviceAvailable) return "请先开启无障碍服务"
        if (!running) {
            return when {
                statusString in blockingStatuses -> statusString
                statusString.isBlank() || statusString == "已停止" || statusString == "用户手动停止" -> "点击启动"
                else -> "$statusString；点击启动"
            }
        }

        val action = statusString
            .removeSuffix("（思考中）")
            .takeUnless { it.isBlank() || it == "运行中" }
            ?: "检查当前页面"
        val seconds = if (deadline > now) {
            ceil((deadline - now) / 1000.0).toLong()
        } else {
            0L
        }
        return "下一步：" + action + " · " + seconds + " 秒后"
    }
}
