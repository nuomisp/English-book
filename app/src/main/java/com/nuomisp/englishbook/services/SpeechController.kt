package com.nuomisp.englishbook.services

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Real playback through Android TTS or an OpenAI-compatible speech API; no MOSS implementation is implied. */
class SpeechController(context: Context, private val settingsStore: SecureSettingsStore) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private var engine: TextToSpeech? = null
    private var engineReady: CompletableDeferred<Int>? = null
    private var player: MediaPlayer? = null
    @Volatile private var activeJob: Job? = null
    @Volatile private var closed = false
    private var systemCompletion: CompletableDeferred<Unit>? = null

    /** Cancels earlier playback, then completes when this utterance finishes (or throws a safe error). */
    suspend fun speak(text: String, onStarted:()->Unit = {}): Unit = coroutineScope {
        if (closed) throw ApiException("语音播放器已经关闭。")
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@coroutineScope
        if (cleanText.length > 3000) throw ApiException("请分段朗读，每段不超过 3000 个字符。")
        stop()
        val job = currentCoroutineContext().job
        activeJob = job
        try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val settings = settingsStore.load()
                when (settings.ttsMode) {
                    TtsMode.SYSTEM -> speakSystem(cleanText,onStarted)
                    TtsMode.OPENAI_API -> playFile(loadSpeech(cleanText, settings),onStarted)
                }
            }
        } finally {
            if (activeJob === job) activeJob = null
        }
    }

    fun stop() {
        activeJob?.cancel(CancellationException("播放已停止"))
        activeJob = null
        onMain {
            engine?.stop()
            systemCompletion?.cancel()
            systemCompletion = null
            player?.let { media -> runCatching { media.stop() } }
        }
    }

    private suspend fun speakSystem(text: String,onStarted:()->Unit) = withContext(Dispatchers.Main.immediate) {
        val ready = engineReady ?: CompletableDeferred<Int>().also { signal ->
            engineReady = signal
            engine = TextToSpeech(appContext) { status -> signal.complete(status) }
        }
        val status = withTimeout(15_000) { ready.await() }
        if (status != TextToSpeech.SUCCESS) {
            engine?.shutdown(); engine = null; engineReady = null
            throw ApiException("手机没有可用的系统语音引擎。请安装英文语音包，或在设置中切换语音 API。")
        }
        val tts = engine ?: throw ApiException("系统语音引擎未初始化。")
        val language = tts.setLanguage(Locale.US)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED)
            throw ApiException("系统语音引擎缺少英文语音包，请在手机语音设置中下载，或使用语音 API。")
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        systemCompletion = completion
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {if(id==utteranceId)onStarted()}
            override fun onDone(id: String?) { if (id == utteranceId) completion.complete(Unit) }
            @Deprecated("Legacy callback required by platform")
            override fun onError(id: String?) {
                if (id == utteranceId) completion.completeExceptionally(ApiException("系统语音播放失败，请检查英文语音包。"))
            }
            override fun onError(id: String?, errorCode: Int) = onError(id)
        })
        tts.setSpeechRate(0.9f)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR)
            throw ApiException("无法开始系统语音播放，请检查语音引擎。")
        try { withTimeout(240_000) { completion.await() } }
        finally { tts.stop(); if (systemCompletion === completion) systemCompletion = null }
    }

    private suspend fun loadSpeech(text: String, settings: ApiSettings): File = withContext(Dispatchers.IO) {
        if (settings.ttsBaseUrl.isBlank() || settings.ttsApiKey.isBlank() || settings.ttsModel.isBlank())
            throw ApiException("请在设置中填写语音 API 的地址、密钥和模型。")
        val url = ApiEndpoints.endpoint(settings.ttsBaseUrl, "audio/speech")
        val identifier = listOf(url.toString(), settings.ttsModel, settings.ttsVoice, text).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(identifier.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cache = File(appContext.cacheDir, "speech").apply { mkdirs() }
        val file = File(cache, "$hash.mp3")
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        val body = JSONObject().put("model", settings.ttsModel.trim()).put("voice", settings.ttsVoice.trim().ifEmpty { "alloy" })
            .put("input", text).put("response_format", "mp3")
        val request = Request.Builder().url(url).header("Authorization", SafeHttp.bearer(settings.ttsApiKey))
            .post(body.toString().toRequestBody(SafeHttp.jsonType)).build()
        val audio = SafeHttp.bytes(request, maxBytes = 8 * 1024 * 1024)
        if (audio.size < 128) throw ApiException("语音接口返回了无效音频。")
        currentCoroutineContext().ensureActive()
        val temporary = File(cache, "$hash.tmp")
        try {
            temporary.writeBytes(audio)
            if (!temporary.renameTo(file)) throw ApiException("无法保存语音缓存，请检查可用存储空间。")
        } finally { temporary.delete() }
        // This directory is private cache: keep at most 64 MiB and trim oldest entries first.
        val files = cache.listFiles()?.filter { it.isFile && it.extension == "mp3" }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        for (old in files) {
            if (total <= 64L * 1024 * 1024) break
            if (old != file) { val size = old.length(); if (old.delete()) total -= size }
        }
        file
    }

    private suspend fun playFile(file: File,onStarted:()->Unit) = withContext(Dispatchers.Main.immediate) {
        val completion = CompletableDeferred<Unit>()
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            media.setOnPreparedListener { onStarted();it.start() }
            media.setOnCompletionListener { completion.complete(Unit) }
            media.setOnErrorListener { _, _, _ ->
                file.delete()
                completion.completeExceptionally(ApiException("音频播放失败，请确认语音接口支持 MP3 输出。")); true
            }
            media.setDataSource(file.absolutePath)
            media.prepareAsync()
            withTimeout(240_000) { completion.await() }
        } catch (error: CancellationException) { throw error
        } catch (error: ApiException) { throw error
        } catch (_: Exception) { file.delete(); throw ApiException("无法播放语音，请检查接口和音频格式。")
        } finally {
            media.release()
            if (player === media) player = null
        }
    }

    override fun close() {
        closed = true
        stop()
        onMain { engine?.shutdown(); engine = null; engineReady = null }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }
}
