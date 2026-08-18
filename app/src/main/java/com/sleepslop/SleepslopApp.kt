package com.sleepslop

import android.app.Application
import com.sleepslop.audio.AudioEngine

class SleepslopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AudioEngine.init(this)
    }
}
