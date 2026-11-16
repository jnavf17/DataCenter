package com.example.datacenter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class Rol(val nombre: String) { ADMIN("Administrador"), OPERADOR("Operador") }

object Seguridad {
    private const val ALIAS = "datacenter_key"

    // Clave AES-256 guardada dentro del Android Keystore (no es exportable)
    private fun claveKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return g.generateKey()
    }

    // Cifrado de datos en reposo (hash de claves, MAC autorizada)
    fun guardarCifrado(texto: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, claveKeystore())
        return Base64.encodeToString(c.iv + c.doFinal(texto.toByteArray()), Base64.NO_WRAP)
    }

    fun leerCifrado(b64: String): String {
        val d = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, claveKeystore(), GCMParameterSpec(128, d, 0, 12))
        return String(c.doFinal(d, 12, d.size - 12))
    }

    // PBKDF2: nunca se guarda la clave, solo su hash con sal
    fun nuevaSal(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hashClave(clave: String, sal: ByteArray): String {
        val spec = PBEKeySpec(clave.toCharArray(), sal, 120_000, 256)
        val h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(h, Base64.NO_WRAP)
    }

    // ---- Canal Wi-Fi: AES-256-GCM con clave derivada de la "clave de enlace" ----
    fun claveEnlace(clave: String): SecretKey {
        val spec = PBEKeySpec(clave.toCharArray(), "datacenter-enlace-v1".toByteArray(), 100_000, 256)
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(k, "AES")
    }

    fun cifrar(k: SecretKey, texto: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(texto.toByteArray()), Base64.NO_WRAP)
    }

    // Devuelve null si el mensaje fue alterado o se cifró con otra clave (suplantación)
    fun descifrar(k: SecretKey, b64: String): String? = try {
        val d = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, d, 0, 12))
        String(c.doFinal(d, 12, d.size - 12))
    } catch (e: Exception) { null }

    // ---- MAC del HC-05 autorizado (anti-suplantación de dispositivo) ----
    fun macAutorizada(c: Context): String? =
        c.getSharedPreferences("disp", Context.MODE_PRIVATE).getString("mac", null)
            ?.let { try { leerCifrado(it) } catch (e: Exception) { null } }

    fun autorizarMac(c: Context, mac: String) {
        c.getSharedPreferences("disp", Context.MODE_PRIVATE).edit().putString("mac", guardarCifrado(mac)).apply()
    }

    fun olvidarMac(c: Context) {
        c.getSharedPreferences("disp", Context.MODE_PRIVATE).edit().remove("mac").apply()
    }
}

// ---- Reglas de contraseña y bloqueo por intentos (los usuarios los guarda Firebase) ----
object Auth {
    private fun b(c: Context) = c.getSharedPreferences("bloqueo", Context.MODE_PRIVATE)
    private fun norm(correo: String) = correo.trim().lowercase()

    fun correoValido(correo: String) =
        android.util.Patterns.EMAIL_ADDRESS.matcher(norm(correo)).matches()

    // Método booleano de verificación: contraseña segura
    fun claveValida(clave: String) =
        clave.length >= 8 &&
            clave.any { it.isUpperCase() } &&
            clave.any { it.isLowerCase() } &&
            clave.any { it.isDigit() } &&
            clave.any { !it.isLetterOrDigit() }

    const val REGLA_CLAVE = "Mínimo 8 caracteres con mayúscula, minúscula, número y símbolo"

    // Bloqueo por usuario: 3 fallos seguidos = 30 s sin poder intentar
    fun bloqueadoHasta(c: Context, correo: String) = b(c).getLong("b_" + norm(correo), 0L)

    fun fallo(c: Context, correo: String) {
        val k = norm(correo)
        val n = b(c).getInt("f_$k", 0) + 1
        val e = b(c).edit()
        if (n >= 3) e.putInt("f_$k", 0).putLong("b_$k", System.currentTimeMillis() + 30_000)
        else e.putInt("f_$k", n)
        e.apply()
    }

    fun ok(c: Context, correo: String) { b(c).edit().putInt("f_" + norm(correo), 0).apply() }
}
