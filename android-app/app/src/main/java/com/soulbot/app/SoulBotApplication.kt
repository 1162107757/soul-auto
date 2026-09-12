package com.soulbot.app

import android.app.Application
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

class SoulBotApplication : Application() {
    private val handlingCrash = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (handlingCrash.compareAndSet(false, true)) {
                RuntimeLog.record(
                    applicationContext,
                    "uncaught application crash; thread=${thread.name}",
                    error,
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
        RuntimeLog.record(applicationContext, "application process started; pid=${Process.myPid()}")
    }
}
