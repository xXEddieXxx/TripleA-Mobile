package org.triplea.mobile.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.RoundedCorner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.triplea.map.game.notes.GameNotes
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.GameStep
import games.strategy.engine.data.ProductionRule
import games.strategy.engine.data.Territory
import games.strategy.triplea.attachments.TerritoryAttachment
import games.strategy.triplea.Properties
import games.strategy.engine.data.Unit
import games.strategy.triplea.ui.PlaceData
import java.util.Optional
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.UiMode
import org.triplea.mobile.app.OrientationMode
import org.triplea.mobile.app.game.BattleRequest
import org.triplea.mobile.app.game.BattleState
import org.triplea.mobile.app.game.BattleStrength
import org.triplea.mobile.app.game.BattleStrengths
import org.triplea.mobile.app.game.CasualtyNoticeRequest
import org.triplea.mobile.app.game.CasualtyRequest
import org.triplea.mobile.app.game.ConfirmRequest
import org.triplea.mobile.app.game.EndTurnRequest
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.game.GameStatus
import org.triplea.mobile.app.game.HistoryBlock
import org.triplea.mobile.app.game.MadeMove
import org.triplea.mobile.app.game.MapSnapshot
import org.triplea.mobile.app.game.MoveHelper
import org.triplea.mobile.app.game.MovePlan
import org.triplea.mobile.app.game.MoveRequest
import org.triplea.mobile.app.game.PlaceRequest
import org.triplea.mobile.app.game.PoliticsRequest
import org.triplea.mobile.app.game.UserActionRequest
import org.triplea.mobile.app.game.PurchaseRequest
import org.triplea.mobile.app.game.RetreatRequest
import org.triplea.mobile.app.game.SelectTerritoryRequest
import org.triplea.mobile.app.game.SelectUnitsRequest
import org.triplea.mobile.app.game.TerritorySnapshot
import org.triplea.mobile.app.game.UiMessage
import org.triplea.mobile.app.game.UiRequest
import org.triplea.mobile.app.game.UnitStack
import org.triplea.mobile.app.render.ImageCache

private val SIDE_PANEL_WIDTH = 260.dp
private val DESKTOP_PANEL_WIDTH = 340.dp

/**
 * The game screen. The map fills the screen; everything else floats on top of it: a status line at
 * the top left, the phase actions at the bottom right, the territory status line at the bottom, the
 * battle window while a battle is fought. An optional details panel (right in landscape, below in
 * portrait) can be toggled by the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(onQuit: () -> kotlin.Unit) {
    val session: LocalGameSession = GameController.session ?: run {
        LaunchedEffect(Unit) { onQuit() }
        return
    }
    val scope = rememberCoroutineScope()
    val status by GameController.status.collectAsState()
    val pending by GameController.pending.collectAsState()
    val battle by GameController.battle.collectAsState()
    val gameOver by GameController.gameOver.collectAsState()
    val images = remember(session) { ImageCache(session.resourceLoader) }
    DisposableEffect(session) { onDispose { images.close() } }
    val mapState = remember(session) {
        val dims = session.mapData.mapDimensions
        MapViewState(dims.width, dims.height, wrapX = session.mapData.scrollWrapX())
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val settings by AppSettings.state.collectAsState()
    mapState.maxScale = settings.mapMaxZoom
    val configuration = LocalConfiguration.current
    val desktop = when (settings.uiMode) {
        UiMode.DESKTOP -> true
        UiMode.PHONE -> false
        UiMode.AUTO -> configuration.smallestScreenWidthDp >= 600
    }

    // keep the display awake while playing, if wanted
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // orientation lock for the game screen only
    DisposableEffect(settings.orientation) {
        val activity = findActivity(view.context)
        activity?.requestedOrientation = when (settings.orientation) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // immersive mode like a video player: system bars hidden, a swipe from the edge shows them briefly
    DisposableEffect(settings.fullscreenGame) {
        val window = findActivity(view.context)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            if (settings.fullscreenGame) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    var snapshot by remember(session) { mutableStateOf<MapSnapshot?>(null) }
    LaunchedEffect(session) {
        GameController.dataVersion.collectLatest { version ->
            delay(60)
            snapshot = withContext(Dispatchers.Default) { MapSnapshot.build(session, version) }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var messageDialog by remember { mutableStateOf<UiMessage?>(null) }
    LaunchedEffect(session) {
        GameController.messages.collect { message ->
            if (message.isError) {
                snackbarHostState.currentSnackbarData?.dismiss()
                launch { snackbarHostState.showSnackbar(message.text, duration = SnackbarDuration.Short) }
            } else {
                messageDialog = message
            }
        }
    }

    // interaction state
    var selectedTerritory by remember(session) { mutableStateOf<String?>(null) }
    var moveFrom by remember(session) { mutableStateOf<Territory?>(null) }
    var moveUnits by remember(session) { mutableStateOf<List<Unit>>(emptyList()) }
    var movePlan by remember(session) { mutableStateOf<MovePlan?>(null) }
    var unitPicker by remember(session) { mutableStateOf<UnitPickerSpec?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showQuitDialog by remember { mutableStateOf(false) }
    // the phone's back button: same question as "Quit to menu"; pages on top have their own handler
    BackHandler(enabled = !showQuitDialog) { showQuitDialog = true }
    var kamikazeConfirm by remember { mutableStateOf<MovePlan?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHowTo by remember { mutableStateOf(false) }
    var showCalc by remember { mutableStateOf(false) }
    /** The calculator waits for a tap on the map that chooses its territory. */
    var calcPicking by remember { mutableStateOf(false) }
    var calcAttacker by remember(session) { mutableStateOf<String?>(null) }
    var calcDefender by remember(session) { mutableStateOf<String?>(null) }
    var gameNotes by remember { mutableStateOf<String?>(null) }
    var showMoves by remember { mutableStateOf(false) }
    /** The purchase screen can be put away to look at the map while the phase stays open. */
    var purchaseHidden by remember(pending) { mutableStateOf(true) }
    val purchaseCounts = remember(pending) { mutableStateMapOf<ProductionRule, Int>() }
    /** Route of a move from the undo list shown on the map until the next tap. */
    var previewRoute by remember(session) { mutableStateOf<List<String>>(emptyList()) }
    /** The AI move the map is following right now (only while the AI pauses between moves). */
    var aiMove by remember(session) { mutableStateOf<GameController.AiMove?>(null) }
    LaunchedEffect(session) {
        GameController.aiMoves.collectLatest { move ->
            // with the pause at 0 the AI plays at full speed and the map stays where it is
            val pause = AppSettings.current.aiMovePauseMillis
            if (pause <= 0) return@collectLatest
            val byName = snapshot?.byName ?: return@collectLatest
            val points = move.route.mapNotNull { byName[it] }.map { Offset(it.centerX.toFloat(), it.centerY.toFloat()) }
            if (points.size < 2) return@collectLatest
            previewRoute = move.route
            aiMove = move
            mapState.focusOn(points)
            // keep the route visible for the pause the AI takes after the move, then clear it
            delay(pause.toLong() + 300)
            if (previewRoute == move.route) previewRoute = emptyList()
            aiMove = null
        }
    }
    var lastAutosaveRound by remember(session) { mutableStateOf(0) }
    var hiddenBattleId by remember(session) { mutableStateOf<UUID?>(null) }
    var phaseEndConfirm by remember(session) { mutableStateOf<PhaseEndConfirm?>(null) }

    // a short banner whenever the phase or the player changes, so transitions are noticeable
    var banner by remember(session) { mutableStateOf<PhaseBanner?>(null) }
    var bannerVisible by remember(session) { mutableStateOf(false) }
    LaunchedEffect(status.stepName, status.playerName) {
        if (status.stepDisplayName.isBlank() || !settings.showPhaseBanner) return@LaunchedEffect
        val politicsStep = GameStep.isPoliticsStepName(status.stepName) || GameStep.isUserActionsStepName(status.stepName)
        if (politicsStep && snapshot?.hasPolitics == false) return@LaunchedEffect
        banner = PhaseBanner(status.playerName, status.stepDisplayName, status.round, status.isHumanTurn)
        bannerVisible = true
        delay(2600)
        bannerVisible = false
    }

    // reset move state whenever the pending request changes
    LaunchedEffect(pending) {
        moveFrom = null
        moveUnits = emptyList()
        movePlan = null
        unitPicker = null
    }

    // autosave once per round, at the first question of a human turn (the game thread is blocked then)
    LaunchedEffect(pending) {
        val request = pending
        val firstHumanQuestion = request is PurchaseRequest || request is MoveRequest || request is PlaceRequest
        if (!settings.autosaveEachRound || !firstHumanQuestion || status.round <= lastAutosaveRound) return@LaunchedEffect
        lastAutosaveRound = status.round
        withContext(Dispatchers.IO) {
            runCatching { GameController.saveGame(MobileEngine.getSaveGamesFolder().resolve("autosave.tsvg")) }
        }
    }

    // a new battle: show its window and bring the territory into view
    val currentBattle = battle
    LaunchedEffect(currentBattle?.id) {
        val b = currentBattle ?: return@LaunchedEffect
        val center = snapshot?.byName?.get(b.territory) ?: return@LaunchedEffect
        mapState.centerOn(center.centerX.toFloat(), center.centerY.toFloat())
    }
    val casualtyNotice = pending as? CasualtyNoticeRequest
    /** A loss choice for the battle on screen is made inside the battle window, not in a dialog. */
    val casualtyRequest = (pending as? CasualtyRequest)?.takeIf { it.battleId == currentBattle?.id }
    /** Retreat, bombardment, raid and target questions about the battle on screen: asked in its footer. */
    val battleQuestion: UiRequest<*>? = when (val r = pending) {
        is RetreatRequest -> r.takeIf { currentBattle != null && it.battleId == currentBattle.id }
        is ConfirmRequest -> r.takeIf { currentBattle != null && it.territory != null && it.territory == currentBattle.territory }
        is SelectUnitsRequest -> r.takeIf { currentBattle != null && it.territory != null && it.territory == currentBattle.territory }
        else -> null
    }
    val battleStrengths by produceState<BattleStrengths?>(
        initialValue = null,
        currentBattle?.id, currentBattle?.attackingUnits, currentBattle?.defendingUnits,
    ) {
        val b = currentBattle
        value = if (b == null) null else withContext(Dispatchers.Default) {
            BattleStrength.compute(session, b.attackingUnits, b.defendingUnits, b.territory)
        }
    }
    // a waiting casualty report always brings the window back, otherwise the game would hang hidden
    val battleVisible = currentBattle != null && (currentBattle.id != hiddenBattleId || casualtyNotice != null || casualtyRequest != null || battleQuestion != null)

    // one message at a time: a new one replaces the old instead of queueing up for minutes
    val toastJob = remember { mutableStateOf<Job?>(null) }
    fun toast(text: String) {
        toastJob.value?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        toastJob.value = scope.launch { snackbarHostState.showSnackbar(text, duration = SnackbarDuration.Short) }
    }

    fun clearSelection() {
        moveFrom = null
        moveUnits = emptyList()
        movePlan = null
    }

    fun planMove(request: MoveRequest, from: Territory, to: Territory, units: List<Unit>, transports: List<Unit>? = null) {
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                MoveHelper.plan(session, request.player, request.nonCombat, from, to, units, transports)
            }
            result.onSuccess { plan ->
                if (transports == null && plan.transportChoices.size > 1) {
                    // several transports with room: the player picks the ones to load, like on the desktop
                    unitPicker = UnitPickerSpec(
                        title = "Load onto",
                        message = "",
                        units = plan.transportChoices,
                        max = plan.transportChoices.size,
                        initialSelection = plan.transportChoices.groupBy { unitGroupKey(it) }.mapValues { it.value.size },
                        onConfirm = { chosen ->
                            unitPicker = null
                            if (chosen.isEmpty()) movePlan = plan else planMove(request, from, to, units, chosen)
                        },
                        onCancel = { unitPicker = null; movePlan = plan },
                    )
                } else {
                    movePlan = plan
                }
            }.onFailure {
                movePlan = null
                toast(it.message ?: "Cannot move there")
            }
        }
    }

    /** One tap on a unit icon: takes one more unit of that stack along, wraps around to none. */
    fun tapStack(request: MoveRequest, territory: Territory, stack: UnitStack) {
        selectedTerritory = territory.name
        val movable = MoveHelper.movableUnits(session, request.player, territory).toHashSet()
        val inStack = stack.units.filter { it in movable }
        if (inStack.isEmpty()) {
            // enemy or allied units: nothing to say, the territory is simply selected
            if (stack.ownerName == request.player.name) toast("${stack.typeName} in ${territory.name} cannot move any more")
            return
        }
        movePlan = null
        if (moveFrom != territory) {
            moveFrom = territory
            moveUnits = listOf(inStack.first())
            return
        }
        val chosen = moveUnits.toHashSet()
        val next = inStack.firstOrNull { it !in chosen }
        if (next != null) {
            moveUnits = moveUnits + next
        } else {
            // every unit of this stack is selected: tapping again deselects the stack
            val stackSet = inStack.toHashSet()
            moveUnits = moveUnits.filter { it !in stackSet }
            if (moveUnits.isEmpty()) moveFrom = null
        }
    }

    /** The unit chooser for a territory (double tap / long press). */
    fun openUnitMenu(territory: Territory) {
        val name = territory.name
        when (val request = pending) {
            is MoveRequest -> {
                val units = MoveHelper.movableUnits(session, request.player, territory)
                if (units.isEmpty()) {
                    selectedTerritory = name
                    toast("No units of ${request.player.name} that can still move in $name")
                    return
                }
                val preselected = if (moveFrom == territory) moveUnits.toHashSet() else units.toHashSet()
                unitPicker = UnitPickerSpec(
                    title = name,
                    message = "",
                    units = units,
                    max = units.size,
                    initialSelection = units.filter { it in preselected }
                        .groupBy { unitGroupKey(it) }
                        .mapValues { it.value.size },
                    onConfirm = { chosen ->
                        unitPicker = null
                        movePlan = null
                        moveFrom = territory
                        moveUnits = chosen
                        selectedTerritory = name
                    },
                    onCancel = { unitPicker = null },
                )
            }
            is PlaceRequest -> {
                selectedTerritory = name
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        MoveHelper.placeableUnits(session, request.player, territory)
                    }
                    result.onFailure { toast(it.message ?: "Cannot place here") }
                    result.onSuccess { placeable ->
                        if (placeable.isError) {
                            toast(placeable.errorMessage ?: "Cannot place here")
                        } else if (placeable.units.isEmpty()) {
                            toast("Nothing can be placed in $name")
                        } else {
                            fun isConstruction(unit: Unit) = runCatching { unit.type.unitAttachment.isConstruction }.getOrDefault(false)
                            val regular = placeable.units.count { !isConstruction(it) }
                            val constructions = placeable.units.size - regular
                            // the engine's limit (factory production) applies to regular units only
                            val max = if (placeable.maxUnits < 0) regular else minOf(placeable.maxUnits, regular)
                            unitPicker = UnitPickerSpec(
                                title = name,
                                message = when {
                                    placeable.maxUnits < 0 -> "No production limit here: an original factory of its first owner may place any number of units (engine rule)."
                                    constructions > 0 -> "Production allows $max unit(s) here; the $constructions construction(s) do not count."
                                    else -> "Production allows $max unit(s) here."
                                },
                                units = placeable.units.toList(),
                                max = max,
                                countsTowardMax = { !isConstruction(it) },
                                onConfirm = { chosen ->
                                    unitPicker = null
                                    request.complete(Optional.of(PlaceData(chosen, territory)))
                                },
                                onCancel = { unitPicker = null },
                            )
                        }
                    }
                }
            }
            is SelectTerritoryRequest -> {
                if (request.candidates.contains(territory)) request.complete(Optional.of(territory))
                else toast("$name is not a valid choice")
            }
            else -> selectedTerritory = name
        }
    }

    fun onTap(tap: MapTap) {
        previewRoute = emptyList()
        val name = tap.territory ?: return
        val territory = MoveHelper.territory(session, name) ?: return
        if (calcPicking) {
            // the tap chooses the calculator's territory and brings the calculator back
            selectedTerritory = name
            calcPicking = false
            showCalc = true
            return
        }
        when (val request = pending) {
            is MoveRequest -> {
                val from = moveFrom
                if (from != null && moveUnits.isNotEmpty() && territory != from) {
                    // units are selected: every other territory is a destination, even when the
                    // finger lands on the enemy units standing there
                    selectedTerritory = name
                    planMove(request, from, territory, moveUnits)
                    return
                }
                val stack = tap.stack
                if (stack != null && stack.territoryName == name) {
                    tapStack(request, territory, stack)
                    return
                }
                selectedTerritory = name
                if (territory == from) movePlan = null
            }
            is SelectTerritoryRequest -> {
                if (request.candidates.contains(territory)) request.complete(Optional.of(territory))
                else toast("$name is not a valid choice")
            }
            is PlaceRequest -> openUnitMenu(territory)
            else -> selectedTerritory = name
        }
    }

    fun onDoubleTap(tap: MapTap) {
        val name = tap.territory ?: return
        val territory = MoveHelper.territory(session, name) ?: return
        openUnitMenu(territory)
    }

    /** Centers the map on the current nation's capital (first owned one, else its original one). */
    fun jumpToCapital() {
        val name = status.playerName
        val target = runCatching {
            session.gameData.acquireReadLock().use {
                val player = session.gameData.playerList.getPlayerId(name) ?: return@use null
                TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, session.gameData.map).orElse(null)?.name
            }
        }.getOrNull() ?: return
        val center = snapshot?.byName?.get(target) ?: return
        selectedTerritory = target
        mapState.centerOn(center.centerX.toFloat(), center.centerY.toFloat())
    }

    fun performMove(plan: MovePlan) {
        (pending as? MoveRequest)?.complete(Optional.of(plan.description))
        clearSelection()
    }

    fun confirmMove(plan: MovePlan) {
        if (plan.lostAir.isNotEmpty()) {
            // the rules allow the move, but the planes will not come back: ask first
            kamikazeConfirm = plan
        } else {
            performMove(plan)
        }
    }


    val madeMoves: List<MadeMove> = remember(snapshot, pending) {
        when (pending) {
            is MoveRequest -> MoveHelper.movesMade(session)
            is PlaceRequest -> MoveHelper.placementsMade(session)
            else -> emptyList()
        }
    }

    fun undoMove(move: MadeMove) {
        previewRoute = emptyList()
        scope.launch {
            val error = withContext(Dispatchers.Default) {
                when (pending) {
                    is MoveRequest -> MoveHelper.undoMoveAt(session, move.index)
                    is PlaceRequest -> MoveHelper.undoPlacementAt(session, move.index)
                    else -> null
                }
            }
            if (error != null) toast(error)
        }
    }

    fun undoAllMoves() {
        previewRoute = emptyList()
        scope.launch {
            withContext(Dispatchers.Default) {
                // newest first, so dependencies are released before the moves they depend on
                var remaining = if (pending is MoveRequest) MoveHelper.movesMade(session) else MoveHelper.placementsMade(session)
                var guard = 0
                while (remaining.isNotEmpty() && guard++ < 200) {
                    val target = remaining.lastOrNull { it.canUndo } ?: break
                    val error = if (pending is MoveRequest) MoveHelper.undoMoveAt(session, target.index) else MoveHelper.undoPlacementAt(session, target.index)
                    if (error != null) break
                    remaining = if (pending is MoveRequest) MoveHelper.movesMade(session) else MoveHelper.placementsMade(session)
                }
            }
        }
    }

    // derived rendering data
    val highlighted = when (val request = pending) {
        is SelectTerritoryRequest -> request.candidates.map { it.name }.toSet()
        is RetreatRequest -> request.possibleTerritories.map { it.name }.toSet()
        else -> emptySet()
    }
    val selectedUnitSet = remember(moveUnits) { moveUnits.toHashSet() }
    val routePoints: List<Offset> = remember(movePlan, previewRoute, snapshot) {
        val byName = snapshot?.byName ?: return@remember emptyList()
        val names = movePlan?.description?.route?.allTerritories?.map { it.name } ?: previewRoute
        names.mapNotNull { name -> byName[name]?.let { Offset(it.centerX.toFloat(), it.centerY.toFloat()) } }
    }
    val battleSites = buildSet {
        snapshot?.battleSites?.let { addAll(it) }
        currentBattle?.takeIf { !it.ended }?.let { add(it.territory) }
    }
    val battlePulse = if (battleSites.isEmpty()) 0f else {
        val transition = rememberInfiniteTransition(label = "battle")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label = "battlePulse",
        ).value
    }
    val resourceLine = remember(status.playerName, snapshot) {
        runCatching {
            session.gameData.acquireReadLock().use {
                session.gameData.playerList.getPlayerId(status.playerName)?.resources?.toString()
            }
        }.getOrNull().orEmpty()
    }
    val selectedSnapshot = selectedTerritory?.let { snapshot?.byName?.get(it) }
    val territoryInfo = selectedSnapshot?.let { territory ->
        buildString {
            append(territory.name)
            if (!territory.isWater) {
                append("  ·  ").append(territory.ownerName)
                append("  ·  ").append(territory.production).append(" PUs")
                if (territory.isVictoryCity) append("  ·  ★ victory city")
            }
        }
    }
    // the steps of the current nation's turn, for the indicator at the top
    // one entry per kind of phase (purchase, combat move, battle, ...): maps often split a phase
    // into several engine steps, which would show as too many dots
    // The strip shows only the main phases (purchase, combat move, non-combat move, place, end
    // turn). Optional phases (tech, politics, user actions, battles, bids) are folded into the
    // main phase before them, and show as a small extra marker while they run.
    val playerSteps: List<Pair<String, String>> = remember(status.playerName, session) {
        runCatching {
            session.gameData.sequence
                .filter { it.playerId?.name == status.playerName }
                .map { (it.name ?: "") to (it.displayName ?: "") }
        }.getOrDefault(emptyList())
    }
    val turnSteps: List<Pair<String, String>> = remember(playerSteps) {
        val byKind = LinkedHashMap<String, Pair<String, String>>()
        playerSteps.forEach { (name, display) ->
            val kind = stepKind(name) ?: return@forEach
            if (kind in MAIN_PHASES && !name.contains("bid", ignoreCase = true)) byKind.putIfAbsent(kind, name to display)
        }
        byKind.values.toList()
    }
    val currentKind = stepKind(status.stepName)
    val currentTurnStep = remember(turnSteps, playerSteps, status.stepName) {
        val kind = currentKind
        if (kind in MAIN_PHASES && !status.stepName.contains("bid", ignoreCase = true)) {
            turnSteps.firstOrNull { stepKind(it.first) == kind }?.first ?: ""
        } else {
            // optional or bid step: the closest main phase before it in this nation's sequence,
            // or the first main phase when the turn starts with an optional one
            val index = playerSteps.indexOfFirst { it.first == status.stepName }
            val before = playerSteps.take(if (index >= 0) index else 0).lastOrNull { stepKind(it.first) in MAIN_PHASES && !it.first.contains("bid", true) }
            val mainKind = before?.let { stepKind(it.first) } ?: turnSteps.firstOrNull()?.let { stepKind(it.first) }
            turnSteps.firstOrNull { stepKind(it.first) == mainKind }?.first ?: ""
        }
    }
    val optionalPhase = if (currentKind != null && (currentKind !in MAIN_PHASES || status.stepName.contains("bid", true))) status.stepDisplayName else ""
    val aiSeconds by GameController.aiThinkingSeconds.collectAsState()
    val hint = phaseHint(session, pending, aiSeconds, status, gameOver, moveFrom, moveUnits, movePlan)
    val playerColor = remember(status.playerName, session) {
        runCatching { Color(session.mapData.getPlayerColor(status.playerName).rgb) }.getOrNull()
    }

    val menu: @Composable () -> kotlin.Unit = {
        Box {
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "menu") }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (showDetails) "Hide details panel" else "Show details panel") },
                    onClick = { showMenu = false; showDetails = !showDetails },
                )
                DropdownMenuItem(text = { Text("Save game") }, onClick = { showMenu = false; showSaveDialog = true })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { showMenu = false; showSettings = true })
                DropdownMenuItem(
                    text = { Text("Game notes") },
                    onClick = {
                        showMenu = false
                        scope.launch {
                            val notes = withContext(Dispatchers.IO) {
                                runCatching {
                                    val xml = GameController.gameXmlPath
                                        ?: MobileEngine.listInstalledGames().firstOrNull { it.gameName == session.gameData.gameName }?.xmlPath
                                    xml?.let { GameController.stripHtml(GameNotes.loadGameNotes(it)) }
                                }.getOrNull()
                            }
                            if (notes.isNullOrBlank()) toast("This map has no game notes") else gameNotes = notes
                        }
                    },
                )
                DropdownMenuItem(text = { Text("Battle calculator") }, onClick = { showMenu = false; showCalc = true })
                DropdownMenuItem(text = { Text("How to play") }, onClick = { showMenu = false; showHowTo = true })
                DropdownMenuItem(text = { Text("Quit to menu") }, onClick = { showMenu = false; showQuitDialog = true })
            }
        }
    }

    // keep floating elements away from rounded display corners and camera cutouts (fullscreen has no bar insets)
    val cornerInset = roundedCornerInset()
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val edgeStart = maxOf(cutout.calculateStartPadding(layoutDirection), cornerInset, 8.dp)
    val edgeEnd = maxOf(cutout.calculateEndPadding(layoutDirection), cornerInset, 8.dp)
    val edgeTop = maxOf(cutout.calculateTopPadding(), 6.dp)
    val edgeBottom = maxOf(cutout.calculateBottomPadding(), 6.dp)

    /** Flag of the owner, name and value of the tapped territory; top right in landscape, bottom left in portrait. */
    val territoryChip: @Composable () -> kotlin.Unit = {
        selectedSnapshot?.let { t ->
            val ownerFlag = remember(t.ownerName, images) {
                if (t.isWater) null else images.getNow("flags/${t.ownerName}.png", listOf("flags/${t.ownerName}.png", "flags/${t.ownerName}_small.png"))
            }
            OverlayChip {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ownerFlag != null) {
                        Image(ownerFlag.asImageBitmap(), contentDescription = t.ownerName, modifier = Modifier.height(20.dp).widthIn(max = 34.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    t.effects.forEach { effect ->
                        val icon = remember(effect.name, images) { images.getNow(effect.imagePaths.last(), effect.imagePaths) }
                        if (icon != null) {
                            Spacer(Modifier.width(6.dp))
                            Image(icon.asImageBitmap(), contentDescription = effect.name, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (!t.isWater) {
                        Spacer(Modifier.width(10.dp))
                        Surface(color = Color(0xFF2B2B2B), shape = MaterialTheme.shapes.small) {
                            Text(
                                (if (t.isVictoryCity) "★ " else "") + "${t.production} PU",
                                color = Color(0xFFFFE178),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    val mapArea: @Composable (Modifier) -> kotlin.Unit = { areaModifier ->
        Box(areaModifier) {
            MapView(
                snapshot = snapshot,
                mapData = session.mapData,
                images = images,
                state = mapState,
                selectedTerritory = selectedTerritory,
                originTerritory = moveFrom?.name,
                destinationTerritory = movePlan?.to?.name,
                highlighted = highlighted,
                selectedUnits = selectedUnitSet,
                route = routePoints,
                routeSteps = movePlan?.description?.route?.numberOfSteps() ?: (previewRoute.size - 1).coerceAtLeast(0),
                battleSites = battleSites,
                battlePulse = battlePulse,
                showTerritoryNames = settings.showTerritoryNames,
                showTerritoryValues = settings.showTerritoryValues,
                unitScale = settings.unitScale,
                counterScale = settings.counterScale,
                showRelief = settings.showRelief,
                qualityFactor = settings.mapQuality.extraSample,
                onTap = ::onTap,
                onDoubleTap = ::onDoubleTap,
                onLongPress = ::onDoubleTap,
                modifier = Modifier.fillMaxSize(),
            )

            // top left: round / player / step (landscape only, portrait has the app bar) and the hint
            // top of the map: the turn progress strip alone at the top center, the nation below it on
            // the left, menu and the tapped territory on the right with room to breathe
            if (calcPicking) {
                // while the calculator waits for a territory: a hint with a way out
                OverlayChip(Modifier.align(Alignment.TopCenter).padding(top = edgeTop + if (landscape) 30.dp else 48.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tap a territory for the calculator", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = { calcPicking = false; showCalc = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "cancel", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (turnSteps.isNotEmpty()) {
                TurnStepStrip(
                    steps = turnSteps,
                    currentStep = currentTurnStep,
                    optional = optionalPhase,
                    suffix = if (!status.isHumanTurn && aiSeconds >= 5) "${aiSeconds}s" else "",
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = if (landscape) edgeTop else 0.dp),
                )
            }
            if (landscape && !desktop) {
                OverlayChip(Modifier.align(Alignment.TopStart).padding(start = edgeStart, top = edgeTop)) {
                    PlayerHeader(
                        status = status,
                        gameName = session.gameData.gameName,
                        resourceLine = resourceLine,
                        images = images,
                        playerColor = playerColor,
                        separator = " · ",
                        compact = true,
                        onFlagTap = ::jumpToCapital,
                    )
                }
            }
            if (landscape && !desktop) {
                // nation (left), turn strip (middle), tapped territory and menu (right): one row
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = edgeTop, end = edgeEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.widthIn(max = 240.dp)) { territoryChip() }
                    Spacer(Modifier.width(4.dp))
                    menu()
                }
            }
            AnimatedVisibility(
                visible = bannerVisible && banner != null,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = edgeTop + if (landscape) 52.dp else 8.dp, start = edgeStart, end = edgeEnd),
            ) {
                banner?.let { PhaseBannerCard(it, images, playerColor) }
            }

            // turn progress: one symbol per step of this nation's turn, the current one lit
            // the battle window
            if (currentBattle != null && battleVisible) {
                // the window may use the space between the turn strip and the action buttons at
                // the bottom, never more: on small screens its middle part scrolls instead. The
                // action row only holds buttons in the move, place, purchase and end turn phases.
                val actionsBelow = !desktop && (pending is MoveRequest || pending is PlaceRequest || pending is PurchaseRequest || pending is EndTurnRequest)
                BoxWithConstraints(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxSize()
                        .padding(
                            top = edgeTop + if (landscape) 30.dp else 48.dp,
                            start = edgeStart,
                            end = edgeEnd,
                            bottom = edgeBottom + if (actionsBelow) 60.dp else 8.dp,
                        ),
                ) {
                    BattleWindow(
                        battle = currentBattle,
                        images = images,
                        compact = landscape && !desktop,
                        notice = casualtyNotice,
                        casualtyRequest = casualtyRequest,
                        question = battleQuestion,
                        showHelp = settings.showBattleHelp,
                        onDismissHelp = { AppSettings.update { it.copy(showBattleHelp = false) } },
                        strengths = battleStrengths,
                        colorOf = { name -> runCatching { Color(session.mapData.getPlayerColor(name).rgb) }.getOrNull() },
                        onDismiss = { hiddenBattleId = currentBattle.id },
                        modifier = Modifier.align(Alignment.TopCenter).heightIn(max = maxHeight),
                    )
                }
            }

            // bottom: move confirmation, phase actions (right) and the territory status line
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = edgeStart, end = edgeEnd, bottom = edgeBottom)) {
                aiMove?.let { move ->
                    // what the AI just moved: flag, units, from -> to
                    val flag = remember(move.player, images) {
                        images.getNow("flags/${move.player}.png", listOf("flags/${move.player}.png", "flags/${move.player}_small.png"))
                    }
                    OverlayChip(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).widthIn(max = 420.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (flag != null) {
                                Image(flag.asImageBitmap(), contentDescription = move.player, modifier = Modifier.height(18.dp).widthIn(max = 30.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            move.units.groupBy { it.type to it.owner }.entries.take(4).forEach { (key, units) ->
                                UnitIcon(images, key.first, key.second, size = 22)
                                Text("×${units.size} ", style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                "${move.route.first()} → ${move.route.last()}",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                movePlan?.let { plan ->
                    MoveConfirmCard(
                        plan = plan,
                        images = images,
                        onConfirm = { confirmMove(plan) },
                        onCancel = { movePlan = null },
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                phaseEndConfirm?.let { confirm ->
                    // the X keeps the phase open, the check ends it
                    PhaseEndCard(
                        confirm = confirm,
                        onCancel = { phaseEndConfirm = null },
                        onConfirm = { phaseEndConfirm = null; confirm.proceed() },
                        modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (!desktop) Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (!landscape) {
                        Box(Modifier.weight(1f).padding(end = 8.dp), contentAlignment = Alignment.BottomStart) { territoryChip() }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhaseActions(
                        session = session,
                        pendingRequest = pending,
                        gameOver = gameOver,
                        hasSelection = moveFrom != null,
                        onClearSelection = ::clearSelection,
                        onUndo = { showMoves = true },
                        onDone = { confirm -> if (settings.confirmPhaseEnd) phaseEndConfirm = confirm else confirm.proceed() },
                        onQuit = onQuit,
                        movesCount = madeMoves.size,
                        onOpenPurchase = { purchaseHidden = false },
                    )
                    }
                }
            }
        }
    }

    /** The side panel of every layout; the desktop layout adds the flag row with the menu and the action buttons. */
    val sidePanel: @Composable (showHeader: Boolean) -> kotlin.Unit = { showHeader ->
        SidePanel(
            status = status,
            gameName = session.gameData.gameName,
            resourceLine = resourceLine,
            images = images,
            playerColor = playerColor,
            hint = if (showHeader) hint else "",
            territory = selectedSnapshot,
            moveFrom = moveFrom,
            moveUnits = moveUnits,
            battle = currentBattle,
            stats = snapshot?.stats ?: emptyList(),
            showVictoryCities = snapshot?.hasVictoryCities ?: false,
            history = snapshot?.history ?: emptyList(),
            relationships = if (snapshot?.hasPolitics == true) snapshot?.relationships ?: emptyList() else emptyList(),
            menu = menu,
            showHeader = showHeader,
            onFlagTap = ::jumpToCapital,
            actions = {
                if (showHeader) {
                    PhaseActions(
                        session = session,
                        pendingRequest = pending,
                        gameOver = gameOver,
                        hasSelection = moveFrom != null,
                        onClearSelection = ::clearSelection,
                        onUndo = { showMoves = true },
                        onDone = { confirm -> if (settings.confirmPhaseEnd) phaseEndConfirm = confirm else confirm.proceed() },
                        onQuit = onQuit,
                        fullWidth = true,
                        movesCount = madeMoves.size,
                        onOpenPurchase = { purchaseHidden = false },
                    )
                }
            },
            movesThisPhase = {
                if (pending is MoveRequest || pending is PlaceRequest) {
                    Text(
                        if (pending is PlaceRequest) "Placements this phase" else "Moves this phase",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                    MovesList(
                        moves = madeMoves,
                        images = images,
                        onShowRoute = { previewRoute = it.routeTerritories },
                        onUndo = ::undoMove,
                        compact = true,
                    )
                }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
    if (desktop) {
        // tablet layout like the desktop client: map plus a permanent tabbed panel on the right
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                mapArea(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                Surface(tonalElevation = 2.dp, modifier = Modifier.width(DESKTOP_PANEL_WIDTH).fillMaxHeight()) {
                    sidePanel(true)
                }
            }
        }
    } else if (landscape) {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                mapArea(Modifier.weight(1f).fillMaxHeight())
                if (showDetails) {
                    VerticalDivider()
                    Surface(tonalElevation = 3.dp, modifier = Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight()) {
                        sidePanel(false)
                    }
                }
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        PlayerHeader(
                            status = status,
                            gameName = session.gameData.gameName,
                            resourceLine = resourceLine,
                            images = images,
                            playerColor = playerColor,
                            separator = "  ·  ",
                            large = true,
                            compact = true,
                            onFlagTap = ::jumpToCapital,
                        )
                    },
                    actions = { menu() },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                mapArea(Modifier.weight(1f).fillMaxWidth())
                if (showDetails) {
                    HorizontalDivider()
                    val panelHeight = (configuration.screenHeightDp * 0.42f).dp
                    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().height(panelHeight)) {
                        sidePanel(false)
                    }
                }
            }
        }
    }

    // full screen pages over the game; plain composables, not dialog windows, so they survive
    // orientation, text size and layout changes made inside them
    if (showSettings) FullScreenPage(onBack = { showSettings = false }) { SettingsScreen(onBack = { showSettings = false }) }
    if (showHowTo) FullScreenPage(onBack = { showHowTo = false }) { HowToPlayScreen(onBack = { showHowTo = false }) }
    if (showCalc) FullScreenPage(onBack = { showCalc = false }) {
        BattleCalcScreen(
            session = session,
            images = images,
            territoryName = selectedTerritory,
            attackerName = calcAttacker ?: status.playerName,
            defenderName = calcDefender,
            onBack = { showCalc = false },
            onPickOnMap = { showCalc = false; calcPicking = true },
            onNations = { a, d -> calcAttacker = a; calcDefender = d },
        )
    }
    gameNotes?.let { notes ->
        FullScreenPage(onBack = { gameNotes = null }) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { gameNotes = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                    Text("Game notes: ${session.gameData.gameName}", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    notes,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
    }

    // dialogs driven by engine questions
    when (val request = pending) {
        is PurchaseRequest -> {
            val capacity = remember(request) { MoveHelper.placementCapacity(session, request.player) }
            if (!purchaseHidden) {
                PurchaseDialog(request, images, capacity, counts = purchaseCounts, onShowMap = { purchaseHidden = true })
            }
        }
        is BattleRequest -> BattleListDialog(request)
        is ConfirmRequest -> if (battleQuestion == null) ConfirmDialog(request)
        is CasualtyRequest -> if (casualtyRequest == null) CasualtyDialog(request, images)
        is SelectTerritoryRequest -> TerritoryPickerDialog(request)
        is RetreatRequest -> if (battleQuestion == null) RetreatDialog(request)
        is PoliticsRequest -> PoliticsDialog(request, session)
        is UserActionRequest -> UserActionDialog(request, session)
        is CasualtyNoticeRequest -> {
            // shown inside the battle window; without a battle window, continue right away
            if (currentBattle == null) LaunchedEffect(request) { request.complete(true) }
        }
        is SelectUnitsRequest -> if (battleQuestion == null) UnitPickerDialog(
            UnitPickerSpec(
                title = request.title,
                message = request.message,
                units = request.candidates,
                max = request.max,
                onConfirm = { request.complete(it) },
                onCancel = { request.complete(emptyList()) },
            ),
            images,
        )
        else -> {}
    }
    unitPicker?.let { UnitPickerDialog(it, images) }
    messageDialog?.let { MessageDialog(it.title, it.text) { messageDialog = null } }
    if (showMoves) {
        MovesDialog(
            title = if (pending is PlaceRequest) "Placements this phase" else "Moves this phase",
            moves = madeMoves,
            images = images,
            onShowRoute = { move ->
                previewRoute = move.routeTerritories
                showMoves = false
                snapshot?.byName?.get(move.routeTerritories.last())?.let { mapState.centerOn(it.centerX.toFloat(), it.centerY.toFloat()) }
            },
            onUndo = ::undoMove,
            onUndoAll = ::undoAllMoves,
            onClose = { showMoves = false },
        )
    }

    if (showSaveDialog) {
        SaveGameDialog(
            defaultName = "${session.gameData.gameName} R${status.round}".replace(Regex("[^A-Za-z0-9 _-]"), ""),
            onSave = { name ->
                showSaveDialog = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { GameController.saveGame(MobileEngine.getSaveGamesFolder().resolve("$name.tsvg")) }
                    }
                    toast(if (result.isSuccess) "Saved as $name" else "Save failed: ${result.exceptionOrNull()?.message}")
                }
            },
            onCancel = { showSaveDialog = false },
        )
    }
    kamikazeConfirm?.let { plan ->
        val lost = plan.lostAir.groupBy { it.type.name }.entries.joinToString(", ") { (name, units) -> "${units.size} $name" }
        val ask = remember(plan) {
            ConfirmRequest(
                "Planes cannot return",
                "$lost cannot reach a friendly landing spot from ${plan.to.name} and will be lost at the end of the turn. " +
                    "The rules of this game allow it (Kamikaze Airplanes). Move anyway?",
            )
        }
        LaunchedEffect(ask) {
            val yes = withContext(Dispatchers.IO) { runCatching { ask.result.get() }.getOrDefault(false) }
            kamikazeConfirm = null
            if (yes) performMove(plan)
        }
        ConfirmDialog(ask)
    }
    if (showQuitDialog) {
        val quit = ConfirmRequest("Quit game?", "Unsaved progress is lost.")
        LaunchedEffect(quit) {
            val yes = withContext(Dispatchers.IO) { runCatching { quit.result.get() }.getOrDefault(false) }
            showQuitDialog = false
            if (yes) {
                GameController.quit()
                onQuit()
            }
        }
        ConfirmDialog(quit)
    }
}

/**
 * How far floating content must stay from the left/right edge so the rounded display corners
 * (Pixel phones) do not cut it off: about 0.6 of the largest corner radius.
 */
@Composable
private fun roundedCornerInset(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    // the system reports the corner radius from Android 12 on; older devices get a small margin
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return 12.dp
    val insets = view.rootWindowInsets ?: return 0.dp
    val radius = listOf(
        RoundedCorner.POSITION_TOP_LEFT,
        RoundedCorner.POSITION_TOP_RIGHT,
        RoundedCorner.POSITION_BOTTOM_LEFT,
        RoundedCorner.POSITION_BOTTOM_RIGHT,
    ).mapNotNull { insets.getRoundedCorner(it)?.radius }.maxOrNull() ?: 0
    return with(density) { (radius * 0.6f).toDp() }
}

private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** What the phase banner announces. */
class PhaseBanner(val player: String, val step: String, val round: Int, val human: Boolean)

/**
 * The phase announcement: the nation's flag and color, the phase in large type, nation and round
 * below, on a dark translucent card that slides in from the top.
 */
@Composable
private fun PhaseBannerCard(banner: PhaseBanner, images: ImageCache, playerColor: androidx.compose.ui.graphics.Color?) {
    val flag = remember(banner.player, images) {
        if (banner.player.isBlank()) null
        else images.getNow("flags/${banner.player}.png", listOf("flags/${banner.player}.png", "flags/${banner.player}_large.png"))
    }
    val accent = playerColor ?: MaterialTheme.colorScheme.primary
    Surface(
        color = androidx.compose.ui.graphics.Color(0xF0161A20),
        contentColor = androidx.compose.ui.graphics.Color.White,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 8.dp,
        modifier = Modifier.widthIn(min = 260.dp, max = 440.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 10.dp)) {
                if (flag != null) {
                    Image(
                        flag.asImageBitmap(),
                        contentDescription = banner.player,
                        modifier = Modifier.height(34.dp).widthIn(max = 56.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                } else {
                    Box(Modifier.size(34.dp).background(accent, MaterialTheme.shapes.small))
                    Spacer(Modifier.width(14.dp))
                }
                Column {
                    Text(
                        banner.step,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(banner.player)
                            if (banner.round > 0) append("  ·  Round ").append(banner.round)
                            if (!banner.human) append("  ·  AI")
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = androidx.compose.ui.graphics.Color(0xFFD5D9E0),
                    )
                }
            }
            // the nation's map color as a bar along the bottom edge
            Box(Modifier.fillMaxWidth().height(4.dp).background(accent))
        }
    }
}

/** One line telling the player what to do in the current phase. */
private fun phaseHint(
    session: LocalGameSession,
    pending: UiRequest<*>?,
    aiSeconds: Int,
    status: GameStatus,
    gameOver: String?,
    moveFrom: Territory?,
    moveUnits: List<Unit>,
    movePlan: MovePlan?,
): String {
    if (gameOver != null) return "Game over: $gameOver"
    return when (pending) {
        is MoveRequest -> when {
            movePlan != null -> "Confirm move"
            moveFrom != null && moveUnits.isNotEmpty() -> "${moveUnits.size} selected → tap target"
            else -> "Tap units, then target"
        }
        is PlaceRequest -> {
            val remaining = MoveHelper.unitsToPlace(session, pending.player)
            if (remaining.isEmpty()) "All placed" else "${remaining.size} to place → tap territory"
        }
        is EndTurnRequest -> "End turn"
        is PurchaseRequest -> "Purchase"
        is BattleRequest, is CasualtyRequest, is ConfirmRequest,
        is SelectTerritoryRequest, is SelectUnitsRequest, is RetreatRequest,
        is PoliticsRequest, is UserActionRequest, is CasualtyNoticeRequest -> ""
        null -> if (status.isHumanTurn) "" else if (status.playerName.isBlank()) "Starting…"
        else status.playerName + (if (aiSeconds >= 5) " · ${aiSeconds}s" else "") + " …"
    }
}

/** The symbol next to the hint: what kind of action the phase wants. */
private fun hintIcon(pending: UiRequest<*>?, hasPlan: Boolean, hasSelection: Boolean): androidx.compose.ui.graphics.vector.ImageVector = when (pending) {
    is MoveRequest -> if (hasPlan) Icons.Filled.Check else if (hasSelection) Icons.AutoMirrored.Filled.ArrowForward else Icons.Filled.TouchApp
    is PlaceRequest -> Icons.Filled.AddLocationAlt
    is PurchaseRequest -> Icons.Filled.ShoppingCart
    is EndTurnRequest -> Icons.Filled.Flag
    null -> Icons.Filled.HourglassEmpty
    else -> Icons.Filled.Info
}

/** The phases every turn has; the others are optional and fold into these on the indicator. */
private val MAIN_PHASES = setOf("purchase", "combat", "noncombat", "place", "endturn")

/** The kind of phase a step belongs to, or null for engine-only steps (tech activation, bids, ...). */
internal fun stepKind(stepName: String): String? = when {
    stepName.isBlank() -> null
    GameStep.isTechStepName(stepName) -> "tech"
    GameStep.isPurchaseOrBidStepName(stepName) -> "purchase"
    GameStep.isMoveStepName(stepName) -> if (stepName.contains("NonCombat", true)) "noncombat" else "combat"
    GameStep.isBattleStepName(stepName) -> "battle"
    GameStep.isPlaceStepName(stepName) -> "place"
    GameStep.isPoliticsStepName(stepName) -> "politics"
    GameStep.isUserActionsStepName(stepName) -> "actions"
    GameStep.isEndTurnStepName(stepName) -> "endturn"
    else -> null
}

/** An icon for a game step, by its name: purchase, moves, battle, placement, end turn, politics, tech. */
internal fun stepIcon(stepName: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    GameStep.isPurchaseOrBidStepName(stepName) -> Icons.Filled.ShoppingCart
    GameStep.isMoveStepName(stepName) -> if (stepName.contains("NonCombat", true)) Icons.Filled.Loop else Icons.AutoMirrored.Filled.ArrowForward
    GameStep.isBattleStepName(stepName) -> Icons.Filled.Whatshot
    GameStep.isPlaceStepName(stepName) -> Icons.Filled.AddLocationAlt
    GameStep.isEndTurnStepName(stepName) -> Icons.Filled.Flag
    GameStep.isPoliticsStepName(stepName) || GameStep.isUserActionsStepName(stepName) -> Icons.Filled.Handshake
    GameStep.isTechStepName(stepName) -> Icons.Filled.Science
    else -> Icons.Filled.Info
}

internal fun summarizeUnits(units: Collection<Unit>): String =
    units.groupBy { it.type.name }.entries.joinToString(", ") { "${it.value.size} ${it.key}" }

/** Small translucent box for text floating on the map. */
@Composable
private fun OverlayChip(modifier: Modifier = Modifier, content: @Composable () -> kotlin.Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) { content() }
    }
}

/**
 * Turn progress as a page indicator: a dot per step of this nation's turn, the current one large
 * and colored, with the current step's symbol and name beside it. Small enough to stay out of
 * the way at the top of the map.
 */
@Composable
private fun TurnStepStrip(
    steps: List<Pair<String, String>>,
    currentStep: String,
    optional: String = "",
    suffix: String = "",
    modifier: Modifier = Modifier,
) {
    val currentIndex = steps.indexOfFirst { it.first == currentStep }
    val mainName = steps.getOrNull(currentIndex)?.second ?: ""
    // "Combat Move · Battle" while an optional phase runs inside a main one
    val currentName = if (optional.isNotBlank()) listOf(mainName, optional).filter { it.isNotBlank() }.joinToString(" · ") else mainName
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            if (currentIndex >= 0) {
                Icon(stepIcon(currentStep), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (suffix.isBlank()) currentName else "$currentName · $suffix",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEachIndexed { index, _ ->
                    val current = index == currentIndex
                    val done = currentIndex >= 0 && index < currentIndex
                    Box(
                        Modifier
                            .size(if (current) 9.dp else 6.dp)
                            .background(
                                when {
                                    current -> MaterialTheme.colorScheme.primary
                                    done -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                                },
                                androidx.compose.foundation.shape.CircleShape,
                            ),
                    )
                    if (current && optional.isNotBlank()) {
                        // the small extra marker: an optional phase inside the current main phase
                        Box(
                            Modifier
                                .padding(start = 1.dp)
                                .size(5.dp)
                                .background(MaterialTheme.colorScheme.tertiary, androidx.compose.foundation.shape.CircleShape),
                        )
                    }
                }
            }
        }
    }
}

/** A page over the whole game screen that the back gesture closes. */
@Composable
private fun FullScreenPage(onBack: () -> kotlin.Unit, content: @Composable () -> kotlin.Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize().zIndex(10f)) { content() }
}

/** Icon plus a short word: readable for players who see poorly or read little English. */
@Composable
private fun ButtonLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Icon(icon, contentDescription = text, modifier = Modifier.size(22.dp))
    if (text.isNotBlank()) {
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A question asked before a phase is ended, because phases cannot be re-entered. */
class PhaseEndConfirm(val title: String, val message: String, val button: String, val proceed: () -> kotlin.Unit)

/** The buttons that advance the game, bottom right of the map. */
@Composable
private fun PhaseActions(
    session: LocalGameSession,
    pendingRequest: UiRequest<*>?,
    gameOver: String?,
    hasSelection: Boolean,
    onClearSelection: () -> kotlin.Unit,
    onUndo: () -> kotlin.Unit,
    onDone: (PhaseEndConfirm) -> kotlin.Unit,
    onQuit: () -> kotlin.Unit,
    fullWidth: Boolean = false,
    movesCount: Int = 0,
    onOpenPurchase: () -> kotlin.Unit = {},
) {
    val compactPadding = PaddingValues(horizontal = 14.dp)
    val buttonModifier = (if (fullWidth) Modifier.fillMaxWidth() else Modifier).heightIn(min = 46.dp)
    if (gameOver != null) {
        Button(onClick = onQuit, contentPadding = compactPadding, modifier = buttonModifier) { ButtonLabel(Icons.AutoMirrored.Filled.ArrowBack, "Menu") }
        return
    }
    when (pendingRequest) {
        is MoveRequest -> {
            if (hasSelection) {
                FilledTonalButton(onClick = onClearSelection, contentPadding = compactPadding, modifier = buttonModifier) { ButtonLabel(Icons.Filled.Clear, "Clear") }
            }
            val moves = movesCount
            FilledTonalButton(onClick = onUndo, enabled = moves > 0, contentPadding = compactPadding, modifier = buttonModifier) {
                ButtonLabel(Icons.AutoMirrored.Filled.Undo, if (moves > 0) "Undo · $moves" else "Undo")
            }
            Button(
                onClick = {
                    val phase = if (pendingRequest.nonCombat) "non-combat move" else "combat move"
                    onDone(
                        PhaseEndConfirm(
                            title = "End $phase?",
                            message = if (moves == 0) "No units moved." else "",
                            button = "End phase",
                        ) { pendingRequest.complete(Optional.empty()) },
                    )
                },
                contentPadding = compactPadding,
                modifier = buttonModifier,
            ) { ButtonLabel(Icons.Filled.Check, "Done") }
        }
        is PlaceRequest -> {
            FilledTonalButton(onClick = onUndo, enabled = movesCount > 0, contentPadding = compactPadding, modifier = buttonModifier) {
                ButtonLabel(Icons.AutoMirrored.Filled.Undo, if (movesCount > 0) "Undo · $movesCount" else "Undo")
            }
            Button(
                onClick = {
                    val remaining = MoveHelper.unitsToPlace(session, pendingRequest.player).size
                    onDone(
                        PhaseEndConfirm(
                            title = "End placement?",
                            message = if (remaining > 0) "$remaining unit(s) not placed, they will be lost." else "",
                            button = "End phase",
                        ) { pendingRequest.complete(Optional.empty()) },
                    )
                },
                contentPadding = compactPadding,
                modifier = buttonModifier,
            ) { ButtonLabel(Icons.Filled.Check, "Done") }
        }
        is PurchaseRequest -> {
            Button(onClick = onOpenPurchase, contentPadding = compactPadding, modifier = buttonModifier) {
                ButtonLabel(Icons.Filled.ShoppingCart, if (pendingRequest.bid) "Bid" else "Buy")
            }
        }
        is EndTurnRequest -> {
            Button(
                onClick = {
                    onDone(
                        PhaseEndConfirm(
                            title = "End turn?",
                            message = "",
                            button = "End turn",
                        ) { pendingRequest.complete(true) },
                    )
                },
                contentPadding = compactPadding,
                modifier = buttonModifier,
            ) { ButtonLabel(Icons.Filled.Flag, "End turn") }
        }
        else -> {}
    }
}

/**
 * The question before a phase ends, shown as a small card right above the Done button instead of
 * a pop-up: the phase that ends and, if anything, what is left unresolved.
 */
@Composable
private fun PhaseEndCard(
    confirm: PhaseEndConfirm,
    onCancel: () -> kotlin.Unit,
    onConfirm: () -> kotlin.Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(max = 420.dp),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(confirm.title, style = MaterialTheme.typography.titleSmall)
                if (confirm.message.isNotBlank()) {
                    Text(confirm.message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "keep going") }
            ConfirmButton(onClick = onConfirm)
        }
    }
}

/** Floating confirmation shown over the map once a destination was tapped and the route is drawn. */
@Composable
private fun MoveConfirmCard(
    plan: MovePlan,
    images: ImageCache,
    onConfirm: () -> kotlin.Unit,
    onCancel: () -> kotlin.Unit,
    modifier: Modifier = Modifier,
) {
    val route = plan.description.route
    val groups = remember(plan) { plan.description.units.groupBy { it.type to it.owner } }
    Card(
        modifier = modifier.widthIn(max = 420.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            // one line: the units, "from -> to" (with the number of steps when it is not one), X and check
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val steps = route.numberOfSteps()
                    Text(
                        "${route.start.name} → ${plan.to.name}" + if (steps > 1) "  ($steps)" else "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        groups.entries.take(5).forEach { (key, units) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UnitIcon(images, key.first, key.second, size = 24)
                                Text("×${units.size}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (groups.size > 5) Text("…", style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "cancel") }
                ConfirmButton(onClick = onConfirm)
            }
            plan.warning?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (plan.lostAir.isNotEmpty()) {
                Text(
                    "${plan.lostAir.size} plane(s) will be lost",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
