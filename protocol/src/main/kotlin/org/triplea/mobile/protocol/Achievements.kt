package org.triplea.mobile.protocol

import java.util.Locale

/** A badge of [Achievements.catalog]: the [id] the server stores, its English [name] and what it needs ([description]). */
data class Badge(val id: String, val name: String, val description: String)

/**
 * A scenario with map badges: its game XML's exact [name] (`<info name>`, stored as `games.game_name`), its [map]
 * folder (the preview picture) and its [nations] for Map Master: the XML's non-optional players without pseudo players
 * (`GameSetup`, `MinorClans`), in `playerList` order.
 */
data class Scenario(val name: String, val map: String, val nations: List<String>) {
    /** [name] as the engine's `normalizeName` matches it (lower case, spaces, `_` and `-` removed); the server's SQL does the same. */
    val key: String = name.lowercase(Locale.ROOT).replace(Regex("[ _-]"), "")
}

/**
 * The badges a player can earn, shared by the server (which awards them, `social/Achievements.kt`) and the app (which
 * shows them): 10 general ones and three per scenario of [SCENARIOS]. Badges stay once earned.
 */
object Achievements {
    /** The nations with their own unit images: their unit icons unlock with wins ([IconUnlocks]). */
    val NATIONS = listOf("Americans", "British", "Chinese", "French", "Germans", "Italians", "Japanese", "Russians")

    /** The 20 scenarios with map badges; names and nations checked against the map repos (plan 2026-10-21-map-achievements). */
    val SCENARIOS: List<Scenario> = run {
        val classic5 = listOf("Russians", "Germans", "British", "Japanese", "Americans")
        listOf(
            Scenario("World War II Classic", "world_war_ii_classic", classic5),
            Scenario("World War II Revised", "world_war_ii_revised", classic5),
            Scenario("World War II v3 1941", "world_war_ii_v3", listOf("Germans", "Russians", "Japanese", "British", "Italians", "Chinese", "Americans")),
            Scenario("World War II v3 1942", "world_war_ii_v3", listOf("Japanese", "Russians", "Germans", "British", "Italians", "Chinese", "Americans")),
            Scenario("World War II v4", "world_war_ii_v4", classic5),
            Scenario("World War II v5 1942 Second Edition", "world_war_ii_v5_1942", classic5),
            Scenario("Classic: Iron Blitz 3rd Edition", "world_war_ii_classic", classic5),
            Scenario("World War II v6 1941", "world_war_ii_v6_1941", classic5),
            Scenario(
                "World War II Global 1940 2nd Edition", "world_war_ii_global",
                listOf("Germans", "Russians", "Japanese", "Americans", "Chinese", "British", "UK_Pacific", "Italians", "ANZAC", "French"),
            ),
            Scenario("World War II Pacific 1940 2nd Edition", "world_war_ii_pacific", listOf("Japanese", "Americans", "Chinese", "British", "ANZAC")),
            Scenario("World War II Europe 1940 2nd Edition", "world_war_ii_europe", listOf("Germans", "Russians", "Americans", "British", "Italians", "French")),
            Scenario("Pact of Steel", "the_pact_of_steel", listOf("Italians", "Russians", "Germans", "British", "Japanese", "Americans")),
            Scenario("Big World : 1942", "big_world", listOf("Russians", "Germans", "British", "Chinese", "Japanese", "Americans")),
            Scenario(
                "Domination 1914 No Man's Land", "domination_1914_no_mans_land",
                listOf("Germany", "France", "Serbia", "Austria", "Italy", "Arabia", "Turkey", "Britain", "Russia", "Communists", "USA"),
            ),
            Scenario(
                "Napoleonic Empires", "napoleonic_empires",
                listOf("France", "UnitedKingdom", "Spain", "KingdomOfPrussia", "Sweden", "AustrianEmpire", "OttomanEmpire", "Russia"),
            ),
            Scenario("270BC", "270bc", listOf("Carthage", "RomanRepublic", "GreekCityStates", "Macedonia", "Egypt", "Seleucid", "Parthia", "Numidia")),
            Scenario(
                "Feudal Japan", "feudal_japan",
                listOf(
                    "Chosokabe", "Shimazu", "Ryuzoji", "Otomo", "Mori", "Satomi", "Date", "Hatakeyama", "Asakura", "Hojo", "Urakami",
                    "Yamana", "Uesugi", "Takeda", "Oda", "Miyoshi", "Asai",
                ),
            ),
            Scenario(
                "Middle Earth - Battle for Arda (v2)", "battle_for_arda",
                listOf(
                    "Saruman", "Angmar", "Mordor", "Arnor", "Gondor", "Northmen", "Lorien", "Orcs", "Rhun", "HighElves", "WoodlandRealm",
                    "Harad", "DolGuldur", "Freefolk", "Dwarves", "Rohan",
                ),
            ),
            Scenario("Civil War: A House Divided", "civil_war", listOf("Confederate", "Union")),
            Scenario(
                "Total World War: December 1941 3.0", "total_world_war",
                listOf(
                    "Germany", "VichyFrance", "DanubeAxis", "Finland", "Russia", "Japan", "Manchuria", "Thailand", "China", "Britain",
                    "Canada", "ExiledAllies", "Egypt", "SouthAfrica", "India", "Australia", "Italy", "Usa",
                ),
            ),
        )
    }

    val general: List<Badge> = listOf(
        Badge("first_victory", "First Victory", "1 win against players"),
        Badge("conqueror", "Conqueror", "10 wins against players"),
        Badge("veteran", "Veteran", "10 finished games"),
        Badge("commander", "Commander", "50 finished games"),
        Badge("rated_debut", "Rated Debut", "1 rated game"),
        Badge("rising_star", "Rising Star", "A rating of 1300"),
        Badge("elite", "Elite", "A rating of 1500"),
        Badge("world_traveller", "World Traveller", "Finished games on 5 maps"),
        Badge("brothers_in_arms", "Brothers in Arms", "A win with another player on your team"),
        Badge("iron_will", "Iron Will", "10 finished games without resigning"),
    )

    /** The three badges of [scenario]: first win, 10 wins, a win with every nation (Map Master). */
    fun mapBadges(scenario: Scenario): List<Badge> = listOf(
        Badge("map_win:${scenario.key}", "${scenario.name} – First win", "1 win"),
        Badge("map_wins10:${scenario.key}", "${scenario.name} – 10 wins", "10 wins"),
        Badge("map_master:${scenario.key}", "${scenario.name} – Map Master", "A win with every nation"),
    )

    val catalog: List<Badge> = general + SCENARIOS.flatMap(::mapBadges)

    /** The badge [id], null for anything else (e.g. an icon id in [GameInfoDto.unlocks]). */
    fun badge(id: String): Badge? = catalog.find { it.id == id }
}

/** What a locked icon needs: [wins] wins with [nation]. */
data class Requirement(val nation: String, val wins: Int) {
    /** "1 win with Germans", "5 wins with Germans". */
    val text: String get() = "$wins win${if (wins == 1) "" else "s"} with $nation"
}

/**
 * Which profile icons need wins: `unit:<Nation>/<unit>` of the [Achievements.NATIONS] except infantry and artillery.
 * Flags, the neutral units (`unit:<unit>`) and `emblem:default` are free. Whether a user keeps a locked icon they already
 * use is the server's rule (`PUT /account/profile`), not this one.
 */
object IconUnlocks {
    // unit to the wins with its nation it needs, in the order the unlock lines list them
    private val TIERS = linkedMapOf(
        "armour" to 1, "fighter" to 1, "transport" to 1,
        "bomber" to 5, "destroyer" to 5, "submarine" to 5,
        "battleship" to 10, "carrier" to 10,
    )

    /** What icon [iconId] needs; null if it is free. */
    fun requirement(iconId: String): Requirement? {
        if (!iconId.startsWith("unit:")) return null
        val nation = iconId.removePrefix("unit:").substringBefore('/', "")
        val wins = TIERS[iconId.substringAfter('/', "")] ?: return null
        return Requirement(nation, wins).takeIf { nation in Achievements.NATIONS }
    }

    /** Whether a user with [winsByNation] may choose [iconId]. */
    fun isUnlocked(iconId: String, winsByNation: Map<String, Int>): Boolean =
        requirement(iconId)?.let { (winsByNation[it.nation] ?: 0) >= it.wins } ?: true

    /** The icons that the [wins]th win with [nation] unlocks: a whole tier when [wins] is exactly its number, else none. */
    fun unlockedBy(nation: String, wins: Int): List<String> =
        if (nation !in Achievements.NATIONS) emptyList() else TIERS.filterValues { it == wins }.keys.map { "unit:$nation/$it" }
}
