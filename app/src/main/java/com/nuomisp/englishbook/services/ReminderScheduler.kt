package com.nuomisp.englishbook.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nuomisp.englishbook.data.LearningRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.nuomisp.englishbook.data.ChatMessage
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** WorkManager survives restarts, but Android/Meizu power management can defer delivery. */
class ReminderScheduler(private val context: Context) {
    fun schedule(enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        val settings = runCatching { SecureSettingsStore(context).load() }.getOrNull()
        if (!enabled || settings == null || !settings.remindersEnabled) {
            manager.cancelAllWorkByTag(TAG)
            if (settings != null) syncDisabledServer(settings)
            return
        }
        ReminderNotifications.createChannel(context)
        if (settings.serverModeEnabled) {
            manager.cancelAllWorkByTag(LOCAL_TAG)
            if (settings.serverUrl.isNotBlank() && settings.serverToken.isNotBlank()) {
                val request = PeriodicWorkRequestBuilder<ServerSyncWorker>(15, TimeUnit.MINUTES).addTag(TAG).addTag(SERVER_TAG).build()
                manager.enqueueUniquePeriodicWork("englishbook_server_sync", ExistingPeriodicWorkPolicy.KEEP, request)
                manager.enqueueUniqueWork("englishbook_server_sync_now", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ServerSyncWorker>().addTag(TAG).addTag(SERVER_TAG).build())
            }
            return
        }
        manager.cancelAllWorkByTag(SERVER_TAG)
        syncDisabledServer(settings)
        manager.enqueueUniquePeriodicWork("englishbook_reminder_planner", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderPlannerWorker>(12, TimeUnit.HOURS).addTag(TAG).addTag(LOCAL_TAG).build())
        planUpcoming()
    }

    private fun syncDisabledServer(settings: ApiSettings) {
        if (settings.serverUrl.isBlank() || settings.serverToken.isBlank()) return
        WorkManager.getInstance(context).enqueueUniqueWork("englishbook_server_preferences", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ServerSyncWorker>().setInputData(workDataOf("syncOnly" to true)).build())
    }

    internal fun planUpcoming() {
        val settings = runCatching { SecureSettingsStore(context).load() }.getOrNull() ?: return
        if (!settings.remindersEnabled || settings.serverModeEnabled) return
        val preferences = context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)
        val seed = if (preferences.contains("seed")) preferences.getInt("seed", 0) else Random.nextInt().also {
            preferences.edit().putInt("seed", it).apply()
        }
        val manager = WorkManager.getInstance(context)
        val now = ZonedDateTime.now()
        for (day in 0L..7L) {
            val date = now.toLocalDate().plusDays(day)
            ReminderPlan.forDate(date, seed).forEachIndexed { index, slot ->
                val target = date.atTime(slot.time).atZone(now.zone)
                if (!target.isAfter(now)) return@forEachIndexed
                val request = OneTimeWorkRequestBuilder<LocalReminderWorker>()
                    .setInputData(workDataOf("date" to date.toString(), "minute" to slot.minuteOfDay, "recap" to slot.recap))
                    .setInitialDelay(Duration.between(now, target).toMillis(), TimeUnit.MILLISECONDS)
                    .addTag(TAG).addTag(LOCAL_TAG).build()
                manager.enqueueUniqueWork("englishbook_prompt_${date}_$index", ExistingWorkPolicy.KEEP, request)
            }
        }
    }

    companion object {
        internal const val TAG = "englishbook_reminders"
        internal const val LOCAL_TAG = "englishbook_local_reminders"
        internal const val SERVER_TAG = "englishbook_server_reminders"
    }
}

class ReminderPlannerWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        ReminderScheduler(applicationContext).planUpcoming()
        return Result.success()
    }
}

class LocalReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val settingsStore = SecureSettingsStore(context)
        val settings = runCatching { settingsStore.load() }.getOrNull() ?: return Result.success()
        if (!settings.remindersEnabled || settings.serverModeEnabled || !ReminderNotifications.allowed(context)) return Result.success()
        val date = runCatching { LocalDate.parse(inputData.getString("date")) }.getOrNull() ?: return Result.success()
        val slot = ReminderSlot(inputData.getInt("minute", 0), inputData.getBoolean("recap", false))
        val now = ZonedDateTime.now()
        if (!ReminderPlan.canDeliver(slot, date, now.toLocalDate(), now.toLocalTime())) return Result.success()
        val repository = LearningRepository.get(context)
        val stats = repository.dailyStats()
        if (!slot.recap && stats.studyMinutes >= 120) return Result.success()
        val preferences = context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)
        val lastRandom = preferences.getLong("last_random", 0L)
        if (!slot.recap && System.currentTimeMillis() - lastRandom < TimeUnit.MINUTES.toMillis(90)) return Result.success()
        val weak = repository.weakWords(3).map { it.word }
        val fallback = if (slot.recap) {
            if (stats.studyMinutes >= 120) "今天学了 ${stats.studyMinutes} 分钟，目标达成。还不错嘛……来看看今天的收获。"
            else "今晚来收个尾：新词 ${stats.newWords} 个，复习 ${stats.reviewedWords} 个。把今天卡住的地方理清，明天才不许再装陌生。"
        } else if (weak.isNotEmpty()) {
            "${weak.joinToString("、")} 还等着你呢。别急着点答案，先自己回忆一遍。"
        } else {
            "今天已经认真学了 ${stats.studyMinutes} 分钟。别光看着我，来做一小组单词练习。"
        }
        val kind = if (slot.recap) "21:30 当日回顾" else "随机学习提醒"
        val generated = withTimeoutOrNull(12_000) {
            try {
                OpenAiClient(settingsStore).chat(listOf(ChatMessage(0L, "user",
                    "请根据实际学习进度生成一条${kind}通知，25到70个中文字。保持傲娇但不羞辱的口吻。不使用Markdown，不声称完成数据中没有的任务，不要求用户回复私密信息。只给通知正文。", System.currentTimeMillis())),
                    repository.learningContext(), ModelRole.MEMORY).take(140)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { null }
        }
        // Re-check after network generation: do not deliver if user finished, disabled, or left the window.
        val refreshed = runCatching { settingsStore.load() }.getOrNull() ?: return Result.success()
        val deliveryNow = ZonedDateTime.now()
        if (!refreshed.remindersEnabled || refreshed.serverModeEnabled ||
            !ReminderPlan.canDeliver(slot, date, deliveryNow.toLocalDate(), deliveryNow.toLocalTime()) ||
            (!slot.recap && repository.dailyStats().studyMinutes >= 120)) return Result.success()
        val id = ("${date}_${slot.minuteOfDay}").hashCode()
        if (ReminderNotifications.show(context, id, if (slot.recap) "凛 · 今晚的学习回顾" else "凛来找你了", generated ?: fallback)) {
            if (!slot.recap) preferences.edit().putLong("last_random", System.currentTimeMillis()).apply()
        }
        return Result.success()
    }
}

internal object ReminderNotifications {
    private const val CHANNEL = "englishbook_learning"
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "学习伙伴提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "09:00–22:00 的随机学习提醒与 21:30 回顾；省电策略可能延迟通知。"
            })
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun show(context: Context, id: Int, title: String, body: String): Boolean {
        if (!allowed(context)) return false
        createChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        launch.putExtra("fromReminder", true)
        val intent = PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title.take(80)).setContentText(body.take(300))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(300)))
            .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        return try { NotificationManagerCompat.from(context).notify(id, notification); true } catch (_: SecurityException) { false }
    }
}
