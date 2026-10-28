package com.elio.recipecraft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Holds the view models of the recipe open in the library's detail pane, and only that recipe's:
 * opening another recipe or closing the pane clears the last one, which stops its database
 * observation and anything it was running. Kept by the library's back stack entry, so the open
 * recipe's view model survives a configuration change.
 */
class DetailPaneStore : ViewModel() {
    private var recipeId: String? = null
    private var owner: Owner? = null

    private class Owner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    /** The store for [recipeId]'s view models; the store of any other recipe is cleared first. */
    fun ownerFor(recipeId: String): ViewModelStoreOwner {
        keepOnly(recipeId)
        return owner ?: Owner().also { owner = it }
    }

    /** Clears the store unless it belongs to [recipeId]; null clears it whatever it holds. */
    fun keepOnly(recipeId: String?) {
        if (recipeId == this.recipeId) return
        owner?.viewModelStore?.clear()
        owner = null
        this.recipeId = recipeId
    }

    override fun onCleared() {
        keepOnly(null)
    }
}
