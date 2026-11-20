package tw.skyhong.pocketlog

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val endpoint: String, val token: String) {
    override fun toString() = "Credentials(redacted)"
}

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val alias = "pocketlog.connection.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(credentials: Credentials) {
        require(EndpointPolicy.normalize(credentials.endpoint) == credentials.endpoint && EndpointPolicy.validToken(credentials.token))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val clear = JSONObject().put("endpoint", credentials.endpoint).put("token", credentials.token).toString().toByteArray()
        val encrypted = cipher.doFinal(clear)
        clear.fill(0)
        check(preferences.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun load(): Credentials? {
        val snapshot = preferences.all
        val encrypted = snapshot["ciphertext"] as? String ?: return null
        val iv = snapshot["iv"] as? String ?: error("Connection needs resetting")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        val clear = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
        try {
            val value = JSONObject(String(clear, Charsets.UTF_8))
            val credentials = Credentials(value.getString("endpoint"), value.getString("token"))
            require(EndpointPolicy.normalize(credentials.endpoint) == credentials.endpoint && EndpointPolicy.validToken(credentials.token))
            return credentials
        } finally { clear.fill(0) }
    }
    fun clear() { check(preferences.edit().clear().commit()) }
}
