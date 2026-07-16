package com.sheetsight.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sheetsight.app.ui.analysis.AnalysisScreen
import com.sheetsight.app.ui.editor.EditorScreen
import com.sheetsight.app.ui.library.LibraryScreen
import com.sheetsight.app.ui.practice.PracticeScreen
import com.sheetsight.app.ui.preview.PreviewScreen
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
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Hide bottom bar for the Preview screen
    val showBottomBar = currentRoute != Destination.Preview.ROUTE_PATTERN

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                SheetSightBottomBar(navController)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Library.route,
            modifier = Modifier.padding(if (showBottomBar) innerPadding else androidx.compose.foundation.layout.PaddingValues(0.dp))
        ) {
            composable(Destination.Library.route) {
                LibraryScreen(
                    onOpenScore = { scoreId ->
                        navController.navigate(Destination.Preview(scoreId).route)
                    }
                )
            }
            composable(Destination.Editor.route) { EditorScreen() }
            composable(Destination.Practice.route) { PracticeScreen() }
            composable(Destination.Analysis.route) { AnalysisScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
            composable(
                route = Destination.Preview.ROUTE_PATTERN,
                arguments = listOf(navArgument("scoreId") { type = NavType.LongType })
            ) { backStackEntry ->
                val scoreId = backStackEntry.arguments?.getLong("scoreId") ?: return@composable
                PreviewScreen(
                    scoreId = scoreId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
