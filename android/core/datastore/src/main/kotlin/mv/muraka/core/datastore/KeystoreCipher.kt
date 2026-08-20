package mv.muraka.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-GCM encryption with a key held in the **Android Keystore**, where the key material
 * is not extractable by the app or by anything that reads its files.
 *
 * ### Why this rather than `androidx.security:security-crypto`
 *
 * `sync-protocol.md` asks for "Android Keystore-backed `EncryptedSharedPreferences`".
 * Jetpack Security was deprecated by Google without a Jetpack replacement, and this
 * project builds with `Deprecation` as a detekt error and `warningsAsErrors` on in CI, so
 * adopting it would mean either suppressing the warning at every call site or carrying a
 * dependency the platform team has abandoned.
 *
 * What that library provides that matters here is one thing — a Keystore-backed AES-GCM
 * key wrapping the stored values — and that is thirty lines of platform API. So the
 * *property* the protocol asks for is preserved exactly; only the library is different.
 * Recorded as a deviation in `docs/08`.
 *
 * ### On key invalidation
 *
 * A Keystore key can become permanently unusable — the user removes the device lock, the
 * device is restored from a backup, or the secure hardware is reset. Decryption then
 * throws, and it will keep throwing forever. [decrypt] answers `null` in that case rather
 * than propagating, so the token store treats it as "no session" and the contributor
 * signs in again. **The outbox is untouched by this**: queued sightings are not
 * encrypted with this key and survive, which is the behaviour scenario 6 of the sync
 * protocol requires.
 */
@Singleton
class KeystoreCipher @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /** Base64 of `[12-byte IV][ciphertext||tag]`. */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ciphertext
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /** Null when the value is corrupt or the key has been invalidated. Never throws. */
    fun decrypt(encoded: String): String? = runCatching {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        if (packed.size <= IV_LENGTH) return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, packed, 0, IV_LENGTH),
        )
        String(cipher.doFinal(packed, IV_LENGTH, packed.size - IV_LENGTH), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Drops the key, which makes every previously stored value permanently undecryptable.
     *
     * Called on account deletion, where "the tokens are gone" has to mean gone rather
     * than merely unreferenced.
     */
    fun destroyKey() {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }

    private fun secretKey(): SecretKey =
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Deliberately NOT setUserAuthenticationRequired(true): the outbox drains
                // from a background worker while the phone is locked in a dry bag, and a
                // key that needs the screen unlocked would stop it. The threat this
                // protects against is another app or an adb pull reading the token file,
                // which an unextractable key already covers.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "muraka.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
