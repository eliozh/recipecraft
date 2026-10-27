package com.elio.recipecraft.data.settings

import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.security.GeneralSecurityException
import javax.inject.Inject

/**
 * An AES-256-GCM key that lives only inside the Android Keystore and never leaves it.
 *
 * Tink's AndroidKeysetManager is deliberately not used: when the Keystore misbehaves it silently
 * stores its keyset in cleartext, which would defeat the encryption. Here a Keystore failure is a
 * [GeneralSecurityException] and no key is stored.
 */
class KeystoreAeadProvider @Inject constructor() : AeadProvider {

    override fun aead(): Aead = keystoreCall {
        if (!AndroidKeystore.hasKey(MASTER_KEY_ALIAS)) AndroidKeystore.generateNewAes256GcmKey(MASTER_KEY_ALIAS)
        KeystoreErrorsAsSecurityExceptions(AndroidKeystore.getAead(MASTER_KEY_ALIAS))
    }

    /** Some Keystore implementations throw unchecked exceptions such as ProviderException. */
    private class KeystoreErrorsAsSecurityExceptions(private val delegate: Aead) : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            keystoreCall { delegate.encrypt(plaintext, associatedData) }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
            keystoreCall { delegate.decrypt(ciphertext, associatedData) }
    }

    private companion object {
        const val MASTER_KEY_ALIAS = "recipecraft_api_key_master"
    }
}

private inline fun <T> keystoreCall(block: () -> T): T = try {
    block()
} catch (e: GeneralSecurityException) {
    throw e
} catch (e: RuntimeException) {
    throw GeneralSecurityException("Android Keystore failure", e)
}
