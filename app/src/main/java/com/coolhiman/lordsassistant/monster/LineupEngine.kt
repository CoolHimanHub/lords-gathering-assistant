package com.coolhiman.lordsassistant.monster

data class Lineup(
    val monster: String,
    val tier: String,
    val heroes: List<String>,
    val source: String
)

object LineupEngine {
    private val lineups = listOf(
        Lineup("Blackwing", "standard", listOf("Demon Slayer", "Scarlet Bolt", "Trickster", "Tracker", "Black Crow"), "community reference"),
        Lineup("Frostwing", "standard", listOf("Demon Slayer", "Scarlet Bolt", "Trickster", "Tracker", "Black Crow"), "community reference"),
        Lineup("Gryphon", "standard", listOf("Black Crow", "Tracker", "Scarlet Bolt", "Trickster", "Death Archer"), "community reference"),
        Lineup("Hell Drider", "standard", listOf("Demon Slayer", "Scarlet Bolt", "Trickster", "Tracker", "Black Crow"), "community reference"),
        Lineup("Noceros", "f2p", listOf("Incinerator", "Sea Squire", "Bombin' Goblin", "Sage of Storms", "Snow Queen"), "community reference"),
        Lineup("Mecha Trojan", "f2p", listOf("Incinerator", "Prima Donna", "Sage of Storms", "Elementalist", "Snow Queen"), "community reference")
    )

    fun suggestions(monster: String, ownedHeroes: Set<String>): List<Lineup> =
        lineups.filter { it.monster.equals(monster, ignoreCase = true) }
            .map { lineup ->
                lineup.copy(heroes = lineup.heroes.sortedByDescending { ownedHeroes.contains(it) })
            }
}
