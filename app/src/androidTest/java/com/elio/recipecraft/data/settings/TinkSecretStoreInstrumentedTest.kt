package com.elio.recipecraft.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android Keystore-backed AEAD; the JVM tests cover the storage logic. */
@RunWith(AndroidJUnit4::class)
class TinkSecretStoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(context.filesDir, "test_datastore/secrets_test.preferences_pb").apply { delete() }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    /** Opens a store the way a fresh process would: new DataStore, new Keystore-backed AEAD. */
    private fun openStore(job: Job) = TinkSecretStore(
        dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file },
        aeadProvider = KeystoreAeadProvider(),
        ioDispatcher = Dispatchers.IO,
    )

    @Test
    fun roundTripAndFreshInstance() = runBlocking {
        val firstJob = Job()
        val store = openStore(firstJob)
        assertNull(store.getKey(ProviderType.OPENAI))

        store.setKey(ProviderType.OPENAI, PLACEHOLDER)
        store.setKey(ProviderType.DEEPSEEK, OTHER_PLACEHOLDER)
        assertEquals(PLACEHOLDER, store.getKey(ProviderType.OPENAI))
        // Only one DataStore may own a file at a time, so close this one before reopening.
        firstJob.cancelAndJoin()

        assertFalse(file.readBytes().decodeToString().contains("PLACEHOLDER"))

        val secondJob = Job()
        val reopened = openStore(secondJob)
        assertEquals(PLACEHOLDER, reopened.getKey(ProviderType.OPENAI))
        assertEquals(OTHER_PLACEHOLDER, reopened.getKey(ProviderType.DEEPSEEK))

        reopened.clear(ProviderType.OPENAI)
        assertNull(reopened.getKey(ProviderType.OPENAI))
        assertEquals(OTHER_PLACEHOLDER, reopened.getKey(ProviderType.DEEPSEEK))
        secondJob.cancelAndJoin()
    }

    private companion object {
        const val PLACEHOLDER = "sk-test-PLACEHOLDER-1234"
        const val OTHER_PLACEHOLDER = "sk-test-PLACEHOLDER-5678"
    }
}
