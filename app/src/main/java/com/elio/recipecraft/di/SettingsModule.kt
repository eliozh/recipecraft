package com.elio.recipecraft.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elio.recipecraft.data.settings.AeadProvider
import com.elio.recipecraft.data.settings.AppLanguageSettings
import com.elio.recipecraft.data.settings.LocaleManagerAppLanguageSettings
import com.elio.recipecraft.data.settings.KeystoreAeadProvider
import com.elio.recipecraft.data.settings.SecretStore
import com.elio.recipecraft.data.settings.SecretsDataStore
import com.elio.recipecraft.data.settings.SettingsDataStore
import com.elio.recipecraft.data.settings.TinkSecretStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    abstract fun bindSecretStore(impl: TinkSecretStore): SecretStore

    @Binds
    abstract fun bindAppLanguageSettings(impl: LocaleManagerAppLanguageSettings): AppLanguageSettings

    @Binds
    abstract fun bindAeadProvider(impl: KeystoreAeadProvider): AeadProvider

    companion object {
        /** File names must match the exclusions in res/xml/data_extraction_rules.xml. */
        const val SETTINGS_FILE = "settings"
        const val SECRETS_FILE = "secrets"

        @Provides
        @Singleton
        @SettingsDataStore
        fun provideSettingsDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(SETTINGS_FILE) },
        )

        @Provides
        @Singleton
        @SecretsDataStore
        fun provideSecretsDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(SECRETS_FILE) },
        )
    }
}
