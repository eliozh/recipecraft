package com.elio.recipecraft.di

import com.elio.recipecraft.data.llm.AndroidAssetReader
import com.elio.recipecraft.data.llm.AssetReader
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

    companion object {
        @Provides
        fun provideClock(): Clock = Clock.systemUTC()
    }
}
