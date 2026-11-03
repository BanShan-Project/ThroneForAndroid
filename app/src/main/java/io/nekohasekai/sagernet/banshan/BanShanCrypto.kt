package io.nekohasekai.sagernet.banshan

import android.util.Base64
import io.nekohasekai.sagernet.ktx.Logs
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The desktop's BanShanCrypto (ServiceLib/Common/BanShanCrypto.cs): the subscription address the login server returns
 * is kept AES-CBC encrypted behind [PREFIX], so the `groups` row, a database dump or a `.thrbackup` file never holds
 * the address in plain text. It hides the address from a casual look; it is not a secret store, since the key ships
 * with the app. The format is the desktop's `bs1:<iv base64>.<cipher base64>`.
 */
object BanShanCrypto {

    /** Marks the stored subscription URL of a login-managed group. */
    const val PREFIX = "bs1:"

    private const val SEED = "BanShanProxy-2024-v2rayN-Hidden-Sub"
    private const val IV_SIZE = 16

    private val key: ByteArray = MessageDigest.getInstance("SHA-256").digest(SEED.toByteArray(Charsets.UTF_8))
    private val random = SecureRandom()

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /** The encrypted form, or null when there is nothing to store or the cipher failed. */
    fun encrypt(plainText: String?): String? {
        val text = plainText?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            val iv = ByteArray(IV_SIZE).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logs.w("BanShan encrypt failed", e)
            null
        }
    }

    /**
     * The plain text of an encrypted value. Anything that is not encrypted (a URL stored before this feature, or a
     * plain subscription) comes back as it is; null for a value that cannot be decrypted.
     */
    fun decrypt(cipherText: String?): String? {
        val text = cipherText?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!isEncrypted(text)) return text
        return try {
            val parts = text.substring(PREFIX.length).split('.')
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Logs.w("BanShan decrypt failed", e)
            null
        }
    }
}
