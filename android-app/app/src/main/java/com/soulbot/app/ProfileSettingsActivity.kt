package com.soulbot.app

import android.os.Bundle
import android.widget.EditText
import com.google.android.material.button.MaterialButton

class ProfileSettingsActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_settings)
        setupToolbar("我的资料")
        val gender = findViewById<EditText>(R.id.etSelfGender)
        val age = findViewById<EditText>(R.id.etSelfAge)
        val region = findViewById<EditText>(R.id.etSelfRegion)
        val zodiac = findViewById<EditText>(R.id.etSelfZodiac)
        val occupation = findViewById<EditText>(R.id.etSelfOccupation)
        val details = findViewById<EditText>(R.id.etSelfDetails)
        val profile = Prefs.getSelfProfile(this)
        gender.setText(profile.gender)
        age.setText(profile.age)
        region.setText(profile.region)
        zodiac.setText(profile.zodiac)
        occupation.setText(profile.occupation)
        details.setText(profile.details)
        findViewById<MaterialButton>(R.id.btnSaveSelfProfile).setOnClickListener {
            Prefs.setSelfProfile(
                this,
                SelfProfile(
                    gender.text.toString(), age.text.toString(), region.text.toString(),
                    zodiac.text.toString(), occupation.text.toString(), details.text.toString(),
                ),
            )
            showMessage("资料已保存，后续回复立即生效")
        }
    }
}
