package com.nuomisp.englishbook.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nuomisp.englishbook.data.LearningRepository
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Explicit opt-in: uploads only the learning snapshot, never the phone's AI/TTS keys or chat history. */
class ServerSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val settingsStore = SecureSettingsStore(context)
        val settings = runCatching { settingsStore.load() }.getOrNull() ?: return Result.success()
        val syncOnly = inputData.getBoolean("syncOnly", false)
        val enabled = settings.remindersEnabled && settings.serverModeEnabled && !syncOnly
        if ((!enabled && !syncOnly) || settings.serverUrl.isBlank() || settings.serverToken.isBlank()) return Result.success()
        val repository = LearningRepository.get(context)
        val stats = repository.dailyStats()
        val snapshot = JSONObject().put("localDate", stats.date).put("timezone", ZoneId.systemDefault().id)
            .put("studiedMinutes", stats.studyMinutes).put("targetMinutes", 120)
            .put("newWords", stats.newWords).put("reviewedWords", stats.reviewedWords)
            .put("dueWords", repository.dueCount())
            .put("weakWords", JSONArray(repository.weakWords(8).map { it.word }.filter { it.length <= 40 && it.matches(Regex("[A-Za-z '-]+")) }))
            .put("remindersEnabled", enabled)
        try {
            SafeHttp.bytes(serverRequest(settings, "v1/snapshot").put(snapshot.toString().toRequestBody(SafeHttp.jsonType)).build())
            if (syncOnly || !enabled) return Result.success()
            val body = SafeHttp.bytes(serverRequest(settings, "v1/inbox").get().build())
            val reminders = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("reminders") ?: return Result.success()
            val preferences = context.getSharedPreferences("server_reminder_delivery", Context.MODE_PRIVATE)
            val delivered = preferences.getStringSet("delivered", emptySet()).orEmpty().toMutableSet()
            for (index in 0 until minOf(reminders.length(), 20)) {
                val reminder = reminders.optJSONObject(index) ?: continue
                val id = reminder.optString("id").take(200)
                if (id.isBlank()) continue
                if (id in delivered) { acknowledge(settings, id); continue }
                val scheduled = runCatching { Instant.parse(reminder.optString("scheduledAt")) }.getOrNull() ?: continue
                val expires = runCatching { Instant.parse(reminder.optString("expiresAt")) }.getOrNull() ?: continue
                val now = ZonedDateTime.now()
                if (now.toInstant().isBefore(scheduled)) continue
                if (!expires.isAfter(now.toInstant())) { acknowledge(settings, id); continue }
                val recap = reminder.optString("kind") == "recap"
                val minute = now.hour * 60 + now.minute
                if (minute !in ReminderPlan.START_MINUTE until ReminderPlan.END_MINUTE) continue
                if (recap && minute < ReminderPlan.RECAP_MINUTE) continue
                if (!recap && minute >= ReminderPlan.RECAP_MINUTE - 30) { acknowledge(settings, id); continue }
                val current = settingsStore.load()
                if (!current.remindersEnabled || !current.serverModeEnabled) return Result.success()
                if (!recap && repository.dailyStats().studyMinutes >= 120) { acknowledge(settings, id); continue }
                val lastRandom = preferences.getLong("last_random", 0L)
                if (!recap && System.currentTimeMillis() - lastRandom < 90L * 60 * 1000) { acknowledge(settings, id); continue }
                val title = reminder.optString("title", "凛来找你了").take(80)
                val message = reminder.optString("body").take(300)
                if (message.isBlank()) { acknowledge(settings, id); continue }
                if (ReminderNotifications.show(context, id.hashCode(), title, message)) {
                    delivered.add(id)
                    // Persist before ACK: a dropped connection must not display the same message twice.
                    val edit = preferences.edit().putStringSet("delivered", delivered.takeLastSet(200))
                    if (!recap) edit.putLong("last_random", System.currentTimeMillis())
                    edit.commit()
                    acknowledge(settings, id)
                }
            }
            return Result.success()
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { return if (runAttemptCount < 2) Result.retry() else Result.success() }
    }

    private suspend fun acknowledge(settings: ApiSettings, id: String) {
        val url = serverEndpoint(settings.serverUrl, "v1/reminders").newBuilder().addPathSegment(id).addPathSegment("ack").build()
        SafeHttp.bytes(Request.Builder().url(url).header("Authorization", SafeHttp.bearer(settings.serverToken))
            .post("".toRequestBody(SafeHttp.jsonType)).build())
    }
}

private fun Set<String>.takeLastSet(limit: Int): Set<String> = toList().takeLast(limit).toSet()

internal fun serverEndpoint(baseUrl: String, path: String): HttpUrl {
    val base = ApiEndpoints.validateBaseUrl(baseUrl)
    return base.newBuilder().encodedPath("${base.encodedPath.trimEnd('/')}/$path").build()
}

private fun serverRequest(settings: ApiSettings, path: String): Request.Builder = Request.Builder()
    .url(serverEndpoint(settings.serverUrl, path)).header("Authorization", SafeHttp.bearer(settings.serverToken))

class ReminderServerClient(private val settingsStore: SecureSettingsStore) {
    suspend fun testConnection(): String {
        val settings = settingsStore.load()
        if (settings.serverUrl.isBlank() || settings.serverToken.isBlank()) throw ApiException("请填写提醒服务器地址和连接令牌。")
        // /health alone cannot verify token authorization, so also check the authenticated inbox.
        SafeHttp.bytes(Request.Builder().url(serverEndpoint(settings.serverUrl, "health")).get().build())
        SafeHttp.bytes(serverRequest(settings, "v1/inbox").get().build())
        return "提醒服务器连接成功。后台同步会受手机省电策略影响。"
    }
}
