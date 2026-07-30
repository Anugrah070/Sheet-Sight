package com.sheetsight.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import com.sheetsight.app.ui.navigation.SheetSightNavHost
import com.sheetsight.app.ui.theme.SheetSightTheme

/**
 * Single-activity host. All screens are Composable destinations reached via
 * [SheetSightNavHost]; this class owns no UI or business logic of its own.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SheetSightTheme {
                SheetSightNavHost()
            }
        }
    }
}
