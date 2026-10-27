package com.elio.recipecraft.di

import android.content.Context
import com.elio.recipecraft.data.nutrition.AndroidCsvDocuments
import com.elio.recipecraft.data.nutrition.CombinedNutritionLookups
import com.elio.recipecraft.data.nutrition.CsvDocuments
import com.elio.recipecraft.data.nutrition.NutritionLookups
import com.elio.recipecraft.data.nutrition.UsdaDatabase
import com.elio.recipecraft.data.nutrition.UsdaFoodDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NutritionModule {

    @Binds
    abstract fun bindNutritionLookups(impl: CombinedNutritionLookups): NutritionLookups

    @Binds
    abstract fun bindCsvDocuments(impl: AndroidCsvDocuments): CsvDocuments

    companion object {
        @Provides
        @Singleton
        fun provideUsdaDatabase(@ApplicationContext context: Context): UsdaDatabase = UsdaDatabase.open(context)

        @Provides
        fun provideUsdaFoodDao(database: UsdaDatabase): UsdaFoodDao = database.foods()
    }
}
