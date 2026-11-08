package cn.biuz.android.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cn.biuz.android.connection.OAuthCredentialPersistence
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * OAuth 账户凭据的 Android 持久化（iOS Keychain 对应物）。
 *
 * 用 Android Keystore 里的 AES-256/GCM 密钥加密后落 SharedPreferences：
 * 密钥不可导出、只有本 App 能解密；明文凭据不落盘（对齐 iOS「token 仅 Keychain」口径）。
 *
 * 解密失败（密钥失效/换了设备/数据损坏）一律返回 null 并清掉残留——宁可要求重新登录，
 * 也不返回半截凭据（iOS 读取失败同口径）。
 */
internal class KeystoreOAuthCredentialPersistence(context: Context) : OAuthCredentialPersistence {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): String? {
        val stored = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            if (blob.size <= IV_SIZE) {
                clear()
                return null
            }
            val iv = blob.copyOfRange(0, IV_SIZE)
            val ciphertext = blob.copyOfRange(IV_SIZE, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (err: Exception) {
            clear()
            null
        }
    }

    override fun save(payload: String): Boolean {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + ciphertext
            prefs.edit().putString(KEY_PAYLOAD, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
            true
        } catch (err: Exception) {
            false
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "cn.biuz.android.oauth"
        const val KEY_PAYLOAD = "oauth.credentials.v1" // iOS Keychain account 同名前缀
        const val KEY_ALIAS = "biuz.oauth.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
