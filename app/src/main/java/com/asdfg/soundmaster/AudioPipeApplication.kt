package com.asdfg.soundmaster

import android.app.Application
import com.asdfg.soundmaster.audio.RoutingDiagnostics

class AudioPipeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RoutingDiagnostics.initialize(this)
    }
}
