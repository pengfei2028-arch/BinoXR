package com.picoxr.stereoviewer.platform

import android.os.Bundle
import android.util.Log
import com.pico.spatial.core.container.getSelfSpaceState
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

class LaunchActivity : SpatialLaunchActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImageAccessController.refresh(this)
        if (!ImageAccessController.granted.value) {
            ImageAccessController.request(this)
        }
        logLifecycle("onCreate")
    }

    override fun onStart() {
        super.onStart()
        logLifecycle("onStart")
    }

    override fun onResume() {
        super.onResume()
        ImageAccessController.refresh(this)
        logLifecycle("onResume")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ImageAccessController.handleResult(this, requestCode)
    }

    override fun onPause() {
        logLifecycle("onPause")
        super.onPause()
    }

    override fun onStop() {
        logLifecycle("onStop")
        super.onStop()
    }

    override fun onDestroy() {
        logLifecycle("onDestroy")
        super.onDestroy()
    }

    private fun logLifecycle(event: String) {
        Log.i(LOG_TAG, "$event spaceState=${getSelfSpaceState()}")
    }

    companion object {
        const val LOG_TAG = "PicoStereoViewer"
    }
}
