package com.coolhiman.lordsassistant.monster

object HeroSelectionMatcher {
    /**
     * Returns only heroes missing from a suggested lineup.
     * This never changes the in-game selection by itself.
     */
    fun missingHeroes(selectedHeroes: Set<String>, lineup: Lineup): List<String> =
        lineup.heroes.filterNot { selectedHeroes.contains(it) }

    fun alreadySelected(selectedHeroes: Set<String>, lineup: Lineup): Boolean =
        lineup.heroes.all { selectedHeroes.contains(it) }
}
