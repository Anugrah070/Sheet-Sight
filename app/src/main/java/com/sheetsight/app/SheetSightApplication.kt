package com.sheetsight.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Annotating with [HiltAndroidApp] triggers Hilt's
 * code generation and creates the top-level dependency container that all
 * other Hilt components (ViewModels, etc.) attach to.
 *
 * No feature-specific initialization happens here yet — this is Phase 1
 * scaffolding only.
 */
@HiltAndroidApp
class SheetSightApplication : Application()
