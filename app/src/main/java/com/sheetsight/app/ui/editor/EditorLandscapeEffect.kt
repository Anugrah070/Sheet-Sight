package com.sheetsight.app.ui.editor

import androidx.compose.runtime.Composable
import com.sheetsight.app.ui.common.LandscapeFirstEffect

/** Makes the Editor landscape-first while restoring the prior policy when it closes. */
@Composable
internal fun EditorLandscapeEffect() {
    LandscapeFirstEffect()
}
