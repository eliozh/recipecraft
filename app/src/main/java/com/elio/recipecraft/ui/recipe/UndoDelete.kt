package com.elio.recipecraft.ui.recipe

import com.elio.recipecraft.domain.Recipe
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The recipe the detail screen just deleted, kept in memory so the library's "Recipe deleted"
 * snackbar can put it back exactly as it was. It is dropped once the snackbar is dismissed or undone.
 */
@Singleton
class UndoDelete @Inject constructor() {

    private val _pending = MutableStateFlow<Recipe?>(null)

    /** The recipe that can still be restored, or null. */
    val pending: StateFlow<Recipe?> = _pending.asStateFlow()

    fun offer(recipe: Recipe) {
        _pending.value = recipe
    }

    /** Clears [recipe] if it is still pending; true when it was, so only one caller acts on it. */
    fun take(recipe: Recipe): Boolean = _pending.compareAndSet(recipe, null)
}
