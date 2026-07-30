package com.sheetsight.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

/**
 * Application entry point. Annotating with [HiltAndroidApp] triggers Hilt's
 * code generation and creates the top-level dependency container that all
 * other Hilt components (ViewModels, etc.) attach to.
 *
 * Phase 4.2 adds OpenCV's native library load here: it is a process-wide,
 * one-time initialization every OMR preprocessing call
 * ([com.sheetsight.app.data.omr.preprocessing.OmrPreprocessor] and its
 * collaborators) depends on, so it belongs at startup rather than being
 * repeated per call.
 */
@HiltAndroidApp
class SheetSightApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV native library failed to load; OMR preprocessing will be unavailable.")
        }
    }

    private companion object {
        const val TAG = "SheetSightApplication"
    }
}