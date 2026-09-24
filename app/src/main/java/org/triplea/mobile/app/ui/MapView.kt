package org.triplea.mobile.app.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.Unit
import games.strategy.triplea.ui.mapdata.MapData
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.triplea.mobile.app.game.MapSnapshot
import org.triplea.mobile.app.game.UnitStack
import org.triplea.mobile.app.render.ImageCache

private const val TILE_SIZE = 256

/** What the user tapped on the map: the territory under the finger and, if any, the unit stack. */
class MapTap(val territory: String?, val stack: UnitStack?)

/**
 * Pan/zoom state of the map, kept outside the composable so it survives recomposition and
 * orientation changes. The offset is always clamped so the map cannot be pushed out of view.
 */
class MapViewState(
    private val mapWidth: Int,
    private val mapHeight: Int,
    /** World maps scroll endlessly sideways: the map is repeated to the left and right. */
    val wrapX: Boolean = false,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewSize by mutableStateOf(Size.Zero)
        private set
    var minScale = 0.05f
        private set
    var maxScale = 4f
    private var fitted = false

    fun toMap(screen: Offset): Offset {
        val p = (screen - offset) / scale
        if (!wrapX) return p
        val w = mapWidth.toFloat()
        return Offset(((p.x % w) + w) % w, p.y)
    }

    fun toScreen(map: Offset): Offset = map * scale + offset

    /** Called whenever the size of the map view changes (first layout, rotation, panel resize). */
    fun onViewportChanged(size: Size) {
        if (size.width <= 0f || size.height <= 0f || size == viewSize) return
        val old = viewSize
        viewSize = size
        val fit = if (wrapX) {
            // a wrapping map must always cover the whole viewport, so zooming out stops at "fill"
            max(size.width / mapWidth, size.height / mapHeight)
        } else {
            min(size.width / mapWidth, size.height / mapHeight)
        }
        minScale = if (wrapX) fit.coerceIn(0.02f, maxScale) else (fit * 0.8f).coerceIn(0.02f, 1f)
        if (!fitted || old == Size.Zero) {
            fitted = true
            scale = fit.coerceIn(minScale, maxScale)
            offset = clampOffset(
                Offset((size.width - mapWidth * scale) / 2f, (size.height - mapHeight * scale) / 2f),
                scale,
            )
        } else {
            // keep the map point that was in the middle of the old viewport in the middle
            val center = toMap(Offset(old.width / 2f, old.height / 2f))
            scale = scale.coerceIn(minScale, maxScale)
            offset = clampOffset(
                Offset(size.width / 2f - center.x * scale, size.height / 2f - center.y * scale),
                scale,
            )
        }
    }

    /** Applies a pinch/pan gesture step around [centroid] (screen coordinates). */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        val effectiveZoom = newScale / scale
        val newOffset = (offset - centroid) * effectiveZoom + centroid + pan
        scale = newScale
        offset = clampOffset(newOffset, newScale)
    }

    /**
     * Brings [points] (map coordinates) into view, e.g. the route of a move the AI just made:
     * zooms out only when they do not fit, zooms in a little when the map is far out, and
     * centers on them (on the last point for a wrapping map, whose routes may cross the seam).
     */
    fun focusOn(points: List<Offset>) {
        if (points.isEmpty() || viewSize == Size.Zero) return
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val target = if (wrapX) points.last() else Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
        val fit = if (wrapX) {
            maxScale
        } else {
            min(viewSize.width / ((maxX - minX) * 1.4f + 200f), viewSize.height / ((maxY - minY) * 1.4f + 200f))
        }
        val wanted = when {
            scale > fit -> fit
            scale < 0.45f -> min(fit, 0.7f)
            else -> scale
        }
        scale = wanted.coerceIn(minScale, maxScale)
        centerOn(target.x, target.y)
    }

    fun centerOn(mapX: Float, mapY: Float) {
        offset = clampOffset(
            Offset(viewSize.width / 2f - mapX * scale, viewSize.height / 2f - mapY * scale),
            scale,
        )
    }

    fun clampOffset(candidate: Offset, atScale: Float): Offset {
        val view = viewSize
        if (view == Size.Zero) return candidate
        val w = mapWidth * atScale
        val h = mapHeight * atScale
        val x = when {
            wrapX -> {
                // keep the offset in (-w, 0]; the drawing repeats the map to the right as needed
                val r = candidate.x % w
                if (r > 0f) r - w else r
            }
            w <= view.width -> (view.width - w) / 2f
            else -> candidate.x.coerceIn(view.width - w, 0f)
        }
        val y = if (h <= view.height) (view.height - h) / 2f else candidate.y.coerceIn(view.height - h, 0f)
        return Offset(x, y)
    }

    /** Used by the fling animation: moves to [target] as far as the clamping allows. */
    internal fun flingTo(target: Offset) {
        offset = clampOffset(target, scale)
    }
}

/**
 * Draws the game map: base tiles, territory ownership colors, relief tiles, unit stacks, the
 * currently selected units and the planned move route. Supports pinch zoom, panning with fling,
 * tap, double tap and long press.
 *
 * Taps on a unit stack are reported immediately; taps elsewhere wait for a possible second tap so
 * that a double tap on a territory can be told apart from a single one.
 */
@Composable
fun MapView(
    snapshot: MapSnapshot?,
    mapData: MapData,
    images: ImageCache,
    state: MapViewState,
    /** The territory whose info is shown: thin white outline. */
    selectedTerritory: String?,
    /** Where the selected units stand: yellow outline. */
    originTerritory: String?,
    /** The planned destination: red outline. */
    destinationTerritory: String?,
    highlighted: Set<String>,
    selectedUnits: Set<Unit>,
    route: List<Offset>,
    routeSteps: Int,
    /** Territories drawn with the pulsing battle marker; [battlePulse] is the animated 0..1 phase. */
    battleSites: Set<String>,
    battlePulse: Float,
    showTerritoryNames: Boolean,
    showTerritoryValues: Boolean,
    unitScale: Float = 1f,
    counterScale: Float = 1f,
    showRelief: Boolean = true,
    qualityFactor: Int = 1,
    highContrast: Boolean = false,
    largeTouchTargets: Boolean = false,
    onTap: (MapTap) -> kotlin.Unit,
    onDoubleTap: (MapTap) -> kotlin.Unit,
    onLongPress: (MapTap) -> kotlin.Unit,
    modifier: Modifier = Modifier,
) {
    val imageVersion by images.version.collectAsState()
    val hasRelief = remember(mapData) { mapData.hasRelief }
    val mapDimensions = remember(mapData) { mapData.mapDimensions }
    val unitWidth = remember(mapData, unitScale) {
        (mapData.defaultUnitWidth * mapData.defaultUnitScale).toFloat().coerceAtLeast(8f) * unitScale
    }
    val paints = remember { MapPaints() }
    val scope = rememberCoroutineScope()
    val minTouchPx = with(LocalDensity.current) { (if (largeTouchTargets) 60.dp else 44.dp).toPx() }
    val contrast = if (highContrast) 1.8f else 1f
    val densityScale = LocalDensity.current.density

    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    fun resolve(screen: Offset): MapTap {
        val p = state.toMap(screen)
        val territory = mapData.getTerritoryAt(p.x.toDouble(), p.y.toDouble())
        // make small icons reachable: at least a 44dp touch target on screen
        val slack = max(0f, (minTouchPx / state.scale - unitWidth) / 2f)
        val stack = currentSnapshot?.stackAt(p.x, p.y, unitWidth, slack)
        return MapTap(territory, stack)
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { state.onViewportChanged(Size(it.width.toFloat(), it.height.toFloat())) }
            .pointerInput(state, mapData) {
                mapGestures(
                    state = state,
                    scope = scope,
                    isImmediateTap = { resolve(it).stack != null },
                    onTap = { currentOnTap(resolve(it)) },
                    onDoubleTap = { currentOnDoubleTap(resolve(it)) },
                    onLongPress = { currentOnLongPress(resolve(it)) },
                )
            },
    ) {
        // referencing imageVersion forces a redraw when new bitmaps arrive
        @Suppress("UNUSED_VARIABLE") val tick = imageVersion
        val viewW = size.width
        val viewH = size.height
        val scale = state.scale
        val offset = state.offset
        val mapW = mapDimensions.width.toFloat()
        val mapH = mapDimensions.height.toFloat()
        val routePoints = if (state.wrapX) unwrapRoute(route, mapW) else route
        // zoomed out, decode tiles at a lower resolution: less memory, no cache thrashing
        val tileSample = (ImageCache.sampleSizeFor(scale) * qualityFactor).coerceAtMost(8)
        // which copies of the map are on screen (only ever more than one for wrapping maps)
        val copies = if (state.wrapX) 0..floor((viewW - offset.x) / (mapW * scale)).toInt() else 0..0
        for (copy in copies) {
        val offsetX = offset.x + copy * mapW * scale
        val visibleLeft = max(0f, -offsetX / scale)
        val visibleTop = max(0f, -offset.y / scale)
        val visibleRight = min(mapW, (viewW - offsetX) / scale)
        val visibleBottom = min(mapDimensions.height.toFloat(), (viewH - offset.y) / scale)
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) continue

        withTransform({
            translate(offsetX, offset.y)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            val canvas = drawContext.canvas.nativeCanvas
            // one tile of margin around the view: tiles are decoded before they scroll in, so
            // panning does not show them popping up at the edge of the screen
            val maxTileX = ceil(mapW / TILE_SIZE).toInt()
            val maxTileY = ceil(mapDimensions.height.toFloat() / TILE_SIZE).toInt()
            val firstTileX = (floor(visibleLeft / TILE_SIZE).toInt() - 1).coerceAtLeast(0)
            val lastTileX = (ceil(visibleRight / TILE_SIZE).toInt() + 1).coerceAtMost(maxTileX)
            val firstTileY = (floor(visibleTop / TILE_SIZE).toInt() - 1).coerceAtLeast(0)
            val lastTileY = (ceil(visibleBottom / TILE_SIZE).toInt() + 1).coerceAtMost(maxTileY)
            val dst = RectF()

            // 1. base tiles. Every tile is drawn at its own size (its pixels times the sample
            // size it was decoded at): the last column and row are usually cut short by the
            // desktop tile breaker, but some maps ship full tiles past the declared map size.
            // A wrapping map is clipped to its width so that surplus never covers the next copy.
            if (state.wrapX) {
                canvas.save()
                canvas.clipRect(0f, 0f, mapW, mapH)
            }
            for (tx in firstTileX until lastTileX) {
                for (ty in firstTileY until lastTileY) {
                    val key = "baseTiles/${tx}_$ty.png"
                    val decoded = images.getDecoded(key, sampleSize = tileSample)
                    val left = (tx * TILE_SIZE).toFloat()
                    val top = (ty * TILE_SIZE).toFloat()
                    if (decoded != null) {
                        dst.set(left, top, left + decoded.bitmap.width * decoded.sampleSize, top + decoded.bitmap.height * decoded.sampleSize)
                        canvas.drawBitmap(decoded.bitmap, null, dst, paints.bitmap)
                    } else if (!images.isMissing(key)) {
                        // still decoding: a quiet placeholder; a tile the map does not have at all
                        // (some maps stop short of their declared size) is left untouched
                        dst.set(left, top, min(left + TILE_SIZE, mapW), min(top + TILE_SIZE, mapH))
                        canvas.drawRect(dst, paints.missingTile)
                    }
                }
            }
            if (state.wrapX) canvas.restore()

            // 2. territory ownership colors
            if (snapshot != null) {
                for (territory in snapshot.territories) {
                    val fill = territory.fillColor ?: continue
                    if (!territory.bounds.intersects(visibleLeft, visibleTop, visibleRight, visibleBottom)) continue
                    paints.fill.color = fill
                    for (path in territory.paths) {
                        canvas.drawPath(path, paints.fill)
                        canvas.drawPath(path, paints.outline)
                    }
                }
            }

            // 3. relief tiles (shading and borders, semi transparent)
            if (hasRelief && showRelief) {
                if (state.wrapX) {
                    canvas.save()
                    canvas.clipRect(0f, 0f, mapW, mapH)
                }
                for (tx in firstTileX until lastTileX) {
                    for (ty in firstTileY until lastTileY) {
                        val key = "reliefTiles/${tx}_$ty.png"
                        val decoded = images.getDecoded(key, sampleSize = tileSample) ?: continue
                        val left = (tx * TILE_SIZE).toFloat()
                        val top = (ty * TILE_SIZE).toFloat()
                        dst.set(left, top, left + decoded.bitmap.width * decoded.sampleSize, top + decoded.bitmap.height * decoded.sampleSize)
                        canvas.drawBitmap(decoded.bitmap, null, dst, paints.bitmap)
                    }
                }
                if (state.wrapX) canvas.restore()
            }

            // 3b. territory names (when zoomed in) and, on request, the PU values
            if (snapshot != null) {
                val showNames = showTerritoryNames && scale >= 0.55f
                paints.nameText.textSize = 13f
                paints.nameOutline.textSize = 13f
                val dpUnit = densityScale / scale
                val valueSize = 9f * dpUnit
                paints.valueText.textSize = valueSize
                for (territory in snapshot.territories) {
                    if (territory.isWater) continue
                    if (!territory.bounds.intersects(visibleLeft, visibleTop, visibleRight, visibleBottom)) continue
                    var nameBottom = -1f
                    var nameCenterX = territory.centerX.toFloat()
                    if (showNames && territory.drawName) {
                        val width = paints.nameText.measureText(territory.name)
                        val x: Float
                        val y: Float
                        if (territory.nameX != null && territory.nameY != null) {
                            x = territory.nameX.toFloat()
                            y = territory.nameY.toFloat() + 13f
                        } else {
                            x = territory.centerX - width / 2f
                            y = territory.bounds.top + (territory.bounds.height() * 0.3f)
                        }
                        canvas.drawText(territory.name, x, y, paints.nameOutline)
                        canvas.drawText(territory.name, x, y, paints.nameText)
                        nameBottom = y + 2f
                        nameCenterX = x + width / 2f
                    }
                    if (showTerritoryValues && (territory.production > 0 || territory.isVictoryCity)) {
                        val label = (if (territory.isVictoryCity) "\u2605 " else "") + territory.production
                        val textWidth = paints.valueText.measureText(label)
                        val padX = 4f * dpUnit
                        val height = 12f * dpUnit
                        val cx: Float
                        val top: Float
                        when {
                            territory.puX != null && territory.puY != null -> {
                                cx = territory.puX + textWidth / 2f + padX
                                top = territory.puY.toFloat()
                            }
                            nameBottom >= 0f -> {
                                cx = nameCenterX
                                top = nameBottom
                            }
                            territory.nameX != null && territory.nameY != null -> {
                                cx = territory.nameX + textWidth / 2f + padX
                                top = territory.nameY.toFloat()
                            }
                            else -> {
                                cx = territory.centerX.toFloat()
                                top = territory.centerY - unitWidth * 0.75f - height
                            }
                        }
                        val left = cx - textWidth / 2f - padX
                        canvas.drawRoundRect(left, top, left + textWidth + padX * 2, top + height, height / 2f, height / 2f, paints.valueBackground)
                        canvas.drawText(label, cx, top + height * 0.74f, paints.valueText)
                    }
                }
            }

            // 4. highlights and selection
            if (snapshot != null) {
                paints.highlight.strokeWidth = 3f / scale
                if (battleSites.isNotEmpty()) {
                    // hostile units met here: pulsing red marker like the desktop battle highlight
                    paints.battleFill.alpha = (40 + 110 * battlePulse).toInt()
                    paints.battleOutline.alpha = (120 + 135 * battlePulse).toInt()
                    paints.battleOutline.strokeWidth = (3f + 3f * battlePulse) / scale
                    for (name in battleSites) {
                        val territory = snapshot.byName[name] ?: continue
                        if (!territory.bounds.intersects(visibleLeft, visibleTop, visibleRight, visibleBottom)) continue
                        for (path in territory.paths) canvas.drawPath(path, paints.battleFill)
                        for (path in territory.paths) canvas.drawPath(path, paints.battleOutline)
                    }
                }
                for (name in highlighted) {
                    val territory = snapshot.byName[name] ?: continue
                    for (path in territory.paths) canvas.drawPath(path, paints.highlightFill)
                    for (path in territory.paths) canvas.drawPath(path, paints.highlight)
                }
                // one screen pixel / dp in map units, so outlines keep their thickness while zooming
                val dpUnit = densityScale / scale
                fun outline(name: String?, paint: Paint, widthDp: Float, shadow: Boolean) {
                    val territory = name?.let { snapshot.byName[it] } ?: return
                    if (shadow) {
                        paints.outlineShadow.strokeWidth = (widthDp + 2f) * dpUnit
                        for (path in territory.paths) canvas.drawPath(path, paints.outlineShadow)
                    }
                    paint.strokeWidth = widthDp * dpUnit
                    for (path in territory.paths) canvas.drawPath(path, paint)
                }
                if (selectedTerritory != originTerritory && selectedTerritory != destinationTerritory) {
                    outline(selectedTerritory, paints.infoOutline, 1.5f * contrast, shadow = true)
                }
                originTerritory?.let { name ->
                    snapshot.byName[name]?.let { t -> for (path in t.paths) canvas.drawPath(path, paints.originFill) }
                }
                outline(originTerritory, paints.originOutline, 2.5f * contrast, shadow = true)
                outline(destinationTerritory, paints.selection, 2.5f * contrast, shadow = true)
            }

            // 5. units
            if (snapshot != null) {
                paints.counterText.textSize = max(12f, unitWidth * 0.45f) * counterScale
                paints.counterOutline.textSize = paints.counterText.textSize
                for (territory in snapshot.territories) {
                    if (territory.stacks.isEmpty()) continue
                    if (!territory.bounds.intersects(
                            visibleLeft - unitWidth * 4,
                            visibleTop - unitWidth * 4,
                            visibleRight + unitWidth * 4,
                            visibleBottom + unitWidth * 4,
                        )
                    ) continue
                    for (stack in territory.stacks) {
                        val key = stack.imagePaths.first()
                        val bitmap = images.get(key, stack.imagePaths)
                        dst.set(
                            stack.x.toFloat(),
                            stack.y.toFloat(),
                            stack.x + unitWidth,
                            stack.y + unitWidth,
                        )
                        val chosen = if (selectedUnits.isEmpty()) 0 else stack.units.count { it in selectedUnits }
                        if (chosen > 0) {
                            // a soft glow with a crisp ring behind the icon, like a selected card
                            val dpUnit = densityScale / scale
                            val pad = 3f * dpUnit
                            val corner = 4f * dpUnit
                            paints.selectionRingShadow.strokeWidth = 4f * dpUnit * contrast
                            paints.selectionRing.strokeWidth = 2f * dpUnit * contrast
                            canvas.drawRoundRect(dst.left - pad, dst.top - pad, dst.right + pad, dst.bottom + pad, corner, corner, paints.selectionGlow)
                            canvas.drawRoundRect(dst.left - pad, dst.top - pad, dst.right + pad, dst.bottom + pad, corner, corner, paints.selectionRingShadow)
                            canvas.drawRoundRect(dst.left - pad, dst.top - pad, dst.right + pad, dst.bottom + pad, corner, corner, paints.selectionRing)
                        }
                        if (bitmap != null) {
                            canvas.drawBitmap(bitmap, null, dst, paints.bitmap)
                        } else {
                            canvas.drawRect(dst, paints.missingUnit)
                        }
                        if (chosen > 0) {
                            // counter pill at the top right corner: "3/5" selected of this stack
                            val dpUnit = densityScale / scale
                            val text = if (stack.count > 1) "$chosen/${stack.count}" else "1"
                            paints.pillText.textSize = 10f * dpUnit
                            val textWidth = paints.pillText.measureText(text)
                            val pillH = 14f * dpUnit
                            val pillW = textWidth + 8f * dpUnit
                            val right = dst.right + 5f * dpUnit
                            val top = dst.top - 7f * dpUnit
                            paints.pillShadow.strokeWidth = 1.5f * dpUnit
                            canvas.drawRoundRect(right - pillW, top, right, top + pillH, pillH / 2f, pillH / 2f, paints.pillBackground)
                            canvas.drawRoundRect(right - pillW, top, right, top + pillH, pillH / 2f, pillH / 2f, paints.pillShadow)
                            canvas.drawText(text, right - pillW / 2f, top + pillH * 0.72f, paints.pillText)
                        } else if (stack.count > 1) {
                            val text = stack.count.toString()
                            val tx = stack.x + unitWidth * 0.55f
                            val ty = stack.y + unitWidth * 0.95f
                            canvas.drawText(text, tx, ty, paints.counterOutline)
                            canvas.drawText(text, tx, ty, paints.counterText)
                        }
                        if (stack.damagedCount > 0) {
                            canvas.drawRect(
                                stack.x.toFloat(),
                                stack.y.toFloat(),
                                stack.x + unitWidth * 0.3f,
                                stack.y + unitWidth * 0.3f,
                                paints.damage,
                            )
                        }
                    }
                }
            }

            // 6. planned move route
            if (routePoints.size >= 2) {
                drawRoute(canvas, routePoints, routeSteps, scale / contrast, paints)
            }
        }
        }
    }
}

/** On a wrapping map a route may cross the seam; shift points so each step takes the short way. */
private fun unwrapRoute(points: List<Offset>, mapWidth: Float): List<Offset> {
    if (points.size < 2) return points
    val out = ArrayList<Offset>(points.size)
    out += points[0]
    for (i in 1 until points.size) {
        val prev = out[i - 1]
        var p = points[i]
        val dx = p.x - prev.x
        if (dx > mapWidth / 2f) p = Offset(p.x - mapWidth, p.y)
        else if (dx < -mapWidth / 2f) p = Offset(p.x + mapWidth, p.y)
        out += p
    }
    return out
}

/** Draws the move route as a smooth arrow through the territory centers, like the desktop client. */
private fun drawRoute(canvas: android.graphics.Canvas, points: List<Offset>, steps: Int, scale: Float, paints: MapPaints) {
    val path = Path()
    val n = points.size
    path.moveTo(points[0].x, points[0].y)
    var tangent: Offset
    if (n == 2) {
        path.lineTo(points[1].x, points[1].y)
        tangent = points[1] - points[0]
    } else {
        var lastControl = points[n - 2]
        for (i in 0 until n - 1) {
            val p0 = points[max(i - 1, 0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[min(i + 2, n - 1)]
            val c1 = p1 + (p2 - p0) / 6f
            val c2 = p2 - (p3 - p1) / 6f
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
            lastControl = c2
        }
        tangent = points[n - 1] - lastControl
    }
    val end = points[n - 1]
    val length = tangent.getDistance()
    if (length < 0.001f) tangent = Offset(1f, 0f) else tangent /= length

    val lineWidth = 5f / scale
    paints.routeOutline.strokeWidth = lineWidth + 4f / scale
    paints.route.strokeWidth = lineWidth
    canvas.drawPath(path, paints.routeOutline)
    canvas.drawPath(path, paints.route)

    // waypoint dots on the intermediate territories
    val dotRadius = 5f / scale
    for (i in 1 until n - 1) {
        canvas.drawCircle(points[i].x, points[i].y, dotRadius + 2f / scale, paints.routeOutlineFill)
        canvas.drawCircle(points[i].x, points[i].y, dotRadius, paints.routeFill)
    }
    canvas.drawCircle(points[0].x, points[0].y, dotRadius + 3f / scale, paints.routeOutlineFill)
    canvas.drawCircle(points[0].x, points[0].y, dotRadius + 1f / scale, paints.routeFill)

    // arrow head at the destination
    val headLength = 22f / scale
    val headWidth = 16f / scale
    val angle = atan2(tangent.y, tangent.x)
    val base = end - tangent * headLength
    val leftAngle = angle + Math.PI.toFloat() / 2f
    val left = Offset(base.x + cos(leftAngle) * headWidth / 2f, base.y + sin(leftAngle) * headWidth / 2f)
    val right = Offset(base.x - cos(leftAngle) * headWidth / 2f, base.y - sin(leftAngle) * headWidth / 2f)
    val head = Path().apply {
        moveTo(end.x, end.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    paints.routeHeadOutline.strokeWidth = 4f / scale
    canvas.drawPath(head, paints.routeHeadOutline)
    canvas.drawPath(head, paints.routeFill)

    // step counter next to the arrow head
    if (steps > 0) {
        val radius = 8f / scale
        val perpendicular = Offset(-tangent.y, tangent.x)
        val center = end - tangent * (headLength * 0.5f) + perpendicular * (radius + 6f / scale)
        canvas.drawCircle(center.x, center.y, radius + 1f / scale, paints.routeFill)
        canvas.drawCircle(center.x, center.y, radius, paints.routeBadge)
        paints.routeBadgeText.textSize = 11f / scale
        canvas.drawText(steps.toString(), center.x, center.y + paints.routeBadgeText.textSize * 0.36f, paints.routeBadgeText)
    }
}

private enum class PressResult { TAP, DRAG, LONG_PRESS, CANCELED }

/**
 * A single gesture detector for the map. Compose's built-in transform and tap detectors fight
 * each other, so this handles everything: pan + pinch (with velocity for a fling), tap, double tap
 * and long press. Pans start after touch slop; a second finger starts the transform immediately.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.mapGestures(
    state: MapViewState,
    scope: CoroutineScope,
    isImmediateTap: (Offset) -> Boolean,
    onTap: (Offset) -> kotlin.Unit,
    onDoubleTap: (Offset) -> kotlin.Unit,
    onLongPress: (Offset) -> kotlin.Unit,
) {
    val decay = splineBasedDecay<Offset>(this)
    var flingJob: Job? = null
    val doubleTapSlop = viewConfiguration.touchSlop * 4f

    fun fling(velocityTracker: VelocityTracker) {
        val velocity = velocityTracker.calculateVelocity()
        val speed = Offset(velocity.x, velocity.y).getDistance()
        if (speed < 150f) return
        flingJob = scope.launch {
            var last = state.offset
            AnimationState(Offset.VectorConverter, state.offset, Offset(velocity.x, velocity.y))
                .animateDecay(decay) {
                    val requested = value != last
                    state.flingTo(value)
                    val moved = state.offset != last
                    last = state.offset
                    if (requested && !moved) cancelAnimation()
                }
        }
    }

    awaitEachGesture {
        flingJob?.cancel()
        val tracker = VelocityTracker()
        val down = awaitFirstDown()
        tracker.addPosition(down.uptimeMillis, down.position)
        when (trackPress(down, state, tracker, onLongPress)) {
            PressResult.DRAG -> fling(tracker)
            PressResult.LONG_PRESS, PressResult.CANCELED -> {}
            PressResult.TAP -> {
                val position = down.position
                if (isImmediateTap(position)) {
                    onTap(position)
                } else {
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                    if (second == null) {
                        onTap(position)
                    } else {
                        tracker.resetTracking()
                        tracker.addPosition(second.uptimeMillis, second.position)
                        when (trackPress(second, state, tracker, onLongPress)) {
                            PressResult.TAP -> {
                                if ((second.position - position).getDistance() <= doubleTapSlop) {
                                    onDoubleTap(second.position)
                                } else {
                                    onTap(position)
                                    onTap(second.position)
                                }
                            }
                            PressResult.DRAG -> {
                                onTap(position)
                                fling(tracker)
                            }
                            PressResult.LONG_PRESS, PressResult.CANCELED -> onTap(position)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Follows one press from [down] until all pointers are up. Applies pan/zoom to [state] once the
 * gesture is past touch slop (or a second finger touches) and reports how the press ended.
 */
private suspend fun AwaitPointerEventScope.trackPress(
    down: PointerInputChange,
    state: MapViewState,
    tracker: VelocityTracker,
    onLongPress: (Offset) -> kotlin.Unit,
): PressResult {
    val slop = viewConfiguration.touchSlop
    var accumulatedZoom = 1f
    var accumulatedPan = Offset.Zero
    var centroid = down.position

    // phase 1: wait for slop, a second finger, release or the long press timeout
    val phaseOne = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) return@withTimeoutOrNull PressResult.CANCELED
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) return@withTimeoutOrNull PressResult.TAP
            if (pressed.size > 1) {
                centroid = event.calculateCentroid(useCurrent = false)
                return@withTimeoutOrNull PressResult.DRAG
            }
            accumulatedZoom *= event.calculateZoom()
            accumulatedPan += event.calculatePan()
            tracker.addPosition(pressed[0].uptimeMillis, pressed[0].position)
            val zoomMotion = abs(1f - accumulatedZoom) * event.calculateCentroidSize(useCurrent = false)
            if (accumulatedPan.getDistance() > slop || zoomMotion > slop) {
                centroid = event.calculateCentroid(useCurrent = false)
                return@withTimeoutOrNull PressResult.DRAG
            }
        }
        @Suppress("UNREACHABLE_CODE")
        PressResult.CANCELED
    }

    when (phaseOne) {
        null -> {
            onLongPress(down.position)
            // swallow the rest of this press
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                if (event.changes.none { it.pressed }) break
            }
            return PressResult.LONG_PRESS
        }
        PressResult.TAP, PressResult.CANCELED -> return phaseOne
        else -> {}
    }

    // phase 2: pan / zoom until every finger is lifted
    if (accumulatedPan != Offset.Zero || accumulatedZoom != 1f) {
        state.transform(centroid, accumulatedPan, accumulatedZoom)
    }
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        if (zoom != 1f || pan != Offset.Zero) {
            state.transform(event.calculateCentroid(useCurrent = false), pan, zoom)
        }
        if (event.changes.size == 1) {
            tracker.addPosition(event.changes[0].uptimeMillis, event.changes[0].position)
        } else {
            tracker.resetTracking()
        }
        event.changes.forEach { if (it.positionChanged()) it.consume() }
        if (pressed.isEmpty()) break
    }
    return PressResult.DRAG
}

private class MapPaints {
    val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    val missingTile = Paint().apply { color = Color.rgb(40, 60, 90) }
    val missingUnit = Paint().apply { color = Color.argb(200, 120, 120, 120) }
    val fill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    val outline = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 0, 0, 0)
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    val highlightFill = Paint().apply { style = Paint.Style.FILL; color = Color.argb(70, 255, 255, 255) }
    val highlight = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(255, 230, 0); isAntiAlias = true }
    // the planned destination: orange, so it reads apart from the red origin
    val selection = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(255, 150, 0); isAntiAlias = true; strokeJoin = Paint.Join.ROUND }
    val counterText = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val counterOutline = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val damage = Paint().apply { color = Color.RED }
    val nameText = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val nameOutline = Paint().apply {
        color = Color.argb(160, 255, 255, 255)
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val battleFill = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(255, 40, 30); isAntiAlias = true }
    val battleOutline = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(255, 60, 40); isAntiAlias = true }

    // selected units: yellow ring, glow and count pill
    val selectionGlow = Paint().apply { style = Paint.Style.FILL; color = Color.argb(90, 255, 225, 0); isAntiAlias = true }
    val selectionRing = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(255, 215, 0); isAntiAlias = true }
    val selectionRingShadow = Paint().apply { style = Paint.Style.STROKE; color = Color.argb(110, 0, 0, 0); isAntiAlias = true }
    val pillBackground = Paint().apply { style = Paint.Style.FILL; color = Color.argb(235, 28, 28, 28); isAntiAlias = true }
    val pillShadow = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(255, 215, 0); isAntiAlias = true }
    val pillText = Paint().apply {
        color = Color.rgb(255, 228, 80)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    // territory values: a quiet, translucent label that does not compete with the units
    val valueBackground = Paint().apply { style = Paint.Style.FILL; color = Color.argb(150, 255, 255, 255); isAntiAlias = true }
    val valueText = Paint().apply {
        color = Color.argb(220, 30, 30, 30)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    val infoOutline = Paint().apply { style = Paint.Style.STROKE; color = Color.argb(230, 255, 255, 255); isAntiAlias = true; strokeJoin = Paint.Join.ROUND }
    val originOutline = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(225, 40, 30); isAntiAlias = true; strokeJoin = Paint.Join.ROUND }
    val originFill = Paint().apply { style = Paint.Style.FILL; color = Color.argb(35, 255, 80, 60) }
    val outlineShadow = Paint().apply { style = Paint.Style.STROKE; color = Color.argb(120, 0, 0, 0); isAntiAlias = true; strokeJoin = Paint.Join.ROUND }

    private val routeColor = Color.rgb(220, 40, 30)
    val route = Paint().apply {
        style = Paint.Style.STROKE
        color = routeColor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    val routeOutline = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    val routeFill = Paint().apply { style = Paint.Style.FILL; color = routeColor; isAntiAlias = true }
    val routeOutlineFill = Paint().apply { style = Paint.Style.FILL; color = Color.WHITE; isAntiAlias = true }
    val routeHeadOutline = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 255, 255)
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    val routeBadge = Paint().apply { style = Paint.Style.FILL; color = Color.WHITE; isAntiAlias = true }
    val routeBadgeText = Paint().apply {
        color = routeColor
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
}
