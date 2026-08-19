package com.sleepslop

import android.app.Application
import com.sleepslop.audio.AudioEngine
import com.sleepslop.audio.SmartTimer
import com.sleepslop.audio.WakeRoutine
import com.sleepslop.data.PresetStore

class SleepslopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AudioEngine.init(this)
        PresetStore.init(this)
        SmartTimer.init(this)
        WakeRoutine.init(this)
    }
}
