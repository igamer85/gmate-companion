package live.elitecraft.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val http = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private lateinit var vault: SecureVault
    private var guildId = ""
    private var pendingStart: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingStart?.invoke(); pendingStart = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        vault = SecureVault(this)

        val etUser = findViewById<EditText>(R.id.etUser)
        val etPass = findViewById<EditText>(R.id.etPass)
        val cbUnmute = findViewById<CheckBox>(R.id.cbAllowUnmute)
        val etSquad = findViewById<EditText>(R.id.etSquad)

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val u = etUser.text.toString().trim(); val p = etPass.text.toString()
            if (u.length < 3 || p.length < 4) { setStatus("Enter username (3+) & password (4+)"); return@setOnClickListener }
            postJson("/v1/auth/login", JSONObject().put("username", u).put("password", p), "") { ok, obj ->
                runOnUiThread {
                    val tok = obj?.optString("token") ?: ""
                    val user = obj?.optJSONObject("user")?.optString("username") ?: u
                    if (ok && tok.isNotBlank()) { vault.saveSession(tok, user); etPass.setText(""); refreshUi(); loadAccountMeta() }
                    else setStatus(obj?.optString("error") ?: "Login failed")
                }
            }
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            val tok = vault.token(); postJson("/v1/auth/logout", JSONObject(), tok) { _, _ -> }
            vault.clear(); sendCmd(VcForegroundService.ACTION_STOP)
            runOnUiThread { refreshUi(); setStatus("Signed out • Mic inactive") }
        }

        findViewById<Button>(R.id.btnGenCode).setOnClickListener {
            postJson("/v1/link/code", JSONObject(), vault.token()) { ok, obj ->
                runOnUiThread {
                    findViewById<TextView>(R.id.tvCodeOut).text =
                        if (ok && obj != null) "In-game run: /gmate link ${obj.optString("code")} (3m)"
                        else (obj?.optString("error") ?: "Could not generate code")
                }
            }
        }

        findViewById<Button>(R.id.btnUnlink).setOnClickListener {
            postJson("/v1/link/unlink", JSONObject(), vault.token()) { ok, _ ->
                runOnUiThread { if (ok) { findViewById<TextView>(R.id.tvLinkStatus).text = "Minecraft Link: Not linked"; findViewById<TextView>(R.id.tvCodeOut).text = "" } }
            }
        }

        cbUnmute.setOnCheckedChangeListener { _, checked ->
            postJson("/v1/link/pref", JSONObject().put("allowRemoteUnmute", checked), vault.token()) { _, _ -> }
        }

        findViewById<Button>(R.id.btnStartSquad).setOnClickListener {
            val code = etSquad.text.toString().trim().uppercase()
            if (code.isBlank()) { setStatus("Enter a Squad code first"); return@setOnClickListener }
            ensurePerms { startVc("TEAM", code) }
        }

        findViewById<Button>(R.id.btnStartGuild).setOnClickListener {
            if (guildId.isBlank()) { setStatus("You are not in a Guild"); return@setOnClickListener }
            ensurePerms { startVc("GUILD", etSquad.text.toString().trim().uppercase()) }
        }

        findViewById<Button>(R.id.btnStopVc).setOnClickListener { sendCmd(VcForegroundService.ACTION_STOP) }
        findViewById<Button>(R.id.btnMute).setOnClickListener { sendCmd(VcForegroundService.ACTION_TOGGLE_MUTE) }
        findViewById<Button>(R.id.btnDeafen).setOnClickListener { sendCmd(VcForegroundService.ACTION_TOGGLE_DEAF) }

        VcForegroundService.onStateChanged = { runOnUiThread { refreshUi() } }
        VcForegroundService.onSessionRevoked = { runOnUiThread { refreshUi(); setStatus("Signed out: logged in on another device") } }

        refreshUi()
        if (vault.token().isNotBlank()) loadAccountMeta()
    }

    private fun startVc(ch: String, squad: String) {
        val i = Intent(this, VcForegroundService::class.java).apply {
            action = VcForegroundService.ACTION_START
            putExtra(VcForegroundService.EXTRA_CHANNEL, ch)
            putExtra(VcForegroundService.EXTRA_SQUAD, squad)
            putExtra(VcForegroundService.EXTRA_GUILD, guildId)
        }
        ContextCompat.startForegroundService(this, i)
    }

    private fun ensurePerms(onReady: () -> Unit) {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) onReady() else { pendingStart = onReady; permLauncher.launch(missing.toTypedArray()) }
    }

    private fun sendCmd(act: String) = startService(Intent(this, VcForegroundService::class.java).setAction(act))

    private fun refreshUi() {
        val logged = vault.token().isNotBlank()
        findViewById<LinearLayout>(R.id.boxLogin).visibility = if (logged) View.GONE else View.VISIBLE
        findViewById<LinearLayout>(R.id.boxActive).visibility = if (logged) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvAccount).text = "Account: ${vault.username()}"
        findViewById<Button>(R.id.btnMute).text = if (VcForegroundService.isMuted) "Mic: MUTED" else "Mic: ON"
        findViewById<Button>(R.id.btnDeafen).text = if (VcForegroundService.isDeafened) "Audio: OFF" else "Audio: ON"
        if (logged) {
            setStatus(
                if (VcForegroundService.isRunning) "VC Active (${VcForegroundService.currentChannel}) • Foreground Notification ON"
                else "Signed in • Mic OFF (Tap Squad VC or Guild VC to start)"
            )
        }
    }

    private fun setStatus(m: String) { findViewById<TextView>(R.id.tvStatus).text = m }

    private fun loadAccountMeta() {
        val tok = vault.token()
        if (tok.isBlank()) return
        getJson("/v1/link/status", tok) { ok, obj ->
            if (ok && obj != null) {
                val linked = obj.optJSONObject("linked")
                runOnUiThread {
                    val tv = findViewById<TextView>(R.id.tvLinkStatus)
                    val cb = findViewById<CheckBox>(R.id.cbAllowUnmute)
                    if (linked != null) {
                        tv.text = "Linked MC: ${linked.optString("mc_username")} (${linked.optString("platform")})"
                        cb.isChecked = linked.optInt("allow_remote_unmute", 1) == 1
                    } else tv.text = "Minecraft Link: Not linked"
                }
            }
        }
        getJson("/v1/guilds/me", tok) { ok, obj -> if (ok && obj != null) guildId = obj.optJSONObject("guild")?.optString("id") ?: "" }
    }

    private fun getJson(path: String, bearer: String, cb: (Boolean, JSONObject?) -> Unit) {
        Thread {
            runCatching {
                val b = Request.Builder().url("https://api.elitecraft.live$path")
                if (bearer.isNotBlank()) b.addHeader("Authorization", "Bearer $bearer")
                http.newCall(b.build()).execute().use { r ->
                    val obj = runCatching { JSONObject(r.body?.string() ?: "") }.getOrNull()
                    cb(r.isSuccessful && (obj?.optBoolean("ok", true) != false), obj)
                }
            }.onFailure { cb(false, null) }
        }.start()
    }

    private fun postJson(path: String, json: JSONObject, bearer: String, cb: (Boolean, JSONObject?) -> Unit) {
        Thread {
            runCatching {
                val b = Request.Builder().url("https://api.elitecraft.live$path").post(json.toString().toRequestBody(jsonType))
                if (bearer.isNotBlank()) b.addHeader("Authorization", "Bearer $bearer")
                http.newCall(b.build()).execute().use { r ->
                    val obj = runCatching { JSONObject(r.body?.string() ?: "") }.getOrNull()
                    cb(r.isSuccessful && (obj?.optBoolean("ok", true) != false), obj)
                }
            }.onFailure { cb(false, null) }
        }.start()
    }
}
