package org.triplea.mobile.protocol

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AchievementsTest {
    @Test
    fun unitIconsOfTheEightNationsUnlockInThreeTiers() {
        assertThat(IconUnlocks.requirement("unit:Germans/infantry")).isNull()
        assertThat(IconUnlocks.requirement("unit:Germans/artillery")).isNull()
        assertThat(IconUnlocks.requirement("unit:Germans/armour")).isEqualTo(Requirement("Germans", 1))
        assertThat(IconUnlocks.requirement("unit:Japanese/submarine")).isEqualTo(Requirement("Japanese", 5))
        assertThat(IconUnlocks.requirement("unit:Russians/carrier")).isEqualTo(Requirement("Russians", 10))
        assertThat(IconUnlocks.requirement("unit:Germans/armour")!!.text).isEqualTo("1 win with Germans")
        assertThat(IconUnlocks.requirement("unit:Russians/carrier")!!.text).isEqualTo("10 wins with Russians")
        // flags, the neutral units, the emblem and anything unknown stay free
        for (free in listOf("emblem:default", "flag:Germans", "unit:armour", "unit:battleship", "unit:Dutch/armour", "unit:Germans", "unit:Germans/", "bogus")) {
            assertThat(IconUnlocks.requirement(free)).describedAs(free).isNull()
            assertThat(IconUnlocks.isUnlocked(free, emptyMap())).describedAs(free).isTrue()
        }
        assertThat(IconUnlocks.isUnlocked("unit:Germans/bomber", mapOf("Germans" to 4))).isFalse()
        assertThat(IconUnlocks.isUnlocked("unit:Germans/bomber", mapOf("Germans" to 5))).isTrue()
        assertThat(IconUnlocks.isUnlocked("unit:Germans/bomber", mapOf("Italians" to 50))).isFalse()
        // a tier is reached by exactly its win, never again by a later one
        assertThat(IconUnlocks.unlockedBy("Germans", 1)).containsExactly("unit:Germans/armour", "unit:Germans/fighter", "unit:Germans/transport")
        assertThat(IconUnlocks.unlockedBy("Germans", 5)).containsExactly("unit:Germans/bomber", "unit:Germans/destroyer", "unit:Germans/submarine")
        assertThat(IconUnlocks.unlockedBy("Germans", 2)).isEmpty()
        assertThat(IconUnlocks.unlockedBy("Dutch", 1)).isEmpty()
    }

    @Test
    fun everyNationUnitIconExceptInfantryAndArtilleryIsLocked() {
        // protocol tests run in the protocol module's folder; the app bundles the server's allow-list
        val icons = Files.readAllLines(Path.of("../app/src/main/assets/profile-icons.txt")).filter { it.isNotBlank() }
        val nationUnits = icons.filter { it.startsWith("unit:") && '/' in it }
        val locked = icons.filter { IconUnlocks.requirement(it) != null }
        assertThat(locked).hasSize(8 * 8).allMatch { it in nationUnits }
        assertThat(nationUnits - locked.toSet()).hasSize(8 * 2).allMatch { it.endsWith("/infantry") || it.endsWith("/artillery") }
        assertThat(locked.map { IconUnlocks.requirement(it)!!.nation }.toSet()).isEqualTo(Achievements.NATIONS.toSet())
    }

    @Test
    fun theCatalogHasTenGeneralAndSixtyMapBadges() {
        assertThat(Achievements.general.map { it.id })
            .containsExactly("first_victory", "conqueror", "veteran", "commander", "rated_debut", "rising_star", "elite", "world_traveller", "brothers_in_arms", "iron_will")
        assertThat(Achievements.SCENARIOS).hasSize(20)
        assertThat(Achievements.SCENARIOS.map { it.key }).doesNotHaveDuplicates()
        assertThat(Achievements.catalog).hasSize(10 + 60)
        assertThat(Achievements.catalog.map { it.id }).doesNotHaveDuplicates().noneMatch { it.startsWith("nation_master:") }

        val classic = Achievements.SCENARIOS.first()
        assertThat(classic).isEqualTo(Scenario("World War II Classic", "world_war_ii_classic", listOf("Russians", "Germans", "British", "Japanese", "Americans")))
        assertThat(classic.key).isEqualTo("worldwariiclassic")
        assertThat(Achievements.mapBadges(classic)).containsExactly(
            Badge("map_win:worldwariiclassic", "World War II Classic – First win", "1 win"),
            Badge("map_wins10:worldwariiclassic", "World War II Classic – 10 wins", "10 wins"),
            Badge("map_master:worldwariiclassic", "World War II Classic – Map Master", "A win with every nation"),
        )
        assertThat(Achievements.badge("map_master:worldwariiclassic")).isEqualTo(Achievements.mapBadges(classic)[2])
        // keys are the engine's normalizeName: case, spaces, '_' and '-' ignored, everything else kept
        assertThat(Achievements.SCENARIOS.single { it.name == "Classic: Iron Blitz 3rd Edition" }.key).isEqualTo("classic:ironblitz3rdedition")
        assertThat(Achievements.SCENARIOS.single { it.name == "Middle Earth - Battle for Arda (v2)" }.key).isEqualTo("middleearthbattleforarda(v2)")
        assertThat(Scenario("world_war_ii_classic", "x", emptyList()).key).isEqualTo(classic.key)
        assertThat(Achievements.badge("first_victory")!!.description).isEqualTo("1 win against players")
        assertThat(Achievements.badge("unit:Germans/armour")).isNull()
    }

    @Test
    fun everyScenarioHasAtLeastTwoRealNations() {
        // the lists themselves are checked against the XMLs by the plan's curl script (network, outside the suite)
        for (scenario in Achievements.SCENARIOS) {
            assertThat(scenario.nations).describedAs(scenario.name).hasSizeGreaterThanOrEqualTo(2).doesNotHaveDuplicates()
                .noneMatch { it.startsWith("Neutral") || it.startsWith("Pro-") || it.startsWith("AI_") || it == "GameSetup" || it == "MinorClans" }
        }
        assertThat(Achievements.SCENARIOS.single { it.name == "Civil War: A House Divided" }.nations).containsExactly("Confederate", "Union")
        assertThat(Achievements.SCENARIOS.single { it.name == "World War II Global 1940 2nd Edition" }.nations).hasSize(10).doesNotContain("Dutch", "Mongolians")
    }
}
