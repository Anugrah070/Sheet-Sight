package com.sheetsight.app.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Material 3 bottom navigation bar for the five top-level tabs. Kept
 * separate from [SheetSightNavHost] so it can be reused or replaced (e.g.
 * with a nav rail on tablets) without touching the graph.
 */
@Composable
fun SheetSightBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        Destination.bottomBarDestinations.forEach { destination ->
            val selected = currentDestination?.hierarchy?.any {
                it.route == destination.route || it.route?.startsWith("${destination.route}?") == true
            } == true
            NavigationBarItem(
                selected = selected,
                onClick = { navController.selectTopLevelDestination(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.labelRes)) }
            )
        }
    }
}

/** Landscape navigation chrome; routes and back-stack behavior remain shared. */
@Composable
fun SheetSightNavigationRail(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationRail {
        Destination.bottomBarDestinations.forEach { destination ->
            val selected = currentDestination?.hierarchy?.any {
                it.route == destination.route || it.route?.startsWith("${destination.route}?") == true
            } == true
            NavigationRailItem(
                selected = selected,
                onClick = { navController.selectTopLevelDestination(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.labelRes)) }
            )
        }
    }
}

private fun NavHostController.selectTopLevelDestination(destination: Destination) {
    navigate(destination.route) {
        // Keep one instance of each tab and restore its state when reselected.
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
