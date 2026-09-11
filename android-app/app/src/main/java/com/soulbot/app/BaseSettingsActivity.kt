package com.soulbot.app

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar

abstract class BaseSettingsActivity : AppCompatActivity() {
    protected fun setupToolbar(title: String) {
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            this.title = title
            setNavigationOnClickListener { finish() }
        }
        applyToolbarInsets()
    }

    protected fun showMessage(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
        Snackbar.make(findViewById(android.R.id.content), message, duration).show()
    }

    protected fun confirmClear(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清除") { _, _ -> action() }
            .show()
    }

    protected fun View.visible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}

fun AppCompatActivity.applyToolbarInsets() {
    val toolbar = findViewById<View>(R.id.toolbar)
    val initialHeight = toolbar.layoutParams.height
    val initialTopPadding = toolbar.paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        view.setPadding(view.paddingLeft, initialTopPadding + top, view.paddingRight, view.paddingBottom)
        view.layoutParams = view.layoutParams.apply { height = initialHeight + top }
        insets
    }
    ViewCompat.requestApplyInsets(toolbar)
}
