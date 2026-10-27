package com.elio.recipecraft

import android.app.Application
import com.elio.recipecraft.data.recipe.SavedRecipeRecalculator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RecipeCraftApp : Application() {

    @Inject
    lateinit var recalculator: SavedRecipeRecalculator

    override fun onCreate() {
        super.onCreate()
        recalculator.start()
    }
}
