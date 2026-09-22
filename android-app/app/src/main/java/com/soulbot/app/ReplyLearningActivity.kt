package com.soulbot.app

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout

class ReplyLearningActivity : BaseSettingsActivity() {
    private lateinit var learningDb: ChatLearningDatabase
    private lateinit var tvStats: TextView
    private lateinit var tvStyleProfile: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reply_learning)
        setupToolbar("回复与学习")
        learningDb = ChatLearningDatabase(applicationContext)
        tvStats = findViewById(R.id.tvLearningStats)
        tvStyleProfile = findViewById(R.id.tvStyleProfile)
        val styleSwitch = findViewById<MaterialSwitch>(R.id.switchStyleLearning)
        val memorySwitch = findViewById<MaterialSwitch>(R.id.switchContactMemory)
        styleSwitch.isChecked = Prefs.getStyleLearningEnabled(this)
        memorySwitch.isChecked = Prefs.getContactMemoryEnabled(this)
        styleSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setStyleLearningEnabled(this, checked)
            refreshStats()
        }
        memorySwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setContactMemoryEnabled(this, checked)
            refreshStats()
        }
        findViewById<android.view.View>(R.id.rowChatSamples).setOnClickListener {
            startActivity(Intent(this, ChatSamplesActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnFeedbackLike).setOnClickListener {
            readFeedbackDraft()?.let { submitFeedback(it, liked = true) }
        }
        findViewById<android.view.View>(R.id.btnFeedbackDislike).setOnClickListener {
            readFeedbackDraft()?.let(::showDislikeReasons)
        }
        findViewById<EditText>(R.id.etFeedbackIncoming).doAfterTextChanged {
            findViewById<TextInputLayout>(R.id.layoutFeedbackIncoming).error = null
        }
        findViewById<EditText>(R.id.etFeedbackReply).doAfterTextChanged {
            findViewById<TextInputLayout>(R.id.layoutFeedbackReply).error = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (::learningDb.isInitialized) refreshStats()
    }

    override fun onDestroy() {
        learningDb.close()
        super.onDestroy()
    }

    private fun refreshStats() {
        val style = if (Prefs.getStyleLearningEnabled(this)) "开启" else "关闭"
        val memory = if (Prefs.getContactMemoryEnabled(this)) "开启" else "关闭"
        val profile = learningDb.styleProfile()
        val feedback = learningDb.feedbackSummary()
        tvStats.text =
            "风格学习 $style · ${learningDb.styleSampleCount()} 条样本\n" +
                "记忆引用 $memory · ${learningDb.contactMemoryCount()} 条旧版摘要\n" +
                "反馈：像我 ${feedback.positive} · 不像我 ${feedback.negative} · " +
                "质量拒绝 ${feedback.qualityRejected}"
        tvStyleProfile.text = if (profile.sampleCount == 0) {
            "还没有足够的人工样本。导入至少 8 组真实对话后，系统才会启用更强的个人风格约束。"
        } else {
            profile.promptLine() + "\n" + profile.compactStyleHints()
        }
    }

    private data class FeedbackDraft(
        val contact: String,
        val incoming: String,
        val reply: String,
        val scene: ReplyScene,
    )

    private fun readFeedbackDraft(): FeedbackDraft? {
        val contact = findViewById<EditText>(R.id.etFeedbackContact).text.toString()
            .trim().ifEmpty { "手动反馈" }
        val incomingField = findViewById<EditText>(R.id.etFeedbackIncoming)
        val replyField = findViewById<EditText>(R.id.etFeedbackReply)
        val incoming = incomingField.text.toString().trim()
        val reply = replyField.text.toString().trim()
        findViewById<TextInputLayout>(R.id.layoutFeedbackIncoming).error =
            if (incoming.isEmpty()) "请填写对方消息，或本次开场依据" else null
        findViewById<TextInputLayout>(R.id.layoutFeedbackReply).error =
            if (reply.isEmpty()) "请填写要评价的回复" else null
        if (incoming.isEmpty() || reply.isEmpty()) {
            (if (incoming.isEmpty()) incomingField else replyField).requestFocus()
            showMessage("请补充标出的必填内容", Snackbar.LENGTH_LONG)
            return null
        }
        val scene = when (findViewById<ChipGroup>(R.id.chipGroupFeedbackScene).checkedChipId) {
            R.id.chipSceneSoulMatch -> ReplyScene.SOUL_MATCH
            R.id.chipSceneQiyu -> ReplyScene.QIYU
            R.id.chipSceneSquare -> ReplyScene.SQUARE
            else -> ReplyScene.CHAT
        }
        return FeedbackDraft(contact, incoming, reply, scene)
    }

    private fun showDislikeReasons(draft: FeedbackDraft) {
        val reasons = arrayOf("昵称硬聊", "同城套话", "连续盘问", "客服腔", "编造经历")
        val selected = BooleanArray(reasons.size)
        MaterialAlertDialogBuilder(this)
            .setTitle("哪里不像你？（可多选，也可不选）")
            .setMultiChoiceItems(reasons, selected) { _, index, checked ->
                selected[index] = checked
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存反馈") { _, _ ->
                val note = reasons.filterIndexed { index, _ -> selected[index] }.joinToString("、")
                submitFeedback(draft, liked = false, note = note)
            }
            .show()
    }

    private fun submitFeedback(draft: FeedbackDraft, liked: Boolean, note: String = "") {
        val saved = learningDb.recordReplyFeedback(
            draft.contact, draft.incoming, draft.reply, liked, note = note, scene = draft.scene,
        )
        if (!saved) {
            showMessage("这条反馈已提交，无需重复操作", Snackbar.LENGTH_LONG)
            return
        }
        findViewById<EditText>(R.id.etFeedbackIncoming).text.clear()
        findViewById<EditText>(R.id.etFeedbackReply).text.clear()
        refreshStats()
        showMessage(if (liked) "已按所选场景加入人工风格样本" else "已保存差评反馈，后续生成会参考")
    }
}
