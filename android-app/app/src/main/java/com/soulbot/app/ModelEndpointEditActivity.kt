package com.soulbot.app

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout

class ModelEndpointEditActivity : BaseSettingsActivity() {
    companion object {
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
    }

    private lateinit var spinner: Spinner
    private lateinit var etDisplayName: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: EditText
    private lateinit var etApiKey: EditText
    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var btnDelete: MaterialButton
    private var existing: ModelEndpoint? = null
    private var bindingInitialValue = true
    private var lastProviderName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_endpoint_edit)
        val endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID)
        existing = endpointId?.let { id ->
            ModelEndpointPrefs.getEndpoints(this).firstOrNull { it.id == id }
        }
        if (endpointId != null && existing == null) {
            finish()
            return
        }
        setupToolbar(if (existing == null) "添加模型通道" else "编辑模型通道")

        spinner = findViewById(R.id.spEndpointProvider)
        etDisplayName = findViewById(R.id.etEndpointDisplayName)
        etBaseUrl = findViewById(R.id.etEndpointBaseUrl)
        etModel = findViewById(R.id.etEndpointModel)
        etApiKey = findViewById(R.id.etEndpointApiKey)
        switchEnabled = findViewById(R.id.switchEndpointEditEnabled)
        btnDelete = findViewById(R.id.btnDeleteModelEndpoint)

        val providerNames = ModelList.MODELS.map(ModelConfig::name)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providerNames)
        val initial = existing ?: ModelEndpointPrefs.newEndpoint()
        etDisplayName.setText(initial.displayName)
        etBaseUrl.setText(initial.baseUrl)
        etModel.setText(initial.model)
        etApiKey.setText(initial.apiKey)
        switchEnabled.isChecked = initial.enabled
        lastProviderName = initial.providerName
        spinner.setSelection(providerNames.indexOf(initial.providerName).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = ModelList.MODELS[position]
                if (!bindingInitialValue) {
                    if (etDisplayName.text.isBlank() || etDisplayName.text.toString() == lastProviderName) {
                        etDisplayName.setText(provider.name)
                    }
                    etBaseUrl.setText(provider.baseUrl)
                    etModel.setText(provider.model)
                }
                bindingInitialValue = false
                lastProviderName = provider.name
                updateBaseUrlState(provider)
                clearErrors()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<MaterialButton>(R.id.btnSaveModelEndpoint).setOnClickListener { save() }
        btnDelete.visible(existing != null)
        btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun updateBaseUrlState(provider: ModelConfig) {
        etBaseUrl.isEnabled = provider.isCustom
        findViewById<TextInputLayout>(R.id.tilEndpointBaseUrl).helperText =
            if (provider.isCustom) "填写 OpenAI 兼容 API 根地址"
            else "提供方预设地址；模型名称仍可调整"
    }

    private fun save() {
        clearErrors()
        val provider = ModelList.MODELS[spinner.selectedItemPosition]
        val endpoint = ModelEndpoint(
            id = existing?.id ?: ModelEndpointPrefs.newEndpoint(provider).id,
            displayName = etDisplayName.text.toString(),
            providerName = provider.name,
            baseUrl = etBaseUrl.text.toString(),
            model = etModel.text.toString(),
            apiKey = etApiKey.text.toString(),
            enabled = switchEnabled.isChecked,
        ).normalized()

        val error = ModelEndpointRules.validationError(endpoint)
        if (error != null) {
            when {
                endpoint.displayName.isBlank() || endpoint.displayName.length > 30 ->
                    findViewById<TextInputLayout>(R.id.tilEndpointDisplayName).error = error
                endpoint.baseUrl.isBlank() || error.contains("地址") ->
                    findViewById<TextInputLayout>(R.id.tilEndpointBaseUrl).error = error
                endpoint.model.isBlank() ->
                    findViewById<TextInputLayout>(R.id.tilEndpointModel).error = error
                endpoint.apiKey.isBlank() ->
                    findViewById<TextInputLayout>(R.id.tilEndpointApiKey).error = error
            }
            return
        }

        val endpoints = ModelEndpointPrefs.getEndpoints(this).toMutableList()
        val index = endpoints.indexOfFirst { it.id == endpoint.id }
        if (index >= 0) {
            endpoints[index] = endpoint
        } else {
            if (endpoints.size >= ModelEndpointRules.MAX_ENDPOINTS) {
                showMessage("最多只能配置 ${ModelEndpointRules.MAX_ENDPOINTS} 个通道")
                return
            }
            endpoints += endpoint
        }
        ModelEndpointPrefs.saveEndpoints(this, endpoints)
        ModelEndpointPrefs.clearHealth(this, endpoint.id)
        showMessage("${endpoint.displayName} 已保存")
        finish()
    }

    private fun confirmDelete() {
        val endpoint = existing ?: return
        AlertDialog.Builder(this)
            .setTitle("删除 ${endpoint.displayName}？")
            .setMessage("不会删除聊天记录，但该通道将不再参与模型调用。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val remaining = ModelEndpointPrefs.getEndpoints(this).filterNot { it.id == endpoint.id }
                ModelEndpointPrefs.saveEndpoints(this, remaining)
                ModelEndpointPrefs.clearHealth(this, endpoint.id)
                finish()
            }
            .show()
    }

    private fun clearErrors() {
        findViewById<TextInputLayout>(R.id.tilEndpointDisplayName).error = null
        findViewById<TextInputLayout>(R.id.tilEndpointBaseUrl).error = null
        findViewById<TextInputLayout>(R.id.tilEndpointModel).error = null
        findViewById<TextInputLayout>(R.id.tilEndpointApiKey).error = null
    }
}
