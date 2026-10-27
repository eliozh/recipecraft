package com.elio.recipecraft.ui.recipe

import com.elio.recipecraft.domain.Ingredient
import com.elio.recipecraft.domain.Step

/** How one line of a recipe changed between two versions. */
enum class Change { SAME, ADDED, REMOVED, CHANGED }

/** One line of a diff: [before] is null for an added line, [after] for a removed one. */
data class DiffEntry<T>(val change: Change, val before: T?, val after: T?)

/**
 * The lines of [before] and [after] in order, each marked as kept, added, removed or changed.
 *
 * Lines are lined up by [sameItem], keeping the longest run in order; a lined-up pair is SAME when
 * [unchanged] holds, else CHANGED. When [pairReplacements] is set, removed and added lines at the
 * same place are paired up as CHANGED, for lines with nothing that identifies them but their text.
 */
fun <T> diff(
    before: List<T>,
    after: List<T>,
    sameItem: (T, T) -> Boolean,
    unchanged: (T, T) -> Boolean = { a, b -> a == b },
    pairReplacements: Boolean = false,
): List<DiffEntry<T>> {
    // lengths[i][j]: the longest run of lined-up items in before[i..] and after[j..].
    val lengths = Array(before.size + 1) { IntArray(after.size + 1) }
    for (i in before.indices.reversed()) {
        for (j in after.indices.reversed()) {
            lengths[i][j] = if (sameItem(before[i], after[j])) {
                lengths[i + 1][j + 1] + 1
            } else {
                maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
    }
    val entries = mutableListOf<DiffEntry<T>>()
    val removed = mutableListOf<T>()
    val added = mutableListOf<T>()
    fun flush() {
        val paired = if (pairReplacements) minOf(removed.size, added.size) else 0
        repeat(paired) { entries += DiffEntry(Change.CHANGED, removed[it], added[it]) }
        removed.drop(paired).forEach { entries += DiffEntry(Change.REMOVED, it, null) }
        added.drop(paired).forEach { entries += DiffEntry(Change.ADDED, null, it) }
        removed.clear()
        added.clear()
    }
    var i = 0
    var j = 0
    while (i < before.size || j < after.size) {
        when {
            i < before.size && j < after.size && sameItem(before[i], after[j]) -> {
                flush()
                val change = if (unchanged(before[i], after[j])) Change.SAME else Change.CHANGED
                entries += DiffEntry(change, before[i], after[j])
                i++
                j++
            }
            j >= after.size || (i < before.size && lengths[i + 1][j] >= lengths[i][j + 1]) -> removed += before[i++]
            else -> added += after[j++]
        }
    }
    flush()
    return entries
}

/** Ingredients lined up by their English match key, else their name, so a new amount reads as a change. */
fun ingredientDiff(before: List<Ingredient>, after: List<Ingredient>): List<DiffEntry<Ingredient>> =
    diff(before, after, sameItem = { a, b -> a.key == b.key })

private val Ingredient.key: String get() = nameEn.ifBlank { name }.trim().lowercase()

/**
 * Steps lined up by their text, ignoring their number, since one new step renumbers the rest. A
 * step rewritten in place shows as changed.
 */
fun stepDiff(before: List<Step>, after: List<Step>): List<DiffEntry<Step>> =
    diff(
        before,
        after,
        sameItem = { a, b -> a.text.trim() == b.text.trim() },
        unchanged = { a, b -> a.minutes == b.minutes },
        pairReplacements = true,
    )
