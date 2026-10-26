package com.soulbot.app

import android.content.Context
import java.util.concurrent.CancellationException

typealias ReplayCancellation = ModelCallCancellation

/** Two bounded, explicitly selected calls; no failover, endpoint-health changes, or device actions. */
class ReplyReplayRunner(context: Context) {
    private val appContext = context.applicationContext

    fun generate(
        case: ReplyReplayCase,
        endpointA: ModelEndpoint,
        endpointB: ModelEndpoint?,
        compareStrategies: Boolean,
        cancellation: ReplayCancellation,
        onProgress: (String) -> Unit = {},
    ): Pair<ReplayCandidate, ReplayCandidate> {
        val started = System.nanoTime()
        ReplyReplayLogic.validationError(case)?.let { throw IllegalArgumentException(it) }
        require(case.turns.lastOrNull()?.role == "in") { "请选择截止到对方消息的片段，不包含待生成的回复" }
        ModelEndpointRules.validationError(endpointA)?.let { throw IllegalArgumentException(it) }
        val second = if (compareStrategies) endpointA else requireNotNull(endpointB) { "请选择第二个模型" }
        ModelEndpointRules.validationError(second)?.let { throw IllegalArgumentException(it) }
        require(compareStrategies || endpointA.id != second.id) { "模型对比需要两个不同配置" }
        cancellation.throwIfCancelled()

        // Freeze both inputs before any call. A replay does not retrieve current/future contact memory.
        val profile = Prefs.getSelfProfile(appContext)
        val examples = if (Prefs.getStyleLearningEnabled(appContext)) {
            val db = ReplyReplayDatabase(appContext)
            try {
                db.relevantExamples(
                    ReplyReplayLogic.formatTranscript(case.turns), case.scene, 3,
                    ReplyReplayLogic.contextKey(case.turns, case.scene),
                    excludeContact = case.contact,
                    excludeTurns = case.turns,
                    checkCancelled = cancellation::throwIfCancelled,
                )
            } finally {
                db.close()
            }
        } else emptyList()
        cancellation.throwIfCancelled()
        val baseline = ReplyReplayPrompt.build(case, profile, emptyList(), personalized = false)
        val personalized = ReplyReplayPrompt.build(case, profile, examples, personalized = true)
        // Randomize generation order as well as display order. Source is revealed only after a vote.
        val tasks = listOf(
            Triple(endpointA, if (compareStrategies) baseline else personalized,
                if (compareStrategies) ReplyReplayPrompt.BASELINE else ReplyReplayPrompt.PERSONALIZED),
            Triple(second, personalized, ReplyReplayPrompt.PERSONALIZED),
        ).shuffled()
        val candidates = tasks.mapIndexed { index, (endpoint, prompt, strategy) ->
            cancellation.throwIfCancelled()
            onProgress("正在生成候选 ${index + 1}/2；不会向 Soul 发送消息")
            val remaining = TOTAL_BUDGET_MS - (System.nanoTime() - started) / 1_000_000
            val label = endpoint.shortLabel().let {
                if (Regex("https?://|api.?key|authorization|bearer|\\bsk-", RegexOption.IGNORE_CASE)
                        .containsMatchIn(it)) "选定模型" else it.take(75)
            }
            val source = "$label · $strategy"
            if (remaining < 5_000) {
                ReplayCandidate("", source, error = "本轮回放已达到时间上限，请稍后重试")
            } else {
                val result = ModelClient.chatOnce(
                    endpoint.apiKey, endpoint.baseUrl, endpoint.model, prompt.system, prompt.messages,
                    timeoutMs = minOf(30_000L, remaining), cancellation = cancellation,
                )
                cancellation.throwIfCancelled()
                val generated = result.content?.let(ReplyNaturalness::sanitize).orEmpty()
                val error = if (generated.length > ReplyReplayLogic.MAX_REPLY_CHARS) {
                    "模型返回过长（超过 ${ReplyReplayLogic.MAX_REPLY_CHARS} 字），请调整模型或稍后重试"
                } else if (generated.isBlank()) {
                    // Never put raw provider error bodies (possibly containing credentials) in exports.
                    result.httpStatus?.let { "调用失败（HTTP $it），请检查模型配置后重试" }
                        ?: "调用失败或未返回文字，请检查网络与模型配置后重试"
                } else ""
                val text = if (error.isEmpty()) generated else ""
                RuntimeLog.record(appContext,
                    "reply replay candidate; slot=${index + 1}; success=${error.isEmpty()}; " +
                        "durationMs=${result.durationMs}; learnedExamples=${examples.size}")
                ReplayCandidate(text, source, result.durationMs, error)
            }
        }
        if (cancellation.isCancelled()) throw CancellationException("回放已取消")
        return candidates[0] to candidates[1]
    }

    companion object {
        private const val TOTAL_BUDGET_MS = 65_000L
    }
}
