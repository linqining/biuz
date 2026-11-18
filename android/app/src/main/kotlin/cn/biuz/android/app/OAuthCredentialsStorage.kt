package cn.biuz.android.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import cn.biuz.android.connection.CredentialReadPolicy
import cn.biuz.android.connection.OAuthCredentialPersistence
import cn.biuz.android.connection.OAuthCredentialReadFailure
import cn.biuz.android.connection.OAuthCredentialReadOutcome
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
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
 * **读取失败语义（2026-11-19，web v3.18.2 凭证安全对齐）**：
 * 原实现 `catch { clear(); null }` 把「密钥失效 / 换了设备 / 数据损坏」三种完全不同的
 * 情况一律处理成「毁掉凭据」——web `systemCredentialCipher.ts:67-75` 明文禁止：
 * 「系统库锁定/缺失不能冒充密文损坏，否则既有 OAuth 恢复逻辑会误删登录」。
 * 现在按原因分类（[OAuthCredentialReadFailure]），**只有密文确坏**
 * （`AEADBadTagException` / 容器格式非法）才允许清理；密钥不可用一律保留数据。
 * 清理决策本身在 `CredentialReadPolicy.canPurge`（纯函数、可单测），本类只做异常→原因映射。
 */
internal class KeystoreOAuthCredentialPersistence(context: Context) : OAuthCredentialPersistence {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): String? = loadOutcome().payloadOrNull

    override fun loadOutcome(): OAuthCredentialReadOutcome {
        val stored = try {
            prefs.getString(KEY_PAYLOAD, null)
        } catch (err: Exception) {
            // SharedPreferences 读本身失败：数据未知，保留（不清理）
            return OAuthCredentialReadOutcome.Failed(
                OAuthCredentialReadFailure.StorageUnavailable(err.describe()),
            )
        } ?: return OAuthCredentialReadOutcome.Absent

        val blob = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (err: Exception) {
            return corruptOrUnavailable(envelopeMalformed = true, detail = err.describe())
        }
        if (blob.size <= IV_SIZE) {
            // 容器写了一半 / 被截断：内容无法判读，不当成密文确坏（数据可能只是写入中断）
            return OAuthCredentialReadOutcome.Failed(
                OAuthCredentialReadFailure.EnvelopeMalformed("blob-too-short:${blob.size}"),
            )
        }
        val iv = blob.copyOfRange(0, IV_SIZE)
        val ciphertext = blob.copyOfRange(IV_SIZE, blob.size)

        val key: SecretKey = try {
            existingSecretKey() ?: return corruptOrUnavailable(
                keyMissing = true,
                detail = "keystore-entry-absent",
            )
        } catch (err: KeyPermanentlyInvalidatedException) {
            // 换锁屏/清凭据：密钥永久失效 = 密钥不可用语义（web `CREDENTIAL_SECURE_STORAGE_UNAVAILABLE`）
            return OAuthCredentialReadOutcome.Failed(
                OAuthCredentialReadFailure.KeystoreUnavailable(err.describe()),
            )
        } catch (err: UnrecoverableKeyException) {
            return OAuthCredentialReadOutcome.Failed(
                OAuthCredentialReadFailure.KeystoreUnavailable(err.describe()),
            )
        } catch (err: Exception) {
            return corruptOrUnavailable(keyUnavailable = true, detail = err.describe())
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            OAuthCredentialReadOutcome.Value(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (err: AEADBadTagException) {
            // GCM 认证失败 = 密文确坏（web `open()` 认证失败 → decryptError，唯一可清理档）
            corruptOrUnavailable(authenticatedEncryptionFailed = true, detail = err.describe())
        } catch (err: KeyPermanentlyInvalidatedException) {
            OAuthCredentialReadOutcome.Failed(
                OAuthCredentialReadFailure.KeystoreUnavailable(err.describe()),
            )
        } catch (err: Exception) {
            // 其余（含 BadPadding/IllegalBlockSize/密钥在 init 阶段失效）按纯策略折算
            corruptOrUnavailable(detail = err.describe())
        }
    }

    /** 统一折算到 [CredentialReadPolicy.classifyDecryptFailure]（判定顺序集中一处）。 */
    private fun corruptOrUnavailable(
        envelopeMalformed: Boolean = false,
        keyUnavailable: Boolean = false,
        keyMissing: Boolean = false,
        authenticatedEncryptionFailed: Boolean = false,
        detail: String,
    ): OAuthCredentialReadOutcome.Failed {
        val reason = CredentialReadPolicy.classifyDecryptFailure(
            envelopeMalformed = envelopeMalformed,
            keyUnavailable = keyUnavailable,
            keyMissing = keyMissing,
            authenticatedEncryptionFailed = authenticatedEncryptionFailed,
            detail = detail,
        )
        return OAuthCredentialReadOutcome.Failed(reason)
    }

    private fun Exception.describe(): String = "${javaClass.simpleName}:${message ?: ""}"

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
        existingSecretKey()?.let { return it }
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

    /**
     * **只读**取现有密钥，缺失返回 null——解密路径绝不能顺手生成新密钥：
     * 新密钥解不开旧密文，会立刻把「密钥丢失」误判成「密文损坏」并触发清理
     * （web `systemCredentialCipher.ts:266` 注释原文：「解密缺密钥的旧文件时禁止生成
     * 替代密钥、误判损坏并清理身份」）。
     */
    private fun existingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
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
