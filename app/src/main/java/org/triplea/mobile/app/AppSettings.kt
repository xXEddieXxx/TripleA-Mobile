package org.triplea.mobile.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import games.strategy.triplea.settings.ClientSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Let the game screen rotate, or lock it. */
enum class OrientationMode { AUTO, LANDSCAPE, PORTRAIT }

/** Map tile resolution; lower is faster and uses less memory on big maps. */
enum class MapQuality(val extraSample: Int) { HIGH(1), MEDIUM(2), LOW(4) }

/** User preferences, the mobile counterpart of the desktop client's settings window. */
data class Settings(
    // game
    val confirmPhaseEnd: Boolean = true,
    val showPhaseBanner: Boolean = true,
    val pauseAfterCasualties: Boolean = true,
    val autoDefaultCasualties: Boolean = false,
    val showAiBattles: Boolean = true,
    /** A short "how battles work" card in the battle window, until the player dismisses it. */
    val showBattleHelp: Boolean = true,
    val autosaveEachRound: Boolean = true,
    /** After loading a save, replay what the other players did since the player's last turn. */
    val replayOnLoad: Boolean = true,
    /** Pause after every battle step and dice roll so the battle window can be followed. */
    val battleStepPauseMillis: Int = 600,
    /** Previous / sleep / next buttons that walk through the units that can still move. */
    val showUnitScroller: Boolean = false,
    // AI
    /** Pause after every AI move; while above 0 the map follows the AI's moves. */
    val aiMovePauseMillis: Int = 800,
    val aiCombatStepPauseMillis: Int = 0,
    // map
    val showTerritoryNames: Boolean = true,
    /** Draw the PU value of every land territory on the map. */
    val showTerritoryValues: Boolean = false,
    val mapMaxZoom: Float = 4f,
    // appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val keepScreenOn: Boolean = true,
    /** Hide the Android status and navigation bars while playing; a swipe from the edge shows them briefly. */
    val fullscreenGame: Boolean = true,
    val orientation: OrientationMode = OrientationMode.AUTO,
    // feedback
    val vibrateOnBattle: Boolean = true,
    val vibrateOnTurn: Boolean = true,
    // performance and look of the map
    val mapQuality: MapQuality = MapQuality.HIGH,
    val showRelief: Boolean = true,
    val unitScale: Float = 1.0f,
    val counterScale: Float = 1.0f,
    // sound
    val soundEnabled: Boolean = true,
    val soundVolume: Float = 0.8f,
    val soundBattle: Boolean = true,
    val soundPhase: Boolean = true,
    val soundPlacement: Boolean = true,
    val soundOther: Boolean = true,
    /** Sound era folder override ("" = what the map or the engine default says). */
    val soundTheme: String = "",
)

/** Holds the current [Settings], persists them and pushes the engine relevant ones into [ClientSetting]. */
object AppSettings {
    private const val PREFS = "settings"
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = Settings()
        _state.value = Settings(
            confirmPhaseEnd = prefs.getBoolean("confirmPhaseEnd", defaults.confirmPhaseEnd),
            showPhaseBanner = prefs.getBoolean("showPhaseBanner", defaults.showPhaseBanner),
            pauseAfterCasualties = prefs.getBoolean("pauseAfterCasualties", defaults.pauseAfterCasualties),
            autoDefaultCasualties = prefs.getBoolean("autoDefaultCasualties", defaults.autoDefaultCasualties),
            showAiBattles = prefs.getBoolean("showAiBattles", defaults.showAiBattles),
            showBattleHelp = prefs.getBoolean("showBattleHelp", defaults.showBattleHelp),
            autosaveEachRound = prefs.getBoolean("autosaveEachRound", defaults.autosaveEachRound),
            replayOnLoad = prefs.getBoolean("replayOnLoad", defaults.replayOnLoad),
            battleStepPauseMillis = prefs.getInt("battleStepPauseMillis", defaults.battleStepPauseMillis),
            showUnitScroller = prefs.getBoolean("showUnitScroller", defaults.showUnitScroller),
            aiMovePauseMillis = prefs.getInt("aiMovePauseMillis", defaults.aiMovePauseMillis),
            aiCombatStepPauseMillis = prefs.getInt("aiCombatStepPauseMillis", defaults.aiCombatStepPauseMillis),
            showTerritoryNames = prefs.getBoolean("showTerritoryNames", defaults.showTerritoryNames),
            showTerritoryValues = prefs.getBoolean("showTerritoryValues", defaults.showTerritoryValues),
            mapMaxZoom = prefs.getFloat("mapMaxZoom", defaults.mapMaxZoom),
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(defaults.theme),
            keepScreenOn = prefs.getBoolean("keepScreenOn", defaults.keepScreenOn),
            fullscreenGame = prefs.getBoolean("fullscreenGame", defaults.fullscreenGame),
            orientation = runCatching { OrientationMode.valueOf(prefs.getString("orientation", null) ?: "") }.getOrDefault(defaults.orientation),
            vibrateOnBattle = prefs.getBoolean("vibrateOnBattle", defaults.vibrateOnBattle),
            vibrateOnTurn = prefs.getBoolean("vibrateOnTurn", defaults.vibrateOnTurn),
            mapQuality = runCatching { MapQuality.valueOf(prefs.getString("mapQuality", null) ?: "") }.getOrDefault(defaults.mapQuality),
            showRelief = prefs.getBoolean("showRelief", defaults.showRelief),
            unitScale = prefs.getFloat("unitScale", defaults.unitScale),
            counterScale = prefs.getFloat("counterScale", defaults.counterScale),
            soundEnabled = prefs.getBoolean("soundEnabled", defaults.soundEnabled),
            soundVolume = prefs.getFloat("soundVolume", defaults.soundVolume),
            soundBattle = prefs.getBoolean("soundBattle", defaults.soundBattle),
            soundPhase = prefs.getBoolean("soundPhase", defaults.soundPhase),
            soundPlacement = prefs.getBoolean("soundPlacement", defaults.soundPlacement),
            soundOther = prefs.getBoolean("soundOther", defaults.soundOther),
            soundTheme = prefs.getString("soundTheme", defaults.soundTheme) ?: defaults.soundTheme,
        )
        // builds before the map followed AI moves stored 0 for everyone: switch those to the new
        // default once; a 0 set on purpose afterwards is kept
        if (!prefs.getBoolean("aiFollowDefaultApplied", false)) {
            prefs.edit { putBoolean("aiFollowDefaultApplied", true) }
            if (_state.value.aiMovePauseMillis == 0) {
                _state.value = _state.value.copy(aiMovePauseMillis = defaults.aiMovePauseMillis)
                prefs.edit { putInt("aiMovePauseMillis", defaults.aiMovePauseMillis) }
            }
        }
        applyToEngine(_state.value)
    }

    fun update(change: (Settings) -> Settings) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit {
            putBoolean("confirmPhaseEnd", next.confirmPhaseEnd)
            putBoolean("showPhaseBanner", next.showPhaseBanner)
            putBoolean("pauseAfterCasualties", next.pauseAfterCasualties)
            putBoolean("autoDefaultCasualties", next.autoDefaultCasualties)
            putBoolean("showAiBattles", next.showAiBattles)
            putBoolean("showBattleHelp", next.showBattleHelp)
            putBoolean("autosaveEachRound", next.autosaveEachRound)
            putBoolean("replayOnLoad", next.replayOnLoad)
            putInt("battleStepPauseMillis", next.battleStepPauseMillis)
            putBoolean("showUnitScroller", next.showUnitScroller)
            putInt("aiMovePauseMillis", next.aiMovePauseMillis)
            putInt("aiCombatStepPauseMillis", next.aiCombatStepPauseMillis)
            putBoolean("showTerritoryNames", next.showTerritoryNames)
            putBoolean("showTerritoryValues", next.showTerritoryValues)
            putFloat("mapMaxZoom", next.mapMaxZoom)
            putString("theme", next.theme.name)
            putBoolean("keepScreenOn", next.keepScreenOn)
            putBoolean("fullscreenGame", next.fullscreenGame)
            putString("orientation", next.orientation.name)
            putBoolean("vibrateOnBattle", next.vibrateOnBattle)
            putBoolean("vibrateOnTurn", next.vibrateOnTurn)
            putString("mapQuality", next.mapQuality.name)
            putBoolean("showRelief", next.showRelief)
            putFloat("unitScale", next.unitScale)
            putFloat("counterScale", next.counterScale)
            putBoolean("soundEnabled", next.soundEnabled)
            putFloat("soundVolume", next.soundVolume)
            putBoolean("soundBattle", next.soundBattle)
            putBoolean("soundPhase", next.soundPhase)
            putBoolean("soundPlacement", next.soundPlacement)
            putBoolean("soundOther", next.soundOther)
            putString("soundTheme", next.soundTheme)
        }
        applyToEngine(next)
    }

    fun resetToDefaults() = update { Settings() }

    private fun applyToEngine(settings: Settings) {
        ClientSetting.aiMovePauseDuration.setValue(settings.aiMovePauseMillis)
        ClientSetting.aiCombatStepPauseDuration.setValue(settings.aiCombatStepPauseMillis)
    }
}
