package com.picoxr.stereoviewer.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ImageAccessController {
    private const val REQUEST_CODE = 7301
    private val mutableGranted = MutableStateFlow(false)

    val granted = mutableGranted.asStateFlow()

    fun refresh(context: Context) {
        mutableGranted.value = hasAccess(context)
    }

    fun request(activity: Activity) {
        activity.requestPermissions(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            REQUEST_CODE,
        )
    }

    fun handleResult(context: Context, requestCode: Int) {
        if (requestCode == REQUEST_CODE) refresh(context)
    }

    private fun hasAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
}
