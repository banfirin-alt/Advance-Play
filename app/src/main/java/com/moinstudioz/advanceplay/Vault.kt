package com.moinstudioz.advanceplay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps ONE sign-in (email / phone / username + password) on this device, encrypted with a key that never leaves the
 * Android keystore. It is never written to the USB backup. The web screen uses it to fill in login boxes.
 */
object Vault {
    private const val ALIAS = "advanceplay_vault"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("ap_vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val have = ks.getKey(ALIAS, null)
        if (have is SecretKey) return have
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return g.generateKey()
    }

    private fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + body, Base64.NO_WRAP)
    }

    private fun dec(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            val all = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun user(c: Context): String = dec(sp(c).getString("u", "") ?: "")
    fun pass(c: Context): String = dec(sp(c).getString("p", "") ?: "")
    fun has(c: Context): Boolean = sp(c).getString("u", "").orEmpty().isNotEmpty() || sp(c).getString("p", "").orEmpty().isNotEmpty()

    /** True when saved. False when this device cannot store it safely. */
    fun save(c: Context, user: String, pass: String): Boolean = try {
        sp(c).edit().putString("u", if (user.isEmpty()) "" else enc(user)).putString("p", if (pass.isEmpty()) "" else enc(pass)).apply()
        true
    } catch (_: Exception) { false }

    fun clear(c: Context) { sp(c).edit().clear().apply() }
}
