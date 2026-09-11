package com.soulbot.app

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.Executors

class ModelSettingsActivity : BaseSettingsActivity() {
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var modelList: LinearLayout
    private lateinit var btnAdd: MaterialButton
    private val executor = Executors.newSingleThreadExecutor()
    private val testingIds = mutableSetOf<String>()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatusLabels()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_settings)
        setupToolbar("模型与 API")
        tvSummary = findViewById(R.id.tvModelEndpointSummary)
        tvEmpty = findViewById(R.id.tvModelEndpointEmpty)
        modelList = findViewById(R.id.modelEndpointList)
        btnAdd = findViewById(R.id.btnAddModelEndpoint)
        btnAdd.setOnClickListener {
            val endpoints = ModelEndpointPrefs.getEndpoints(this)
            if (endpoints.size >= ModelEndpointRules.MAX_ENDPOINTS) {
                showMessage("最多只能配置 ${ModelEndpointRules.MAX_ENDPOINTS} 个通道")
            } else {
                openEditor(null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.removeCallbacks(refreshRunnable)
        render()
        refreshHandler.postDelayed(refreshRunnable, 1000)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        val endpoints = ModelEndpointPrefs.getEndpoints(this)
        val health = ModelEndpointPrefs.getHealth(this)
        val runnable = endpoints.filter(ModelEndpointRules::isRunnable)
        tvSummary.text = when {
            endpoints.isEmpty() -> "还没有模型通道"
            runnable.isEmpty() -> "${endpoints.size} 个通道 · 暂无可用配置"
            else -> "已启用 ${runnable.size} 个通道 · 主用 ${runnable.first().displayName}"
        }
        tvEmpty.visible(endpoints.isEmpty())
        modelList.removeAllViews()
        endpoints.forEachIndexed { index, endpoint ->
            modelList.addView(createEndpointView(endpoint, index, endpoints, health[endpoint.id]))
        }
        btnAdd.isEnabled = endpoints.size < ModelEndpointRules.MAX_ENDPOINTS
        btnAdd.text = if (btnAdd.isEnabled) "添加模型通道" else "已达到 5 个通道上限"
    }

    private fun createEndpointView(
        endpoint: ModelEndpoint,
        index: Int,
        all: List<ModelEndpoint>,
        health: ModelEndpointHealth?,
    ): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_model_endpoint, modelList, false)
        val row = view.findViewById<View>(R.id.rowModelEndpoint)
        row.tag = endpoint.id
        view.findViewById<TextView>(R.id.tvEndpointPriority).text = "P${index + 1}"
        view.findViewById<TextView>(R.id.tvEndpointName).text = endpoint.displayName
        view.findViewById<TextView>(R.id.tvEndpointDetail).text =
            "${endpoint.providerName} · ${endpoint.model}"
        view.findViewById<TextView>(R.id.tvEndpointStatus).text = endpointStatus(endpoint, health)

        val toggle = view.findViewById<SwitchMaterial>(R.id.switchEndpointEnabled)
        toggle.isChecked = endpoint.enabled
        toggle.contentDescription = "启用 ${endpoint.displayName}"
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val error = ModelEndpointRules.validationError(endpoint)
                if (error != null) {
                    toggle.isChecked = false
                    showMessage("$error，请先编辑通道")
                    return@setOnCheckedChangeListener
                }
            }
            updateEndpoint(endpoint.copy(enabled = checked))
        }

        val btnUp = view.findViewById<MaterialButton>(R.id.btnEndpointUp)
        val btnDown = view.findViewById<MaterialButton>(R.id.btnEndpointDown)
        btnUp.isEnabled = index > 0
        btnDown.isEnabled = index < all.lastIndex
        btnUp.setOnClickListener { move(endpoint.id, index - 1) }
        btnDown.setOnClickListener { move(endpoint.id, index + 1) }
        view.findViewById<MaterialButton>(R.id.btnEndpointEdit).setOnClickListener {
            openEditor(endpoint.id)
        }

        val btnTest = view.findViewById<MaterialButton>(R.id.btnEndpointTest)
        val testing = endpoint.id in testingIds
        btnTest.isEnabled = !testing && ModelEndpointRules.validationError(endpoint) == null
        btnTest.text = if (testing) "测试中…" else "测试"
        btnTest.setOnClickListener { testEndpoint(endpoint) }

        row.setOnClickListener { openEditor(endpoint.id) }
        row.setOnLongClickListener {
            row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val clip = ClipData.newPlainText("model-endpoint", endpoint.id)
            row.startDragAndDrop(clip, View.DragShadowBuilder(row), endpoint.id, 0)
            true
        }
        row.setOnDragListener { target, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                DragEvent.ACTION_DRAG_ENTERED -> {
                    target.alpha = 0.72f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    target.alpha = 1f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    target.alpha = 1f
                    val sourceId = event.localState as? String ?: return@setOnDragListener false
                    move(sourceId, index)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    target.alpha = 1f
                    true
                }
                else -> true
            }
        }
        return view
    }

    private fun refreshStatusLabels() {
        val endpoints = ModelEndpointPrefs.getEndpoints(this).associateBy(ModelEndpoint::id)
        val health = ModelEndpointPrefs.getHealth(this)
        for (index in 0 until modelList.childCount) {
            val child = modelList.getChildAt(index)
            val id = child.findViewById<View>(R.id.rowModelEndpoint).tag as? String ?: continue
            val endpoint = endpoints[id] ?: continue
            child.findViewById<TextView>(R.id.tvEndpointStatus).text =
                endpointStatus(endpoint, health[id])
        }
    }

    private fun endpointStatus(endpoint: ModelEndpoint, health: ModelEndpointHealth?): String {
        val error = ModelEndpointRules.validationError(endpoint)
        return when {
            error != null -> "配置未完成 · $error"
            !endpoint.enabled -> "已停用"
            else -> ModelEndpointPrefs.statusText(health)
        }
    }

    private fun updateEndpoint(updated: ModelEndpoint) {
        val endpoints = ModelEndpointPrefs.getEndpoints(this).map {
            if (it.id == updated.id) updated else it
        }
        ModelEndpointPrefs.saveEndpoints(this, endpoints)
        if (!updated.enabled) ModelEndpointPrefs.clearHealth(this, updated.id)
        render()
    }

    private fun move(endpointId: String, targetIndex: Int) {
        val endpoints = ModelEndpointPrefs.getEndpoints(this)
        val reordered = ModelEndpointRules.reordered(endpoints, endpointId, targetIndex)
        if (reordered === endpoints) return
        ModelEndpointPrefs.saveEndpoints(this, reordered)
        render()
    }

    private fun testEndpoint(endpoint: ModelEndpoint) {
        val error = ModelEndpointRules.validationError(endpoint)
        if (error != null) {
            showMessage("$error，请先编辑通道")
            return
        }
        testingIds += endpoint.id
        render()
        executor.execute {
            val result = ModelClient.chatOnce(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl,
                model = endpoint.model,
                systemPrompt = "这是连通性测试。只回复 OK。",
                messages = listOf("user" to "回复 OK"),
                timeoutMs = 20_000L,
            )
            if (result.content != null) {
                ModelEndpointPrefs.recordSuccess(this, endpoint.id, accepted = true)
            } else {
                ModelEndpointPrefs.recordFailure(this, endpoint.id, result)
            }
            runOnUiThread {
                testingIds -= endpoint.id
                if (!isFinishing && !isDestroyed) {
                    render()
                    showMessage(
                        if (result.content != null) "${endpoint.displayName} 测试成功"
                        else "测试失败：${result.error.take(48)}",
                    )
                }
            }
        }
    }

    private fun openEditor(endpointId: String?) {
        startActivity(Intent(this, ModelEndpointEditActivity::class.java).apply {
            endpointId?.let { putExtra(ModelEndpointEditActivity.EXTRA_ENDPOINT_ID, it) }
        })
    }
}
