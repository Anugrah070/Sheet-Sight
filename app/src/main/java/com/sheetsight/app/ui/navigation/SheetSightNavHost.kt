package com.sheetsight.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sheetsight.app.ui.analysis.AnalysisScreen
import com.sheetsight.app.ui.editor.EditorScreen
import com.sheetsight.app.ui.library.LibraryScreen
import com.sheetsight.app.ui.practice.PracticeScreen
import com.sheetsight.app.ui.settings.SettingsScreen

/**
 * Top-level navigation graph. Hosts the five tabs described in the product
 * requirements (Library, Editor, Practice, Analysis, Settings) behind a
 * shared [Scaffold]/bottom bar. Each destination is a self-contained
 * feature package under `ui/<feature>`; no feature logic lives here.
 */
@Composable
fun SheetSightNavHost(
    navController: NavHostController = rememberNavController()
) {
    Scaffold(
        bottomBar = { SheetSightBottomBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Library.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.Library.route) { LibraryScreen() }
            composable(Destination.Editor.route) { EditorScreen() }
            composable(Destination.Practice.route) { PracticeScreen() }
            composable(Destination.Analysis.route) { AnalysisScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
