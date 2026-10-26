package com.soulbot.app

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Context -> blind candidates -> human review. This screen never sends a Soul message. */
class ReplayEditorActivity : BaseSettingsActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var draft = ReplyReplayCase(contact = "", turns = emptyList(), dataset = ReplayDataset.EVALUATION)
    private var transcript = ""
    private var step = 0
    private var busy = false
    private var generation: ReplayCancellation? = null
    private var generationEpoch = 0
    private var endpoints = emptyList<ModelEndpoint>()
    private var endpointAId = ""
    private var endpointBId = ""
    private var compareStrategies = true
    private var revealed = false
    private var savedFingerprint = ""
    private var recordLoadPending = false
    private lateinit var content: LinearLayout
    private var contactField: EditText? = null
    private var transcriptField: EditText? = null
    private var originalField: EditText? = null
    private var correctionField: EditText? = null
    private var reasonField: EditText? = null
    private var sceneField: Spinner? = null
    private var datasetField: Spinner? = null
    private var strategyField: Spinner? = null
    private var endpointAField: Spinner? = null
    private var endpointBField: Spinner? = null
    private var transcriptLayout: TextInputLayout? = null
    private var generateButton: MaterialButton? = null
    private val scenes = ReplyScene.values().toList()
    private val sceneLabels = listOf("聊天回复", "灵魂匹配", "奇遇铃", "广场私聊")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replay_editor)
        setupToolbar("聊天回放")
        content = findViewById(R.id.replayEditorContent)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { requestExit() }
        findViewById<View>(R.id.replayPrevious).setOnClickListener { moveTo(step - 1) }
        findViewById<View>(R.id.replayNext).setOnClickListener {
            if (step == 2) saveReview() else if (readValidContext()) moveTo(step + 1)
        }
        findViewById<View>(R.id.replayCancel).setOnClickListener { cancelGeneration() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy || step == 0) requestExit() else moveTo(step - 1)
            }
        })
        val root = findViewById<View>(R.id.replayEditorRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        if (savedInstanceState != null) restoreDraft(savedInstanceState)
        else recordLoadPending = intent.getStringExtra("replay_id") != null
        render()
        loadInitial()
    }

    private fun loadInitial() {
        val id = intent.getStringExtra("replay_id")
        val loadRecord = recordLoadPending
        work("读取本地配置…") {
            val configs = ModelEndpointPrefs.getEndpoints(applicationContext)
                .filter { ModelEndpointRules.validationError(it) == null }
            val item = if (loadRecord && id != null) ReplyReplayDatabase(applicationContext).use { it.get(id) } else null
            if (loadRecord && id != null && item == null) error("这条记录已被删除，请返回列表")
            ui {
                endpoints = configs
                if (item != null) {
                    draft = item
                    transcript = ReplyReplayLogic.formatTranscript(item.turns)
                    revealed = canRevealSaved(item)
                    savedFingerprint = fingerprint()
                    recordLoadPending = false
                }
                if (endpointAId.isBlank()) endpointAId = configs.firstOrNull()?.id.orEmpty()
                if (endpointBId.isBlank()) endpointBId = configs.getOrNull(1)?.id.orEmpty()
                render()
            }
        }
    }

    private fun render() {
        contactField = null; transcriptField = null; originalField = null
        correctionField = null; reasonField = null; sceneField = null; datasetField = null
        strategyField = null; endpointAField = null; endpointBField = null; transcriptLayout = null
        generateButton = null
        content.removeAllViews()
        findViewById<TextView>(R.id.replayStepTitle).text = listOf("1 / 3 · 选择上下文", "2 / 3 · 生成候选", "3 / 3 · 评价与改写")[step]
        when (step) {
            0 -> renderContext()
            1 -> renderCandidates()
            else -> renderReview()
        }
        val nextButton = findViewById<MaterialButton>(R.id.replayNext)
        val previousButton = findViewById<MaterialButton>(R.id.replayPrevious)
        nextButton.text = when (step) {
            0 -> "下一步：候选回复"
            1 -> if (draft.candidateA == null && draft.candidateB == null) "不生成，直接手工改写" else "下一步：评价与改写"
            else -> "保存记录"
        }
        previousButton.visible(step > 0)
        enableTree(content, !busy)
        nextButton.isEnabled = !busy
        previousButton.isEnabled = !busy
        updateRestrictedControls()
    }

    private fun renderContext() {
        paragraph("选择回复前的聊天片段，最后一条应是对方消息。生成时不会读取截止点之后的聊天，也不会向 Soul 发送消息。")
        button("从本地聊天历史选择", ::pickContact)
        contactField = field("联系人（可留空）", draft.contact, 120, multiline = false)
        sceneField = selector("回复场景", sceneLabels, scenes.indexOf(draft.scene))
        datasetField = selector("这条记录的用途", listOf("仅测试 · 不用于后续学习", "学习用途 · 需人工确认采纳或改写"), if (draft.dataset == ReplayDataset.LEARNING) 1 else 0)
        paragraph("测试集用于公平比较效果，不用于风格学习。同一片段不要一边学习一边测试。")
        transcriptField = field("多轮上下文（必填）", transcript, 16_320, minLines = 6).also {
            transcriptLayout = it.parent.parent as? TextInputLayout
        }
        paragraph("格式：每条以“我：”或“对方：”开头。最多 40 条，单条 2000 字，合计 16000 字。图片或语音请填已确认的文字，不猜测内容。")
        originalField = field("当时的原回复（可留空，不加入生成上下文）", draft.originalReply, 2000, minLines = 2)
    }

    private fun renderCandidates() {
        paragraph("按同一段上下文对比两条候选。A / B 已随机排列；评价保存前不展示来源和耗时。")
        button("查看本轮上下文", ::showContext)
        strategyField = selector("对比方式", listOf("同一模型：基础策略 / 个性策略", "两个模型：都使用个性策略"), if (compareStrategies) 0 else 1)
        val names = endpoints.map(ModelEndpoint::shortLabel)
        endpointAField = selector("模型配置一", names.ifEmpty { listOf("暂无有效模型配置") }, endpoints.indexOfFirst { it.id == endpointAId }.coerceAtLeast(0))
        endpointBField = selector("模型配置二（仅比较两个模型时使用）", names.ifEmpty { listOf("暂无有效模型配置") }, endpoints.indexOfFirst { it.id == endpointBId }.coerceAtLeast(0))
        paragraph("只使用你在“模型设置”中保存的配置，不改变自动任务的模型顺序，也不自动切换备用服务。每次最多发起两次模型请求，可能产生费用。")
        if (!Prefs.getStyleLearningEnabled(this)) paragraph("注意：风格学习开关当前关闭，个性策略不会读取已采纳的学习案例。可先在“回复与学习”开启，再进行对比。")
        generateButton = button(if (draft.candidateA != null || draft.candidateB != null) "重新生成两条候选" else "生成两条候选", ::confirmGenerate)
        if (endpoints.isEmpty()) paragraph("请先在“模型设置”填写有效接口、模型名和 API Key，再重新打开此页。你仍可跳过生成，直接手工改写。")
        renderCandidate("候选 A", draft.candidateA)
        renderCandidate("候选 B", draft.candidateB)
    }

    private fun renderReview() {
        paragraph(if (draft.dataset == ReplayDataset.LEARNING) "当前是学习用途。只有保存时明确确认采纳或改写，才会用于后续参考。" else "当前仅用于测试。选择、改写和原因都不会用于风格学习。")
        button("查看本轮上下文", ::showContext)
        if (draft.originalReply.isNotBlank()) {
            heading("当时的原回复")
            paragraph(draft.originalReply, selectable = true)
        }
        renderCandidate("候选 A", draft.candidateA)
        renderCandidate("候选 B", draft.candidateB)
        val canVote = successfulPair()
        if (draft.candidateA != null || draft.candidateB != null) {
            heading("哪条更适合这段聊天？")
            if (!canVote) paragraph("候选未完整生成成功，本轮不计比较投票。可返回重新生成，或填写你自己的改写。")
            val group = RadioGroup(this)
            val choices = listOf(null to "暂不评价", ReplayChoice.A to "选 A", ReplayChoice.B to "选 B", ReplayChoice.NEITHER to "都不好")
            choices.forEach { (value, label) ->
                val radio = MaterialRadioButton(this).apply {
                    id = View.generateViewId()
                    text = label
                    minHeight = dp(48)
                    isChecked = draft.choice == value
                    isEnabled = value == null || canVote
                }
                group.addView(radio, LinearLayout.LayoutParams(-1, -2))
                radio.setOnClickListener { draft = draft.copy(choice = value) }
            }
            content.addView(group)
        }
        correctionField = field("你会怎么回（可手工改写）", draft.correctedReply, 2000, minLines = 3)
        reasonField = field("选择或修改原因", draft.reason, 1000, minLines = 2)
        paragraph("例如：接错了上文、问题太多、编造经历，或这句像我平时说的话。改写或选择“都不好”时请写明原因。")
        if (revealed) {
            paragraph("这是已保存的评价，来源已公开。重新生成会清除本轮评价并恢复盲选。")
            button("删除这条记录", ::confirmDelete)
        }
    }

    private fun renderCandidate(title: String, candidate: ReplayCandidate?) {
        if (candidate == null) return
        heading(title)
        // Failed metadata can name the endpoint, so keep the actual error hidden until review is saved.
        paragraph(if (candidate.error.isNotBlank()) "生成失败，本候选不可选择。可重新生成；保存记录后可查看失败信息。" else candidate.text, selectable = true)
        if (revealed) paragraph("来源：${candidate.source}\n耗时：${candidate.durationMs / 1000.0} 秒" + if (candidate.error.isNotBlank()) "\n${candidate.error}" else "")
    }

    private fun showContext() {
        MaterialAlertDialogBuilder(this).setTitle("本轮上下文 · 截止到对方消息")
            .setMessage(transcript).setPositiveButton("返回对比", null).show()
    }

    private fun captureInputs() {
        val nextTranscript = transcriptField?.text?.toString() ?: transcript
        val nextScene = sceneField?.selectedItemPosition?.let { scenes[it] } ?: draft.scene
        val nextContact = contactField?.text?.toString()?.trim() ?: draft.contact
        val contextChanged = nextTranscript != transcript || nextScene != draft.scene || nextContact != draft.contact
        transcript = nextTranscript
        draft = draft.copy(
            contact = nextContact, scene = nextScene,
            dataset = datasetField?.selectedItemPosition?.let { if (it == 1) ReplayDataset.LEARNING else ReplayDataset.EVALUATION } ?: draft.dataset,
            originalReply = originalField?.text?.toString().orEmpty().takeIf { originalField != null } ?: draft.originalReply,
            correctedReply = correctionField?.text?.toString().orEmpty().takeIf { correctionField != null } ?: draft.correctedReply,
            reason = reasonField?.text?.toString().orEmpty().takeIf { reasonField != null } ?: draft.reason,
        )
        if (contextChanged && (draft.candidateA != null || draft.candidateB != null || draft.correctedReply.isNotBlank())) {
            draft = draft.copy(candidateA = null, candidateB = null, choice = null, correctedReply = "", reason = "")
            revealed = false
            showMessage("上下文已改变，旧候选和评价已清除，请重新生成或改写", Snackbar.LENGTH_LONG)
        }
        strategyField?.let { compareStrategies = it.selectedItemPosition == 0 }
        endpointAField?.let { endpointAId = endpoints.getOrNull(it.selectedItemPosition)?.id.orEmpty() }
        endpointBField?.let { endpointBId = endpoints.getOrNull(it.selectedItemPosition)?.id.orEmpty() }
    }

    private fun readValidContext(): Boolean {
        captureInputs()
        val parsed = runCatching { ReplyReplayLogic.parseTranscript(transcript) }
        val turns = parsed.getOrNull()
        val error = parsed.exceptionOrNull()?.message ?: when {
            turns.isNullOrEmpty() -> "请填写至少一条对方消息"
            turns.last().role != "in" -> "请把片段截止在一条对方消息，原回复填在下方独立字段"
            else -> ReplyReplayLogic.validationError(draft.copy(turns = turns))
        }
        if (error != null) {
            transcriptLayout?.error = error
            transcriptField?.requestFocus()
            showMessage(error, Snackbar.LENGTH_LONG)
            return false
        }
        transcriptLayout?.error = null
        draft = draft.copy(turns = turns.orEmpty())
        return true
    }

    private fun moveTo(target: Int) {
        if (busy) return
        captureInputs()
        step = target.coerceIn(0, 2)
        render()
        findViewById<NestedScrollView>(R.id.replayEditorScroll).scrollTo(0, 0)
    }

    private fun confirmGenerate() {
        if (busy || !readValidContext()) return
        val a = endpoints.firstOrNull { it.id == endpointAId } ?: return showMessage("请选择有效模型配置")
        val b = if (compareStrategies) null else endpoints.firstOrNull { it.id == endpointBId }
        if (!compareStrategies && (b == null || a.id == b.id || a.baseUrl == b.baseUrl && a.model == b.model)) {
            showMessage("模型对比请选择两个不同的模型；只有一个模型时可选择策略对比", Snackbar.LENGTH_LONG)
            return
        }
        MaterialAlertDialogBuilder(this).setTitle("向所选模型服务发送测试上下文？")
            .setMessage("将向 ${a.shortLabel()}${b?.let { " 和 ${it.shortLabel()}" }.orEmpty()} 发送这段聊天上下文，以及生成所需的本人资料和相关学习资料。\n\n共最多两次请求，可能产生费用；不会向 Soul 发消息，也不会改变自动任务的模型顺序。取消不能撤回已发出的请求。\n\n重新生成会替换当前候选、改写和评价。")
            .setNegativeButton("取消", null).setPositiveButton("同意并生成") { _, _ -> generate(a, b) }.show()
    }

    private fun generate(a: ModelEndpoint, b: ModelEndpoint?) {
        if (busy) return
        val request = draft.copy(candidateA = null, candidateB = null, choice = null, correctedReply = "", reason = "")
        val cancellation = ReplayCancellation()
        generation = cancellation
        val epoch = ++generationEpoch
        val strategy = compareStrategies
        setBusy(true, "正在生成候选，最多两次请求…", cancellable = true)
        worker.execute {
            val result = runCatching {
                ReplyReplayRunner(applicationContext).generate(request, a, b, strategy, cancellation) {
                    // Runner progress may include provider names/timings. Keep blind review genuinely blind.
                    ui { if (epoch == generationEpoch) status("正在生成候选，请稍候…") }
                }
            }
            ui {
                if (epoch != generationEpoch) return@ui
                generation = null
                setBusy(false)
                if (cancellation.isCancelled()) { status("已取消。原草稿保留，未计入评价。"); return@ui }
                result.onSuccess { (first, second) ->
                    val pairSucceeded = listOf(first, second).all { it.error.isBlank() && it.text.isNotBlank() }
                    val hasPreviousWork = draft.candidateA != null || draft.candidateB != null ||
                        draft.correctedReply.isNotBlank() || draft.reason.isNotBlank() || draft.choice != null
                    if (!pairSucceeded && hasPreviousWork) {
                        status("本次候选未完整成功，未覆盖之前的候选、改写和评价。请检查模型配置或网络后重试。")
                        return@onSuccess
                    }
                    draft = request.copy(candidateA = first, candidateB = second)
                    revealed = false
                    render()
                    status(if (successfulPair()) "两条候选已生成，请比较文字后再评价。" else "有候选生成失败，本轮不计比较投票。")
                }.onFailure { status("生成未完成，原草稿已保留。请检查模型配置或网络后重试。") }
            }
        }
    }

    private fun cancelGeneration() {
        generation?.cancel()
        generation = null
        generationEpoch++
        setBusy(false)
        status("已取消生成，原草稿已保留。已发送的网络请求无法撤回。")
    }

    private fun saveReview() {
        if (busy || !readValidContext()) return
        if (draft.choice != null && !successfulPair()) {
            showMessage("候选未完整成功，不能计入比较投票"); return
        }
        if ((draft.correctedReply.isNotBlank() || draft.choice == ReplayChoice.NEITHER) && draft.reason.isBlank()) {
            reasonField?.error = "请补充选择或修改原因"
            reasonField?.requestFocus()
            return
        }
        val learning = ReplyReplayLogic.learningReply(draft).isNotBlank()
        if (learning) {
            MaterialAlertDialogBuilder(this).setTitle("确认将这条回复用于学习？")
                .setMessage("你已选择学习用途。保存后，你采纳的候选或手工改写（优先使用改写）会连同上下文和原因供后续参考。请确认表达符合你的习惯，且没有编造自身信息。\n\n只想比较效果，请回到第一步改成“仅测试”。")
                .setNegativeButton("再检查一下", null).setPositiveButton("确认采纳并保存") { _, _ -> persist() }.show()
        } else persist()
    }

    private fun persist() {
        val snapshot = draft
        work("保存记录…") {
            ReplyReplayDatabase(applicationContext).use { check(it.save(snapshot)) { "保存未成功，请重试" } }
            ui {
                draft = snapshot
                revealed = canRevealSaved(snapshot)
                savedFingerprint = fingerprint()
                render()
                status(if (ReplyReplayLogic.learningReply(snapshot).isNotBlank()) "已保存并确认用于学习。" else "已保存；这条记录未加入学习参考。")
            }
        }
    }

    private fun pickContact() {
        captureInputs()
        work("读取本地联系人…") {
            val contacts = ConversationMemoryDatabase(applicationContext).use { it.replayContacts() }
            ui {
                if (contacts.isEmpty()) showMessage("还没有本地聊天历史，可以直接粘贴多轮上下文")
                else MaterialAlertDialogBuilder(this).setTitle("选择联系人")
                    .setItems(contacts.toTypedArray()) { _, index -> pickCutoff(contacts[index]) }
                    .setNegativeButton("取消", null).show()
            }
        }
    }

    private fun pickCutoff(contact: String) {
        work("读取聊天历史…") {
            val entries = ConversationMemoryDatabase(applicationContext).use { it.replayEntries(contact) }
            val choices = entries.indices.filter { entries[it].role == "in" }.reversed()
            val labels = choices.map { index ->
                val entry = entries[index]
                "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.observedAt))}\n${entry.content}"
            }
            ui {
                if (choices.isEmpty()) showMessage("该联系人没有可用的对方消息，请手工粘贴片段")
                else MaterialAlertDialogBuilder(this).setTitle("截止到哪条对方消息？")
                    .setItems(labels.toTypedArray()) { _, index ->
                        val end = choices[index]
                        val turns = entries.take(end + 1).takeLast(40).map { ReplayTurn(it.role, it.content) }.toMutableList()
                        while (turns.size > 1 && turns.sumOf { it.text.length } > ReplyReplayLogic.MAX_CONTEXT_CHARS) {
                            turns.removeAt(0)
                        }
                        val original = entries.drop(end + 1).takeWhile { it.role == "out" }.joinToString("\n") { it.content }.take(2000)
                        val apply = {
                            draft = draft.copy(contact = contact, turns = turns, originalReply = original,
                                candidateA = null, candidateB = null, choice = null, correctedReply = "", reason = "")
                            transcript = ReplyReplayLogic.formatTranscript(turns)
                            revealed = false
                            render()
                            status("已选取截止该消息的最近 ${turns.size} 条，后续消息不会加入上下文。请检查并删减无关内容。")
                        }
                        if (transcript.isNotBlank()) MaterialAlertDialogBuilder(this).setTitle("替换当前片段？")
                            .setMessage("将替换当前上下文和原回复，并清除旧候选与评价。")
                            .setNegativeButton("取消", null).setPositiveButton("替换") { _, _ -> apply() }.show()
                        else apply()
                    }.setNegativeButton("取消", null).show()
            }
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this).setTitle("删除这条回放？")
            .setMessage("删除片段、候选、改写和评价，不影响 Soul 或本地聊天历史。只有之前导出的文件可以恢复。")
            .setNegativeButton("取消", null).setPositiveButton("删除记录") { _, _ ->
                work("删除记录…") {
                    ReplyReplayDatabase(applicationContext).use { check(it.delete(draft.id)) { "记录不存在或删除失败" } }
                    ui { showMessage("回放记录已删除"); finish() }
                }
            }.show()
    }

    private fun successfulPair(): Boolean = listOf(draft.candidateA, draft.candidateB).all {
        it != null && it.error.isBlank() && it.text.isNotBlank()
    }

    private fun canRevealSaved(item: ReplyReplayCase): Boolean =
        item.choice != null || item.correctedReply.isNotBlank() ||
            item.candidateA?.error?.isNotBlank() == true || item.candidateB?.error?.isNotBlank() == true

    private fun work(label: String, task: () -> Unit) {
        if (busy || worker.isShutdown) return
        setBusy(true, label)
        worker.execute {
            val result = runCatching(task)
            ui {
                setBusy(false)
                result.exceptionOrNull()?.let { status("操作失败：${it.message ?: "请重试"}。当前草稿已保留。") }
            }
        }
    }

    private fun ui(action: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) action() }
    private fun status(message: String) {
        findViewById<TextView>(R.id.replayEditorStatus).apply { text = message; visible(message.isNotEmpty()) }
    }
    private fun setBusy(value: Boolean, message: String = "", cancellable: Boolean = false) {
        busy = value
        findViewById<View>(R.id.replayEditorProgress).visible(value)
        findViewById<View>(R.id.replayCancel).visible(value && cancellable)
        findViewById<View>(R.id.replayNext).isEnabled = !value
        findViewById<View>(R.id.replayPrevious).isEnabled = !value
        enableTree(content, !value)
        if (message.isNotBlank()) status(message)
        updateRestrictedControls()
    }
    private fun updateRestrictedControls() {
        generateButton?.isEnabled = !busy && endpoints.isNotEmpty()
        if (step == 2 && !successfulPair()) disableInvalidVotes(content)
    }
    private fun enableTree(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) enableTree(view.getChildAt(i), enabled)
    }
    private fun disableInvalidVotes(view: View) {
        if (view is MaterialRadioButton && view.text != "暂不评价") view.isEnabled = false
        if (view is ViewGroup) for (i in 0 until view.childCount) disableInvalidVotes(view.getChildAt(i))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun heading(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextAppearance(R.style.TextAppearance_SoulBot_Section)
            setPadding(0, dp(24), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun paragraph(text: String, selectable: Boolean = false) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextIsSelectable(selectable)
            setPadding(0, dp(8), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(text: String, click: () -> Unit): MaterialButton {
        val button = (layoutInflater.inflate(R.layout.view_replay_action, content, false) as MaterialButton).apply {
            this.text = text
            minHeight = dp(48)
            isAllCaps = false
            setOnClickListener { click() }
        }
        content.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return button
    }
    private fun field(label: String, value: String, limit: Int, multiline: Boolean = true, minLines: Int = 1): EditText {
        val layout = (layoutInflater.inflate(R.layout.view_replay_field, content, false) as TextInputLayout).apply {
            hint = label
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            this.minLines = minLines
            maxLines = if (multiline) Int.MAX_VALUE else 1
            setText(value)
            // Imported history must never be silently cut mid-message; validation explains any excess.
            filters = arrayOf(InputFilter.LengthFilter(maxOf(limit, value.length)))
            id = View.generateViewId()
            isSaveEnabled = false // State is captured explicitly, never by volatile generated view ids.
        }
        layout.addView(edit, LinearLayout.LayoutParams(-1, -2))
        content.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        return edit
    }
    private fun selector(label: String, options: List<String>, selected: Int): Spinner {
        val spinner = Spinner(this).apply {
            id = View.generateViewId()
            contentDescription = label
            minimumHeight = dp(48)
            adapter = ArrayAdapter(this@ReplayEditorActivity, R.layout.item_replay_spinner, options)
            setSelection(selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
            isSaveEnabled = false
        }
        content.addView(TextView(this).apply {
            text = label
            labelFor = spinner.id
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, dp(16), 0, dp(4))
        })
        content.addView(spinner, LinearLayout.LayoutParams(-1, -2))
        return spinner
    }

    private fun fingerprint() = listOf(draft.toString(), transcript).joinToString("\n")
    private fun requestExit() {
        captureInputs()
        val unsaved = (transcript.isNotBlank() || draft.originalReply.isNotBlank() || draft.correctedReply.isNotBlank()) && fingerprint() != savedFingerprint
        if (!busy && !unsaved) return finish()
        MaterialAlertDialogBuilder(this).setTitle(if (busy) "停止操作并离开？" else "放弃未保存的修改？")
            .setMessage(if (busy) "生成会被取消，已发出的请求无法撤回。尚未保存的修改会丢失。" else "尚未保存的上下文、候选或评价将丢失。可以取消并继续编辑。")
            .setNegativeButton("继续编辑", null).setPositiveButton("离开") { _, _ -> generation?.cancel(); finish() }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureInputs()
        outState.putString("id", draft.id); outState.putLong("created", draft.createdAt)
        outState.putString("contact", draft.contact); outState.putString("scene", draft.scene.name)
        outState.putString("dataset", draft.dataset.name); outState.putString("transcript", transcript)
        outState.putString("original", draft.originalReply); outState.putString("corrected", draft.correctedReply)
        outState.putString("reason", draft.reason); outState.putString("choice", draft.choice?.name)
        outState.putInt("step", step); outState.putBoolean("revealed", revealed)
        outState.putString("endpointA", endpointAId); outState.putString("endpointB", endpointBId)
        outState.putBoolean("strategies", compareStrategies); outState.putString("saved", savedFingerprint)
        outState.putBoolean("recordLoadPending", recordLoadPending)
        fun putCandidate(key: String, candidate: ReplayCandidate?) {
            if (candidate != null) outState.putBundle(key, Bundle().apply {
                putString("text", candidate.text); putString("source", candidate.source)
                putLong("duration", candidate.durationMs); putString("error", candidate.error)
            })
        }
        putCandidate("a", draft.candidateA); putCandidate("b", draft.candidateB)
        outState.putBoolean("interrupted", generation != null)
        super.onSaveInstanceState(outState)
    }

    private fun restoreDraft(state: Bundle) {
        fun candidate(key: String) = state.getBundle(key)?.let {
            ReplayCandidate(it.getString("text").orEmpty(), it.getString("source").orEmpty(), it.getLong("duration"), it.getString("error").orEmpty())
        }
        transcript = state.getString("transcript").orEmpty()
        draft = ReplyReplayCase(
            id = state.getString("id") ?: draft.id, contact = state.getString("contact").orEmpty(),
            scene = runCatching { ReplyScene.valueOf(state.getString("scene").orEmpty()) }.getOrDefault(ReplyScene.CHAT),
            turns = runCatching { ReplyReplayLogic.parseTranscript(transcript) }.getOrDefault(emptyList()),
            originalReply = state.getString("original").orEmpty(), correctedReply = state.getString("corrected").orEmpty(),
            reason = state.getString("reason").orEmpty(),
            dataset = runCatching { ReplayDataset.valueOf(state.getString("dataset").orEmpty()) }.getOrDefault(ReplayDataset.EVALUATION),
            candidateA = candidate("a"), candidateB = candidate("b"),
            choice = runCatching { ReplayChoice.valueOf(state.getString("choice").orEmpty()) }.getOrNull(),
            createdAt = state.getLong("created", System.currentTimeMillis()),
        )
        step = state.getInt("step").coerceIn(0, 2); revealed = state.getBoolean("revealed")
        endpointAId = state.getString("endpointA").orEmpty(); endpointBId = state.getString("endpointB").orEmpty()
        compareStrategies = state.getBoolean("strategies", true); savedFingerprint = state.getString("saved").orEmpty()
        recordLoadPending = state.getBoolean("recordLoadPending")
        if (state.getBoolean("interrupted")) status("界面已重建，生成已取消；草稿仍保留，可手动重新生成。")
    }

    override fun onDestroy() {
        generation?.cancel()
        generationEpoch++
        worker.shutdown()
        super.onDestroy()
    }
}
