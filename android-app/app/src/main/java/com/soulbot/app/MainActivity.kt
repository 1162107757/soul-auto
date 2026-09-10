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
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etKey: EditText
    private lateinit var spModel: Spinner
    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: EditText
    private lateinit var tvLearningStats: TextView
    private lateinit var learningDb: ChatLearningDatabase
    private val modelNames = ModelList.MODELS.map { it.name }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        learningDb = ChatLearningDatabase(applicationContext)

        etKey = findViewById(R.id.etApiKey)
        val cbShow = findViewById<CheckBox>(R.id.cbShowKey)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnAcc = findViewById<Button>(R.id.btnAccessibility)
        val btnOverlay = findViewById<Button>(R.id.btnOverlay)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnHistory = findViewById<Button>(R.id.btnHistory)
        val rgAutomationMode = findViewById<RadioGroup>(R.id.rgAutomationMode)
        val etSquareInterval = findViewById<EditText>(R.id.etSquareInterval)
        val etThinkMin = findViewById<EditText>(R.id.etThinkMin)
        val etThinkMax = findViewById<EditText>(R.id.etThinkMax)
        val etGreetHours = findViewById<EditText>(R.id.etGreetHours)
        val btnSaveTime = findViewById<Button>(R.id.btnSaveTime)
        val etSelfGender = findViewById<EditText>(R.id.etSelfGender)
        val etSelfAge = findViewById<EditText>(R.id.etSelfAge)
        val etSelfRegion = findViewById<EditText>(R.id.etSelfRegion)
        val etSelfZodiac = findViewById<EditText>(R.id.etSelfZodiac)
        val etSelfOccupation = findViewById<EditText>(R.id.etSelfOccupation)
        val etSelfDetails = findViewById<EditText>(R.id.etSelfDetails)
        val btnSaveSelfProfile = findViewById<Button>(R.id.btnSaveSelfProfile)
        val cbStyleLearning = findViewById<CheckBox>(R.id.cbStyleLearning)
        val cbContactMemory = findViewById<CheckBox>(R.id.cbContactMemory)
        val etStyleSamples = findViewById<EditText>(R.id.etStyleSamples)
        val btnImportStyle = findViewById<Button>(R.id.btnImportStyle)
        val btnClearStyle = findViewById<Button>(R.id.btnClearStyle)
        val btnClearContactMemory = findViewById<Button>(R.id.btnClearContactMemory)
        tvStatus = findViewById(R.id.tvStatus)
        tvLearningStats = findViewById(R.id.tvLearningStats)
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

        rgAutomationMode.check(
            when (Prefs.getAutomationMode(this)) {
                AutomationMode.PLANET_CHAT -> R.id.rbPlanetChat
                AutomationMode.SQUARE_DM -> R.id.rbSquareDm
            },
        )
        rgAutomationMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbSquareDm -> AutomationMode.SQUARE_DM
                else -> AutomationMode.PLANET_CHAT
            }
            Prefs.setAutomationMode(this, mode)
            Toast.makeText(this, "已切换为${mode.displayName}，下次启动生效", Toast.LENGTH_SHORT).show()
            refreshStatus()
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

        val selfProfile = Prefs.getSelfProfile(this)
        etSelfGender.setText(selfProfile.gender)
        etSelfAge.setText(selfProfile.age)
        etSelfRegion.setText(selfProfile.region)
        etSelfZodiac.setText(selfProfile.zodiac)
        etSelfOccupation.setText(selfProfile.occupation)
        etSelfDetails.setText(selfProfile.details)

        btnSaveSelfProfile.setOnClickListener {
            Prefs.setSelfProfile(
                this,
                SelfProfile(
                    gender = etSelfGender.text.toString(),
                    age = etSelfAge.text.toString(),
                    region = etSelfRegion.text.toString(),
                    zodiac = etSelfZodiac.text.toString(),
                    occupation = etSelfOccupation.text.toString(),
                    details = etSelfDetails.text.toString(),
                ),
            )
            Toast.makeText(this, "自身资料已保存，后续回复立即生效", Toast.LENGTH_SHORT).show()
        }

        cbStyleLearning.isChecked = Prefs.getStyleLearningEnabled(this)
        cbContactMemory.isChecked = Prefs.getContactMemoryEnabled(this)

        cbStyleLearning.setOnCheckedChangeListener { _, checked ->
            Prefs.setStyleLearningEnabled(this, checked)
            refreshLearningStats()
        }
        cbContactMemory.setOnCheckedChangeListener { _, checked ->
            Prefs.setContactMemoryEnabled(this, checked)
            refreshLearningStats()
        }

        btnImportStyle.setOnClickListener {
            val parsed = ChatSampleParser.parse(etStyleSamples.text.toString())
            if (parsed.isEmpty()) {
                Toast.makeText(this, "没有识别到“对方：/我：”格式的样本", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val added = learningDb.importSamples(parsed)
            etStyleSamples.text.clear()
            Toast.makeText(this, "已新增 $added 条人工聊天样本", Toast.LENGTH_SHORT).show()
            refreshLearningStats()
        }

        btnClearStyle.setOnClickListener {
            confirmClear(
                title = "清除聊天风格？",
                message = "导入和自动学习的人工回复样本都会删除，联系人记忆不受影响。",
            ) {
                learningDb.clearStyleSamples()
                refreshLearningStats()
                Toast.makeText(this, "聊天风格样本已清除", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearContactMemory.setOnClickListener {
            confirmClear(
                title = "清除联系人记忆？",
                message = "所有联系人过去提到的内容都会删除，聊天风格样本不受影响。",
            ) {
                learningDb.clearContactMemories()
                refreshLearningStats()
                Toast.makeText(this, "联系人记忆已清除", Toast.LENGTH_SHORT).show()
            }
        }
        refreshLearningStats()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLearningStats()
    }

    override fun onDestroy() {
        learningDb.close()
        super.onDestroy()
    }

    private fun refreshLearningStats() {
        if (!::tvLearningStats.isInitialized || !::learningDb.isInitialized) return
        val styleCount = learningDb.styleSampleCount()
        val memoryCount = learningDb.contactMemoryCount()
        val styleState = if (Prefs.getStyleLearningEnabled(this)) "已开启" else "已暂停"
        val memoryState = if (Prefs.getContactMemoryEnabled(this)) "已开启" else "已暂停"
        tvLearningStats.text =
            "风格学习：$styleState · $styleCount 条人工样本\n" +
                "联系人记忆：$memoryState · $memoryCount 条本地记忆"
    }

    private fun confirmClear(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清除") { _, _ -> action() }
            .show()
    }

    private fun refreshStatus() {
        val acc = isAccessibilityEnabled()
        val overlay = isOverlayEnabled()
        val selected = modelNames[spModel.selectedItemPosition]
        val hasKey = Prefs.getApiKey(this, selected).isNotEmpty()
        tvStatus.text = buildString {
            append("当前模型：$selected\n")
            append("工作模式：${Prefs.getAutomationMode(this@MainActivity).displayName}\n")
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
