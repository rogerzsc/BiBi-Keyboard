/**
 * HomeRail 直达客户端（fork 新增）：
 * 识别结果不入输入框，直接送 HomeRail 平台语音会话，回复可选平台 TTS 回播。
 *
 * 认证：Cookie hrauth=<口令>（与浏览器同一道门禁）+ 同源 Origin 头（变更类请求要求）。
 * 证书：平台当前为自签证书，OkHttp 信任全部（个人服务器；换正式证书后可收紧）。
 *
 * 归属模块：homerail
 */
package com.brycewg.asrkb.homerail

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object HomeRailDirect {
    private const val TAG = "HomeRailDirect"

    private val client: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            // manager 回合可能包含 LLM 思考与派发，放宽读超时
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(prefs: Prefs): Boolean =
        prefs.homerailDirectEnabled &&
            prefs.homerailBaseUrl.isNotBlank() &&
            prefs.homerailPassword.isNotBlank()

    private fun normalizeBase(url: String): String = url.trim().trimEnd('/')

    private fun Request.Builder.addHomeRailHeaders(prefs: Prefs, base: String): Request.Builder {
        addHeader("Cookie", "hrauth=${prefs.homerailPassword}")
        addHeader("Origin", base)
        return this
    }

    private suspend fun httpCall(request: Request): Response = withContext(Dispatchers.IO) {
        client.newCall(request).execute()
    }

    /** 取（或新建）平台语音会话 id，缓存进 prefs。 */
    suspend fun ensureSessionId(prefs: Prefs): String? {
        prefs.homerailSessionId.takeIf { it.isNotBlank() }?.let { return it }
        val base = normalizeBase(prefs.homerailBaseUrl)
        val request = Request.Builder()
            .url("$base/ui/api/voice-agent/sessions")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .addHomeRailHeaders(prefs, base)
            .build()
        httpCall(request).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "create session failed ${resp.code}: ${text.take(200)}")
                return null
            }
            val sid = JSONObject(text).optJSONObject("data")?.optString("session_id").orEmpty()
            if (sid.isBlank()) {
                Log.w(TAG, "create session: no session_id in response")
                return null
            }
            prefs.homerailSessionId = sid
            return sid
        }
    }

    /** 发送一轮转写，返回 spoken_text（可能为空串）；失败返回 null。 */
    suspend fun sendTurn(prefs: Prefs, text: String): String? {
        val sid = ensureSessionId(prefs) ?: return null
        val base = normalizeBase(prefs.homerailBaseUrl)
        val request = Request.Builder()
            .url("$base/ui/api/voice-agent/sessions/$sid/turn")
            .post(JSONObject().put("text", text).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHomeRailHeaders(prefs, base)
            .build()
        return try {
            httpCall(request).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "turn failed ${resp.code}: ${body.take(200)}")
                    return null
                }
                JSONObject(body).optJSONObject("data")?.optString("spoken_text") ?: ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "turn error", t)
            null
        }
    }

    /** 平台 TTS（MiniMax）：返回音频字节；失败/JSON 错误体返回 null。 */
    suspend fun fetchTts(prefs: Prefs, text: String): ByteArray? {
        val base = normalizeBase(prefs.homerailBaseUrl)
        val request = Request.Builder()
            .url("$base/ui/api/voice/speech")
            .post(JSONObject().put("text", text).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHomeRailHeaders(prefs, base)
            .build()
        return try {
            httpCall(request).use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "tts failed ${resp.code}")
                    return null
                }
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.size > 4 && bytes[0] == '{'.code.toByte()) {
                    Log.w(TAG, "tts returned json: " + String(bytes, 0, minOf(200, bytes.size)))
                    return null
                }
                bytes
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tts error", t)
            null
        }
    }

    /** 连通性测试：GET /ui/api/catalog（口令门禁会 302 到 /gate/）。 */
    suspend fun testConnection(prefs: Prefs): String {
        val base = normalizeBase(prefs.homerailBaseUrl)
        if (base.isBlank()) return "未配置服务器地址"
        val request = Request.Builder()
            .url("$base/ui/api/catalog")
            .get()
            .addHomeRailHeaders(prefs, base)
            .build()
        return try {
            httpCall(request).use { resp ->
                when {
                    resp.isSuccessful -> "连接成功（HTTP ${resp.code}）"
                    resp.code == 302 || resp.code == 401 || resp.code == 403 -> "口令不对（HTTP ${resp.code}）"
                    else -> "HTTP ${resp.code}"
                }
            }
        } catch (t: Throwable) {
            "连接失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ==================== TTS 播放 ====================

    private var mediaPlayer: MediaPlayer? = null

    fun stopTts() {
        try {
            mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
        } catch (_: Throwable) {
        }
        mediaPlayer = null
    }

    fun playTts(context: Context, audio: ByteArray, onDone: () -> Unit) {
        stopTts()
        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .build()
        try {
            am.requestAudioFocus(focusRequest)
        } catch (_: Throwable) {
        }
        val tmp = File(appContext.cacheDir, "homerail_tts.bin")
        try {
            tmp.writeBytes(audio)
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(tmp.absolutePath)
            player.setOnCompletionListener {
                it.release()
                if (mediaPlayer === it) mediaPlayer = null
                try {
                    am.abandonAudioFocusRequest(focusRequest)
                } catch (_: Throwable) {
                }
                onDone()
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "tts player error $what/$extra")
                mp.release()
                if (mediaPlayer === mp) mediaPlayer = null
                try {
                    am.abandonAudioFocusRequest(focusRequest)
                } catch (_: Throwable) {
                }
                onDone()
                true
            }
            player.prepare()
            player.start()
        } catch (t: Throwable) {
            Log.w(TAG, "tts play failed", t)
            stopTts()
            try {
                am.abandonAudioFocusRequest(focusRequest)
            } catch (_: Throwable) {
            }
            onDone()
        }
    }

    // ==================== 编排 ====================

    /**
     * 识别结果直达 HomeRail：POST 转写 → spoken_text → 可选 TTS 回播 → 回调 onRoundFinished。
     * 在 [scope]（调用方主协程域）上调度；HTTP 走 IO。onRoundFinished 参数为回复文本（null=失败）。
     */
    fun handleFinal(
        context: Context,
        prefs: Prefs,
        scope: CoroutineScope,
        text: String,
        onStatus: (String) -> Unit,
        onRoundFinished: (String?) -> Unit
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val reply = sendTurn(prefs, text)
            if (reply == null) {
                onStatus(appContext.getString(R.string.homerail_status_send_failed))
                onRoundFinished(null)
                return@launch
            }
            if (prefs.homerailTtsEnabled && reply.isNotBlank()) {
                val audio = fetchTts(prefs, reply)
                if (audio != null && audio.isNotEmpty()) {
                    playTts(appContext, audio) { scope.launch { onRoundFinished(reply) } }
                    return@launch
                }
            }
            if (reply.isNotBlank()) {
                onStatus(reply)
            } else {
                onStatus(appContext.getString(R.string.homerail_status_no_reply))
            }
            onRoundFinished(reply)
        }
    }
}
