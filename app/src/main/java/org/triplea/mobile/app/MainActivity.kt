package org.triplea.mobile.app

import android.os.Build
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.triplea.mobile.app.forum.DiceRegistrationScreen
import org.triplea.mobile.app.forum.ForumAccount
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.forum.ForumGamesScreen
import org.triplea.mobile.forum.Forum
import org.triplea.mobile.app.forum.ForumLoginScreen
import org.triplea.mobile.app.forum.ForumSaves
import org.triplea.mobile.app.forum.ForumThreadScreen
import org.triplea.mobile.app.forum.ForumTurnJob
import org.triplea.mobile.forum.NodeBbClient

/** Holds navigation arguments that are awkward to encode into a route string. */
object NavArgs {
    @Volatile
    var saveFile: Path? = null

    /** The forum thread the thread screen shows, and that a save opened from it belongs to. */
    @Volatile
    var forumThread: NodeBbClient.GameThread? = null

    /** The setup screen starts a new play-by-forum game. */
    @Volatile
    var newForumGame: Boolean = false

    /** A save game handed to the app by another app (a chat, a mail); the load screen imports it. */
    val pendingImport = MutableStateFlow<Uri?>(null)

    /** The forum thread a tapped "your turn" notification opens. */
    val pendingThread = MutableStateFlow<NodeBbClient.GameThread?>(null)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeImport(intent)
        setContent {
            TripleATheme {
                Surface {
                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeImport(intent)
    }

    private fun takeImport(intent: Intent?) {
        if (intent?.action == ForumTurnJob.ACTION_OPEN_THREAD) {
            val topic = intent.getIntExtra(ForumTurnJob.EXTRA_TOPIC, 0)
            val forum = Forum.entries.firstOrNull { it.name == intent.getStringExtra(ForumTurnJob.EXTRA_FORUM) } ?: Forum.TRIPLEA
            if (topic > 0) {
                NavArgs.pendingThread.value = NodeBbClient.GameThread.of(
                    forum,
                    topic,
                    intent.getStringExtra(ForumTurnJob.EXTRA_SLUG).orEmpty(),
                    intent.getStringExtra(ForumTurnJob.EXTRA_TITLE).orEmpty(),
                )
            }
            return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            // shared "to" the app: the file travels as a stream extra, not as the data URI
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            else -> null
        }
        Log.i("Import", "intent action=${intent?.action} type=${intent?.type} data=${intent?.data} stream=$uri")
        if (uri != null) NavArgs.pendingImport.value = uri
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
    val pendingImport by NavArgs.pendingImport.collectAsState()
    LaunchedEffect(pendingImport) {
        if (pendingImport != null) navController.navigate("load") { launchSingleTop = true }
    }
    val pendingThread by NavArgs.pendingThread.collectAsState()
    LaunchedEffect(pendingThread) {
        val thread = pendingThread ?: return@LaunchedEffect
        // not into a running game: its forum turn would be lost; the notification just brings the app up
        if (navController.currentDestination?.route != "game") {
            NavArgs.forumThread = thread
            navController.navigate("forum/thread")
        }
        NavArgs.pendingThread.value = null
    }
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNewGame = {
                    NavArgs.saveFile = null
                    NavArgs.forumThread = null
                    NavArgs.newForumGame = false
                    navController.navigate("setup")
                },
                onLoadGames = { navController.navigate("load") },
                onPlayByForum = { navController.navigate("forum") },
                onMapBrowser = { navController.navigate("maps") },
                onSettings = { navController.navigate("settings") },
                onHowToPlay = { navController.navigate("howto") },
                onAbout = { navController.navigate("about") },
            )
        }
        composable("forum") {
            ForumGamesScreen(
                onSignIn = { navController.navigate("forum/login") },
                onOpenThread = { thread ->
                    NavArgs.forumThread = thread
                    navController.navigate("forum/thread")
                },
                onNewGame = {
                    NavArgs.saveFile = null
                    NavArgs.forumThread = null
                    NavArgs.newForumGame = true
                    navController.navigate("setup")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("forum/thread") {
            val thread = NavArgs.forumThread
            if (thread == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                ForumThreadScreen(
                    thread = thread,
                    onOpenSave = { save ->
                        NavArgs.saveFile = save
                        NavArgs.newForumGame = false
                        navController.navigate("setup")
                    },
                    onWatch = { navController.navigate("game") },
                    onSignIn = {
                        // the login opens on the forum the thread is on
                        ForumAccount.select(thread.forum())
                        navController.navigate("forum/login")
                    },
                    onStartGame = {
                        // the setup's game list and dice emails, the game then posting to this thread
                        NavArgs.saveFile = null
                        NavArgs.newForumGame = false
                        navController.navigate("setup")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable("dice/register") {
            DiceRegistrationScreen(onBack = { navController.popBackStack() })
        }
        composable("forum/login") {
            ForumLoginScreen(
                forum = ForumAccount.selected.value,
                onSignedIn = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
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
                    // a save downloaded from a forum thread still posts to it
                    NavArgs.forumThread = ForumSaves.threadFor(save)
                    NavArgs.newForumGame = false
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
                forumThread = NavArgs.forumThread,
                newForumGame = NavArgs.newForumGame,
                onRegisterDiceEmail = { navController.navigate("dice/register") },
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
                    // only from the game itself: a game ended by a sent turn has already moved to its thread
                    if (navController.currentDestination?.route == "game") {
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                },
                onOpenForum = { thread ->
                    NavArgs.forumThread = thread
                    if (GameController.session == null) {
                        // the turn is sent and the game over: home > forum > thread, so Back leads to the games
                        navController.navigate("forum") { popUpTo("home") }
                    }
                    navController.navigate("forum/thread")
                },
            )
        }
    }
}
