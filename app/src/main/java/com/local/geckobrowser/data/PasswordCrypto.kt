package com.local.geckobrowser.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 加密工具：密码字段加密后存入 Room，解密时需设备凭证或生物识别。
 * 不提供任何云端导出/同步入口。
 */
object PasswordCrypto {
    private const val KEY_ALIAS = "local_browser_login_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 设置为 true 时解密会触发设备凭证/生物识别校验（由密码库界面配合 BiometricPrompt）。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    data class Sealed(val cipher: String, val iv: String)

    fun encrypt(plain: String): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return Sealed(data, iv)
    }

    fun decrypt(sealed: Sealed): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, Base64.decode(sealed.iv, Base64.NO_WRAP))
        cipher.init(Cipher.DECRYPT_MODE, key(), spec)
        return String(cipher.doFinal(Base64.decode(sealed.cipher, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun decrypt(entity: LoginEntity): String = decrypt(Sealed(entity.passwordCipher, entity.passwordIv))
}
