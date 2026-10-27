package com.elio.recipecraft.data.settings

import javax.inject.Qualifier

/** The DataStore holding non-secret settings; it may be backed up. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

/** The DataStore holding encrypted API keys; excluded from backup. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecretsDataStore
