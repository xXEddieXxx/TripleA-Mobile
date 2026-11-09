package org.triplea.mobile.app.game

import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.RelationshipType
import games.strategy.engine.data.Resource
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import games.strategy.engine.data.UnitType
import games.strategy.triplea.Properties
import games.strategy.triplea.delegate.EditDelegate
import games.strategy.triplea.delegate.Matches
import games.strategy.triplea.delegate.TechAdvance
import games.strategy.triplea.delegate.TechTracker
import games.strategy.triplea.delegate.TechnologyDelegate
import games.strategy.triplea.delegate.remote.IEditDelegate
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.LocalGameSession
import org.triplea.util.Triple

/** The engine's edit delegate (desktop "edit mode"): the calls and the queries the edit dialogs need. */
object EditHelper {

    /** True where the map is free for edits; false for battles, tech rolls and every confirm question. */
    fun editAllowed(pending: UiRequest<*>?): Boolean = when (pending) {
        is MoveRequest, is PlaceRequest, is EndTurnRequest,
        is PurchaseRequest, is PoliticsRequest, is UserActionRequest -> true
        else -> false
    }

    fun isEditMode(session: LocalGameSession): Boolean =
        session.gameData.acquireReadLock().use { EditDelegate.getEditMode(session.gameData.properties) }

    fun setEditMode(session: LocalGameSession, on: Boolean) {
        session.getRemoteDelegate("edit", IEditDelegate::class.java).setEditMode(on)
    }

    /** Runs engine edit calls; the first error string (or thrown message) wins, null means success. */
    private fun edit(session: LocalGameSession, block: (IEditDelegate) -> String?): String? = runCatching {
        block(session.getRemoteDelegate("edit", IEditDelegate::class.java))
    }.getOrElse { it.message ?: it.toString() }

    fun addUnits(
        session: LocalGameSession,
        territory: Territory,
        owner: GamePlayer,
        counts: Map<UnitType, Int>,
    ): String? = edit(session) { delegate ->
        val units = counts.flatMap { (type, count) -> type.create(count, owner) }
        delegate.addUnits(territory, units)
    }

    fun removeUnits(session: LocalGameSession, territory: Territory, units: Collection<Unit>): String? =
        edit(session) { it.removeUnits(territory, units) }

    fun changeOwner(session: LocalGameSession, territory: Territory, owner: GamePlayer): String? =
        edit(session) { it.changeTerritoryOwner(territory, owner) }

    fun changeDamage(
        session: LocalGameSession,
        territory: Territory,
        hit: Map<Unit, Int>,
        bombing: Map<Unit, Int>,
    ): String? = edit(session) { delegate ->
        // the engine rejects a map with units of several owners, so one call per owner
        hit.entries.groupBy { it.key.owner }.values.firstNotNullOfOrNull { group ->
            delegate.changeUnitHitDamage(IntegerMap(group.associate { it.key to it.value }), territory)
        } ?: bombing.entries.groupBy { it.key.owner }.values.firstNotNullOfOrNull { group ->
            delegate.changeUnitBombingDamage(IntegerMap(group.associate { it.key to it.value }), territory)
        }
    }

    fun changeResource(session: LocalGameSession, player: GamePlayer, resource: String, newTotal: Int): String? =
        edit(session) { it.changeResource(player, resource, newTotal) }

    private const val PARTIAL = "Some changes were applied. "

    /** Runs the calls in order and stops at the first error; one that follows an applied call says so. */
    fun inOrder(calls: List<() -> String?>): String? {
        calls.forEachIndexed { i, call ->
            val error = call() ?: return@forEachIndexed
            return if (i > 0 && !error.startsWith(PARTIAL)) PARTIAL + error else error
        }
        return null
    }

    fun changeTechs(
        session: LocalGameSession,
        player: GamePlayer,
        add: Collection<TechAdvance>,
        remove: Collection<TechAdvance>,
    ): String? = edit(session) { delegate ->
        val addError = if (add.isNotEmpty()) delegate.addTechAdvance(player, add) else null
        if (addError != null) addError else {
            val removeError = if (remove.isNotEmpty()) delegate.removeTechAdvance(player, remove) else null
            if (removeError != null && add.isNotEmpty()) PARTIAL + removeError else removeError
        }
    }

    fun changeRelationships(
        session: LocalGameSession,
        changes: List<Triple<GamePlayer, GamePlayer, RelationshipType>>,
    ): String? = edit(session) { it.changePoliticalRelationships(changes) }

    fun players(session: LocalGameSession): List<GamePlayer> =
        session.gameData.acquireReadLock().use { session.gameData.playerList.players.toList() }

    /** The nations plus Neutral (the null player): always for a new territory owner, for added units only when neutral units exist, as on desktop. */
    fun playersWithNeutral(session: LocalGameSession, onlyIfNeutralUnits: Boolean): List<GamePlayer> =
        session.gameData.acquireReadLock().use {
            val data = session.gameData
            val neutral = data.playerList.nullPlayer
            val offer = !onlyIfNeutralUnits || data.map.territories.any { Matches.territoryHasUnitsOwnedBy(neutral).test(it) }
            data.playerList.players.toList() + listOfNotNull(neutral.takeIf { offer })
        }

    fun unitTypes(session: LocalGameSession): List<UnitType> =
        session.gameData.acquireReadLock().use { session.gameData.unitTypeList.allUnitTypes.toList() }

    fun resources(session: LocalGameSession): List<Resource> =
        session.gameData.acquireReadLock().use { session.gameData.resourceList.resources.toList() }

    fun techEnabled(session: LocalGameSession): Boolean =
        session.gameData.acquireReadLock().use { Properties.getTechDevelopment(session.gameData.properties) }

    /** Techs the player may still get, as the desktop "Add Technology" lists them. */
    fun availableTechs(session: LocalGameSession, player: GamePlayer): List<TechAdvance> =
        session.gameData.acquireReadLock().use {
            TechnologyDelegate.getAvailableTechs(player, session.gameData.technologyFrontier).toList()
        }

    /** Techs the player owns, including the two that cannot be removed (see [canRemoveTech]). */
    fun ownedTechs(session: LocalGameSession, player: GamePlayer): List<TechAdvance> =
        session.gameData.acquireReadLock().use {
            TechTracker.getCurrentTechAdvances(player, session.gameData.technologyFrontier).toList()
        }

    /** The engine cannot undo these two techs, so desktop does not offer to remove them either. */
    fun canRemoveTech(tech: TechAdvance): Boolean =
        tech.property != TechAdvance.TECH_PROPERTY_IMPROVED_SHIPYARDS &&
            tech.property != TechAdvance.TECH_PROPERTY_INDUSTRIAL_TECHNOLOGY

    fun bombingDamageEnabled(session: LocalGameSession): Boolean = session.gameData.acquireReadLock().use {
        Properties.getDamageFromBombingDoneToUnitsInsteadOfTerritories(session.gameData.properties)
    }

    fun damageableUnits(session: LocalGameSession, territory: Territory): List<Unit> =
        session.gameData.acquireReadLock().use {
            val bombing = Properties.getDamageFromBombingDoneToUnitsInsteadOfTerritories(session.gameData.properties)
            territory.unitCollection.filter {
                it.unitAttachment.hitPoints > 1 || (bombing && Matches.unitCanBeDamaged().test(it))
            }
        }

    fun relationshipTypes(session: LocalGameSession): List<RelationshipType> =
        session.gameData.acquireReadLock().use {
            val list = session.gameData.relationshipTypeList
            // as the desktop political overview: the internal null and self relations are not choices
            list.allRelationshipTypes.filter { it != list.nullRelation && it != list.selfRelation }
        }

    fun relationship(session: LocalGameSession, a: GamePlayer, b: GamePlayer): RelationshipType =
        session.gameData.acquireReadLock().use { session.gameData.relationshipTracker.getRelationshipType(a, b) }
}
