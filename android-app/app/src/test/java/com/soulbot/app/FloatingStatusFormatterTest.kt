package com.soulbot.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingStatusFormatterTest {
    @Test
    fun running_countdown_names_the_next_action_and_rounds_up_seconds() {
        assertEquals(
            "下一步：回复 小明 · 3 秒后",
            FloatingStatusFormatter.detail(
                running = true,
                serviceAvailable = true,
                statusString = "回复 小明（思考中）",
                deadline = 12_001L,
                now = 10_000L,
            ),
        )
    }

    @Test
    fun immediate_action_is_shown_as_zero_seconds() {
        assertEquals(
            "下一步：检查未读消息 · 0 秒后",
            FloatingStatusFormatter.detail(true, true, "检查未读消息", 0L, 10_000L),
        )
    }

    @Test
    fun stopped_and_unavailable_states_do_not_pretend_to_have_a_next_action() {
        assertEquals("点击启动", FloatingStatusFormatter.detail(false, true, "已停止", 0L, 0L))
        assertEquals(
            "请先开启无障碍服务",
            FloatingStatusFormatter.detail(false, false, "已停止", 0L, 0L),
        )
    }

    @Test
    fun stopped_state_keeps_the_failure_reason_visible() {
        assertEquals(
            "任务异常退出；点击启动",
            FloatingStatusFormatter.detail(false, true, "任务异常退出", 0L, 0L),
        )
    }
}
