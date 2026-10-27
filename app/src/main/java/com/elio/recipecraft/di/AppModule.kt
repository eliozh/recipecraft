package com.elio.recipecraft.di

import com.elio.recipecraft.data.llm.AndroidAssetReader
import com.elio.recipecraft.data.llm.AssetReader
import com.elio.recipecraft.data.recipe.AndroidRecipeDocuments
import com.elio.recipecraft.data.recipe.RecipeDocuments
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindAssetReader(impl: AndroidAssetReader): AssetReader

    @Binds
    abstract fun bindRecipeDocuments(impl: AndroidRecipeDocuments): RecipeDocuments

    companion object {
        @Provides
        fun provideClock(): Clock = Clock.systemUTC()
    }
}
