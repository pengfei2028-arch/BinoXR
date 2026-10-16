package com.picoxr.stereoviewer.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.picoxr.stereoviewer.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
