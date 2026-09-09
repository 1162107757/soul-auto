package com.soulbot.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etKey: EditText
    private lateinit var spModel: Spinner
    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: EditText
    private val modelNames = ModelList.MODELS.map { it.name }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etKey = findViewById(R.id.etApiKey)
        val cbShow = findViewById<CheckBox>(R.id.cbShowKey)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnAcc = findViewById<Button>(R.id.btnAccessibility)
        val btnOverlay = findViewById<Button>(R.id.btnOverlay)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnHistory = findViewById<Button>(R.id.btnHistory)
        val etSquareInterval = findViewById<EditText>(R.id.etSquareInterval)
        val etThinkMin = findViewById<EditText>(R.id.etThinkMin)
        val etThinkMax = findViewById<EditText>(R.id.etThinkMax)
        val etGreetHours = findViewById<EditText>(R.id.etGreetHours)
        val btnSaveTime = findViewById<Button>(R.id.btnSaveTime)
        tvStatus = findViewById(R.id.tvStatus)
        spModel = findViewById(R.id.spModel)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModel = findViewById(R.id.etModel)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelNames)
        spModel.adapter = adapter
        spModel.setSelection(modelNames.indexOf(Prefs.getSelectedModel(this)).coerceAtLeast(0))
        spModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = modelNames[position]
                Prefs.setSelectedModel(this@MainActivity, selected)
                etKey.setText(Prefs.getApiKey(this@MainActivity, selected))
                val isCustom = selected == "中转站"
                etBaseUrl.visibility = if (isCustom) View.VISIBLE else View.GONE
                etModel.visibility = if (isCustom) View.VISIBLE else View.GONE
                if (isCustom) {
                    etBaseUrl.setText(Prefs.getCustomBaseUrl(this@MainActivity))
                    etModel.setText(Prefs.getCustomModel(this@MainActivity))
                }
                refreshStatus()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        etKey.setText(Prefs.getApiKey(this, Prefs.getSelectedModel(this)))

        cbShow.setOnCheckedChangeListener { _, checked ->
            etKey.transformationMethod =
                if (checked) null else PasswordTransformationMethod.getInstance()
            etKey.setSelection(etKey.text.length)
        }

        btnSave.setOnClickListener {
            val selected = modelNames[spModel.selectedItemPosition]
            Prefs.setApiKey(this, selected, etKey.text.toString())
            if (selected == "中转站") {
                Prefs.setCustomBaseUrl(this, etBaseUrl.text.toString())
                Prefs.setCustomModel(this, etModel.text.toString())
            }
            Toast.makeText(this, "已保存 $selected 的配置", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        btnAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnStart.setOnClickListener {
            if (!isOverlayEnabled()) {
                Toast.makeText(this, "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            val intent = Intent(this, FloatingButtonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "悬浮按钮已启动", Toast.LENGTH_SHORT).show()
        }

        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // 时间间隔设置
        etSquareInterval.setText(Prefs.getSquareInterval(this).toString())
        etThinkMin.setText(Prefs.getThinkMin(this).toString())
        etThinkMax.setText(Prefs.getThinkMax(this).toString())
        etGreetHours.setText(Prefs.getGreetIntervalHours(this).toString())

        btnSaveTime.setOnClickListener {
            val square = etSquareInterval.text.toString().toIntOrNull() ?: 240
            val thinkMin = etThinkMin.text.toString().toIntOrNull() ?: 5
            val thinkMax = etThinkMax.text.toString().toIntOrNull() ?: 15
            val greetHours = etGreetHours.text.toString().toIntOrNull() ?: 24
            Prefs.setSquareInterval(this, square)
            Prefs.setThinkMin(this, thinkMin)
            Prefs.setThinkMax(this, thinkMax)
            Prefs.setGreetIntervalHours(this, greetHours)
            Toast.makeText(this, "时间设置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val acc = isAccessibilityEnabled()
        val overlay = isOverlayEnabled()
        val selected = modelNames[spModel.selectedItemPosition]
        val hasKey = Prefs.getApiKey(this, selected).isNotEmpty()
        tvStatus.text = buildString {
            append("当前模型：$selected\n")
            append("无障碍服务：${if (acc) "✅ 已开启" else "❌ 未开启"}\n")
            append("悬浮窗权限：${if (overlay) "✅ 已授权" else "❌ 未授权"}\n")
            append("API Key：${if (hasKey) "✅ 已填写" else "❌ 未填写"}")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun isOverlayEnabled(): Boolean =
        Settings.canDrawOverlays(this)
}
