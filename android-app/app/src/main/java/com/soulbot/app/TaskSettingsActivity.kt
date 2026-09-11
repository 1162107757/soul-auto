package com.soulbot.app

import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

class TaskSettingsActivity : BaseSettingsActivity() {
    private lateinit var rgMode: RadioGroup
    private lateinit var squareFields: android.view.View
    private lateinit var etSquareInterval: EditText
    private lateinit var etThinkMin: EditText
    private lateinit var etThinkMax: EditText
    private lateinit var etGreetHours: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_settings)
        setupToolbar("任务设置")
        rgMode = findViewById(R.id.rgAutomationMode)
        squareFields = findViewById(R.id.groupSquareFields)
        etSquareInterval = findViewById(R.id.etSquareInterval)
        etThinkMin = findViewById(R.id.etThinkMin)
        etThinkMax = findViewById(R.id.etThinkMax)
        etGreetHours = findViewById(R.id.etGreetHours)

        rgMode.check(
            if (Prefs.getAutomationMode(this) == AutomationMode.SQUARE_DM) R.id.rbSquareDm
            else R.id.rbPlanetChat,
        )
        rgMode.setOnCheckedChangeListener { _, _ -> updateModeFields() }
        etSquareInterval.setText(Prefs.getSquareInterval(this).toString())
        etThinkMin.setText(Prefs.getThinkMin(this).toString())
        etThinkMax.setText(Prefs.getThinkMax(this).toString())
        etGreetHours.setText(Prefs.getGreetIntervalHours(this).toString())
        updateModeFields()
        findViewById<MaterialButton>(R.id.btnSaveTask).setOnClickListener { save() }
    }

    private fun updateModeFields() {
        val square = rgMode.checkedRadioButtonId == R.id.rbSquareDm
        squareFields.visible(square)
        findViewById<TextView>(R.id.tvModeExplanation).text = if (square) {
            "奇遇铃优先，其次回复未读消息；空闲时只刷同城广场并私聊。"
        } else {
            "奇遇铃优先，其次回复未读消息；空闲时只进行灵魂匹配。"
        }
    }

    private fun save() {
        val square = etSquareInterval.text.toString().toIntOrNull()
        val min = etThinkMin.text.toString().toIntOrNull()
        val max = etThinkMax.text.toString().toIntOrNull()
        val greet = etGreetHours.text.toString().toIntOrNull()
        val tilSquare = findViewById<TextInputLayout>(R.id.tilSquareInterval)
        val tilMin = findViewById<TextInputLayout>(R.id.tilThinkMin)
        val tilMax = findViewById<TextInputLayout>(R.id.tilThinkMax)
        val tilGreet = findViewById<TextInputLayout>(R.id.tilGreetHours)
        listOf(tilSquare, tilMin, tilMax, tilGreet).forEach { it.error = null }
        var valid = true
        if (rgMode.checkedRadioButtonId == R.id.rbSquareDm && (square == null || square < 1)) {
            tilSquare.error = "请输入大于 0 的秒数"
            valid = false
        }
        if (min == null || min < 0) {
            tilMin.error = "请输入不小于 0 的秒数"
            valid = false
        }
        if (max == null || max < 0 || (min != null && max < min)) {
            tilMax.error = "最大值不能小于最小值"
            valid = false
        }
        if (greet == null || greet < 1) {
            tilGreet.error = "请输入大于 0 的小时数"
            valid = false
        }
        if (!valid) return

        Prefs.setAutomationMode(
            this,
            if (rgMode.checkedRadioButtonId == R.id.rbSquareDm) AutomationMode.SQUARE_DM
            else AutomationMode.PLANET_CHAT,
        )
        Prefs.setSquareInterval(this, square ?: Prefs.getSquareInterval(this))
        Prefs.setThinkMin(this, min!!)
        Prefs.setThinkMax(this, max!!)
        Prefs.setGreetIntervalHours(this, greet!!)
        showMessage("任务设置已保存；运行中的任务重新启动后使用新模式")
    }
}
