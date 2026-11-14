package com.tv.player.core

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 网盘登录、网络存储密码这些设置加密后再存（AES-GCM，密钥在 Android Keystore 里、不可导出）。
 * 挡的是直接读设置文件（jar 爬虫扫文件、拷走数据目录）；同一应用身份的代码照样能调 Keystore 解密，所以这只是一层，
 * 爬虫另外放在单独进程里（见 SpiderHost）。Android 6.0 以下没有可用的 Keystore AES，照旧明文。
 * 加密失败时存明文（总比存不进去强）；解不开（Keystore 偶尔出错、密钥丢了）时当作没登录，但不缓存、下次再试。
 */
object Secrets {
    private const val TAG = "Secrets"
    private const val ALIAS = "tvp_secrets"
    private const val PREFIX = "enc1:"

    fun isSealed(stored: String) = stored.startsWith(PREFIX)

    fun seal(plain: String): String {
        if (plain.isEmpty() || Build.VERSION.SDK_INT < 23) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "加密失败，按明文存", e)
            plain
        }
    }

    /** 解开存的值；旧版本的明文原样返回；解不开时 null（调用方别把它当成「没有」去覆盖）。 */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored // 旧版本存的明文
        if (Build.VERSION.SDK_INT < 23) return null
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) {
            Log.w(TAG, "解密失败", e)
            null
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
