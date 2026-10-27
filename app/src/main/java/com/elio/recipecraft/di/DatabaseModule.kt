package com.elio.recipecraft.di

import android.content.Context
import androidx.room.Room
import com.elio.recipecraft.data.AppDatabase
import com.elio.recipecraft.data.nutrition.NutritionTableDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.FILE_NAME).build()

    @Provides
    fun provideNutritionTableDao(database: AppDatabase): NutritionTableDao = database.nutritionTables()
}
