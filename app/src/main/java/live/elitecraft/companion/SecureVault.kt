package live.elitecraft.companion

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

@Suppress("DEPRECATION")
class SecureVault(ctx: Context) {
    private val prefs: SharedPreferences by lazy {
        val mk = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "gmate_companion_vault",
            mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveSession(token: String, username: String) =
        prefs.edit().putString("token", token).putString("username", username).apply()

    fun token(): String = prefs.getString("token", "") ?: ""
    fun username(): String = prefs.getString("username", "") ?: ""
    fun clear() = prefs.edit().clear().apply()
}
