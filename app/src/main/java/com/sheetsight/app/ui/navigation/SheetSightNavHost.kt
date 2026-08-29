package com.sheetsight.app.ui.navigation

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sheetsight.app.ui.analysis.AnalysisScreen
import com.sheetsight.app.ui.debug.OmrSmokeTestScreen
import com.sheetsight.app.ui.debug.AcousticValidationScreen
import com.sheetsight.app.ui.debug.GuidedPianoCaptureScreen
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
    val context = LocalContext.current
    val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Hide bottom bar for Preview and full-screen developer diagnostics.
    val showBottomBar = currentRoute != Destination.Preview.ROUTE_PATTERN &&
            currentRoute != Destination.Editor.ROUTE_PATTERN &&
            currentRoute != Destination.OmrSmokeTest.route &&
            currentRoute != Destination.AcousticValidation.route &&
            currentRoute != Destination.GuidedPianoCapture.route

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = showBottomBar && maxWidth > maxHeight

        Scaffold(
            bottomBar = {
                if (showBottomBar && !useNavigationRail) {
                    SheetSightBottomBar(navController)
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        if (showBottomBar) innerPadding
                        else androidx.compose.foundation.layout.PaddingValues(0.dp)
                    )
            ) {
                if (useNavigationRail) {
                    SheetSightNavigationRail(navController)
                }
                NavHost(
                    navController = navController,
                    startDestination = Destination.Library.route,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(Destination.Library.route) {
                        LibraryScreen(
                            onOpenScore = { score ->
                                navController.navigate(Destination.Preview(score.id).route)
                            },
                            onOpenEditor = { score ->
                                navController.navigate(Destination.Editor.forScore(score.id))
                            }
                        )
                    }
                    composable(
                        route = Destination.Editor.ROUTE_PATTERN,
                        arguments = listOf(
                            navArgument("scoreId") {
                                type = NavType.LongType
                                defaultValue = -1L
                            }
                        )
                    ) { backStackEntry ->
                        val scoreId = backStackEntry.arguments?.getLong("scoreId")?.takeIf { it > 0L }
                        EditorScreen(
                            scoreId = scoreId,
                            onBack = {
                                if (!navController.popBackStack()) {
                                    navController.navigate(Destination.Library.route) { launchSingleTop = true }
                                }
                            }
                        )
                    }
                    composable(Destination.Practice.route) { PracticeScreen() }
                    composable(Destination.Analysis.route) { AnalysisScreen() }
                    composable(Destination.Settings.route) {
                        SettingsScreen(
                            showDeveloperTools = isDebuggable,
                            onOpenOmrSmokeTest = { navController.navigate(Destination.OmrSmokeTest.route) },
                            onOpenAcousticValidation = { navController.navigate(Destination.AcousticValidation.route) },
                            onOpenGuidedPianoCapture = { navController.navigate(Destination.GuidedPianoCapture.route) }
                        )
                    }
                    composable(
                        route = Destination.Preview.ROUTE_PATTERN,
                        arguments = listOf(navArgument("scoreId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val scoreId = backStackEntry.arguments?.getLong("scoreId") ?: return@composable
                        PreviewScreen(
                            scoreId = scoreId,
                            onBack = { navController.popBackStack() },
                            onOpenEditor = { recognizedScoreId ->
                                navController.navigate(Destination.Editor.forScore(recognizedScoreId))
                            }
                        )
                    }
                    composable(Destination.OmrSmokeTest.route) {
                        OmrSmokeTestScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Destination.AcousticValidation.route) {
                        AcousticValidationScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Destination.GuidedPianoCapture.route) {
                        GuidedPianoCaptureScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
