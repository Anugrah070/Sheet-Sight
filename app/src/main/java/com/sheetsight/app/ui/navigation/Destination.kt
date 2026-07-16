package com.sheetsight.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.sheetsight.app.R

/**
 * Single source of truth for the app's top-level tabs (route, label, icon).
 * Adding a new tab means adding one entry here and one composable in
 * [SheetSightNavHost] — nothing else needs to change.
 */
sealed class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    data object Library : Destination("library", R.string.nav_library, Icons.Filled.LibraryMusic)
    data object Editor : Destination("editor", R.string.nav_editor, Icons.Filled.Edit)
    data object Practice : Destination("practice", R.string.nav_practice, Icons.Filled.PlayArrow)
    data object Analysis : Destination("analysis", R.string.nav_analysis, Icons.Filled.Analytics)
    data object Settings : Destination("settings", R.string.nav_settings, Icons.Filled.Settings)

    data class Preview(val scoreId: Long) : Destination("preview/$scoreId", R.string.nav_preview, Icons.Filled.Visibility) {
        companion object {
            const val ROUTE_PATTERN = "preview/{scoreId}"
        }
    }

    companion object {
        val bottomBarDestinations = listOf(Library, Editor, Practice, Analysis, Settings)
    }
}
