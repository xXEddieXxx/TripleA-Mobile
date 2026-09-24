package org.triplea.mobile.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.nio.file.Path
import org.triplea.mobile.app.ui.GameScreen
import org.triplea.mobile.app.ui.AboutScreen
import org.triplea.mobile.app.ui.HomeScreen
import org.triplea.mobile.app.ui.HowToPlayScreen
import org.triplea.mobile.app.ui.LoadGameScreen
import org.triplea.mobile.app.ui.MapBrowserScreen
import org.triplea.mobile.app.ui.SettingsScreen
import org.triplea.mobile.app.ui.SetupScreen

/** Holds navigation arguments that are awkward to encode into a route string. */
object NavArgs {
    @Volatile
    var saveFile: Path? = null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TripleATheme {
                Surface {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
private fun TripleATheme(content: @Composable () -> Unit) {
    val settings by AppSettings.state.collectAsState()
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF9FC5E8), secondary = Color(0xFFC9B37E))
    } else {
        lightColorScheme(primary = Color(0xFF2E4A62), secondary = Color(0xFF8A6D2F))
    }
    // the default Material sizes are small for a map game held at arm's length: raise the small styles
    val base = Typography()
    val typography = Typography(
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
        labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelMedium = base.labelMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontSize = 16.sp, lineHeight = 20.sp),
        titleSmall = base.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNewGame = {
                    NavArgs.saveFile = null
                    navController.navigate("setup")
                },
                onLoadGames = { navController.navigate("load") },
                onMapBrowser = { navController.navigate("maps") },
                onSettings = { navController.navigate("settings") },
                onHowToPlay = { navController.navigate("howto") },
                onAbout = { navController.navigate("about") },
            )
        }
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable("howto") {
            HowToPlayScreen(onBack = { navController.popBackStack() })
        }
        composable("load") {
            LoadGameScreen(
                onBack = { navController.popBackStack() },
                onLoad = { save ->
                    NavArgs.saveFile = save
                    navController.navigate("setup")
                },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("maps") {
            MapBrowserScreen(onBack = { navController.popBackStack() })
        }
        composable("setup") {
            SetupScreen(
                saveFile = NavArgs.saveFile,
                onGameStarted = {
                    navController.navigate("game") {
                        popUpTo("home") { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("game") {
            GameScreen(
                onQuit = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
            )
        }
    }
}
