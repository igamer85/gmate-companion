package live.elitecraft.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class VcForegroundService : Service() {
    companion object {
        const val ACTION_START = "live.elitecraft.companion.START"
        const val ACTION_STOP = "live.elitecraft.companion.STOP"
        const val ACTION_TOGGLE_MUTE = "live.elitecraft.companion.TOGGLE_MUTE"
        const val ACTION_TOGGLE_DEAF = "live.elitecraft.companion.TOGGLE_DEAF"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_SQUAD = "squad"
        const val EXTRA_GUILD = "guild"
        private const val NOTIF_ID = 4041
        private const val SAMPLE_RATE = 16000

        @Volatile var isRunning = false
        @Volatile var currentChannel = "OFF"
        @Volatile var isMuted = false
        @Volatile var isDeafened = false
        @Volatile var onStateChanged: (() -> Unit)? = null
        @Volatile var onSessionRevoked: (() -> Unit)? = null
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val active = AtomicBoolean(false)
    private var ctrlWs: WebSocket? = null
    private var voiceWs: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var recThread: Thread? = null
    private var token = ""
    private var squadCode = ""
    private var guildId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopAll("OFF"); START_NOT_STICKY }
            ACTION_TOGGLE_MUTE -> {
                if (isRunning) { isMuted = !isMuted; notifyNow(); onStateChanged?.invoke() }
                START_STICKY
            }
            ACTION_TOGGLE_DEAF -> {
                if (isRunning) { isDeafened = !isDeafened; notifyNow(); onStateChanged?.invoke() }
                START_STICKY
            }
            ACTION_START -> {
                val ch = intent.getStringExtra(EXTRA_CHANNEL) ?: "TEAM"
                squadCode = intent.getStringExtra(EXTRA_SQUAD) ?: ""
                guildId = intent.getStringExtra(EXTRA_GUILD) ?: ""
                token = SecureVault(this).token()
                if (token.isBlank()) { stopSelf(); return START_NOT_STICKY }
                startFg(buildNotif(ch))
                isRunning = true; isMuted = false; isDeafened = false; active.set(true)
                initAudioTrack(); connectControlWs(); switchChannel(ch); onStateChanged?.invoke()
                START_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun pi(id: Int, act: String): PendingIntent = PendingIntent.getService(
        this, id, Intent(this, VcForegroundService::class.java).setAction(act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotif(ch: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val label = if (ch == "GUILD") "Guild VC" else "Squad ($squadCode)"
        return NotificationCompat.Builder(this, getString(R.string.vc_channel_id))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("gMate VC • $label")
            .setContentText("Mic: ${if (isMuted) "MUTED" else "LIVE"} • Audio: ${if (isDeafened) "OFF" else "ON"}")
            .setContentIntent(openPi).setOngoing(true)
            .addAction(0, if (isMuted) "Unmute" else "Mute", pi(2, ACTION_TOGGLE_MUTE))
            .addAction(0, if (isDeafened) "Undeafen" else "Deafen", pi(3, ACTION_TOGGLE_DEAF))
            .addAction(0, "Stop", pi(4, ACTION_STOP)).build()
    }

    private fun notifyNow() {
        if (isRunning) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif(currentChannel))
    }

    private fun connectControlWs() {
        ctrlWs?.close(1000, null)
        val enc = URLEncoder.encode(token, "UTF-8")
        ctrlWs = client.newWebSocket(Request.Builder().url("wss://api.elitecraft.live/v1/ws/world?t=$enc").build(), object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (obj.optString("type")) {
                    "SESSION_REPLACED" -> { SecureVault(this@VcForegroundService).clear(); stopAll("OFF"); onSessionRevoked?.invoke() }
                    "REMOTE_VC_CMD" -> handleRemoteCmd(obj.optString("action"))
                }
            }
        })
    }

    private fun handleRemoteCmd(a: String) {
        if (!isRunning) return
        when (a) {
            "MUTE" -> { isMuted = true; notifyNow(); onStateChanged?.invoke() }
            "UNMUTE" -> { isMuted = false; notifyNow(); onStateChanged?.invoke() }
            "DEAFEN" -> { isDeafened = true; notifyNow(); onStateChanged?.invoke() }
            "UNDEAFEN" -> { isDeafened = false; notifyNow(); onStateChanged?.invoke() }
            "TEAM" -> if (squadCode.isNotBlank()) switchChannel("TEAM")
            "GUILD" -> if (guildId.isNotBlank()) switchChannel("GUILD")
            "OFF" -> stopAll("OFF")
        }
    }

    private fun switchChannel(ch: String) {
        currentChannel = ch; voiceWs?.close(1000, null); voiceWs = null
        val encT = URLEncoder.encode(token, "UTF-8")
        val url = if (ch == "GUILD") "wss://api.elitecraft.live/v1/ws/guild/${URLEncoder.encode(guildId, "UTF-8")}?t=$encT"
        else "wss://api.elitecraft.live/v1/ws/squad/${URLEncoder.encode(squadCode, "UTF-8")}?t=$encT"
        voiceWs = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (ch == "GUILD") ws.send(JSONObject().put("type", "GUILD_VC").put("inVc", true).toString())
                startMicLoop()
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (isDeafened) return
                val b64 = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("type") == "VC_AUDIO" }?.optString("pcm", "") ?: ""
                if (b64.isNotEmpty()) { val pcm = Base64.decode(b64, Base64.DEFAULT); audioTrack?.write(pcm, 0, pcm.size) }
            }
        })
        notifyNow(); onStateChanged?.invoke()
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE / 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack?.play()
    }

    private fun startMicLoop() {
        if (recThread?.isAlive == true || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE / 5))
        rec.startRecording()
        recThread = Thread {
            val buf = ByteArray(1600)
            while (active.get()) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0 && !isMuted && currentChannel != "OFF") {
                    var sum = 0.0; var i = 0
                    while (i + 1 < read) { val s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toDouble(); sum += s * s; i += 2 }
                    if (sqrt(sum / (read / 2)) / 32768.0 > 0.015) {
                        voiceWs?.send(JSONObject().put("type", "VC_AUDIO").put("pcm", Base64.encodeToString(buf.copyOf(read), Base64.NO_WRAP)).toString())
                    }
                }
            }
            runCatching { rec.stop(); rec.release() }
        }.apply { isDaemon = true; start() }
    }

    private fun stopAll(ch: String) {
        active.set(false); currentChannel = ch; isRunning = false; recThread = null
        runCatching { voiceWs?.close(1000, null) }; voiceWs = null
        runCatching { ctrlWs?.close(1000, null) }; ctrlWs = null
        runCatching { audioTrack?.stop(); audioTrack?.release() }; audioTrack = null
        onStateChanged?.invoke(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { stopAll("OFF"); super.onDestroy() }
}
