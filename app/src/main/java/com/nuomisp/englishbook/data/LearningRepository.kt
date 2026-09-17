package com.nuomisp.englishbook.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Local source of truth. Network credentials never enter this database or its backup. */
class LearningRepository(context: Context) {
    private val appContext = context.applicationContext
    private val helper = LearningDatabase(appContext)
    private val catalog by lazy { StarterContent.words(appContext) }
    private val catalogById by lazy { catalog.associateBy { it.id } }
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun words(): List<Word> = catalog
    fun articles(): List<ReadingArticle> = StarterContent.articles
    fun dictations(): List<DictationSentence> = catalog.take(40).map {
        DictationSentence("sentence-${it.id}", it.example, it.exampleZh, it.level)
    }

    fun progress(wordId: String): WordProgress? = progress(helper.readableDatabase, wordId)

    /** Due reviews first, then up to today's remaining 20 new words. */
    fun studyQueue(limit: Int = 20): List<Word> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()
        val progress = allProgress().associateBy { it.wordId }
        val due = progress.values.filter { it.nextReviewAt <= now }
            .sortedBy { it.nextReviewAt }.mapNotNull { catalogById[it.wordId] }
        val remainingNew = (20 - dailyStats().newWords).coerceAtLeast(0)
        val newWords = catalog.filter { it.id !in progress }.take(remainingNew)
        return (due + newWords).take(limit.coerceAtMost(200))
    }

    fun dueCount(): Int = count(
        "SELECT COUNT(*) FROM word_progress WHERE next_review_at <= ?",
        arrayOf(System.currentTimeMillis().toString()),
    )

    fun learnedCount(): Int = count("SELECT COUNT(*) FROM word_progress")

    fun masteredCount(): Int = count(
        "SELECT COUNT(*) FROM word_progress WHERE correct_streak >= 3 AND interval_days >= 21",
    )

    fun weakWords(limit: Int = 8): List<Word> = allProgress()
        .filter { it.lapses > 0 || it.lastRating == ReviewRating.HARD }
        .sortedWith(compareByDescending<WordProgress> { it.lapses }.thenBy { it.correctStreak })
        .take(limit.coerceIn(0, 100)).mapNotNull { catalogById[it.wordId] }

    @Synchronized
    fun rateWord(wordId: String, rating: ReviewRating) {
        require(wordId in catalogById) { "Unknown word" }
        val now = System.currentTimeMillis()
        mutate { db ->
            val previous = progress(db, wordId)
            val next = ReviewScheduler.next(wordId, previous, rating, now)
            db.insertOrThrow("word_reviews", null, ContentValues().apply {
                put("word_id", wordId)
                put("reviewed_at", now)
                put("day", day(now))
                put("rating", rating.name)
                put("was_new", if (previous == null) 1 else 0)
            })
            db.insertWithOnConflict("word_progress", null, progressValues(next), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun recordSpelling(wordId: String, answer: String): Boolean {
        val word = requireNotNull(catalogById[wordId]) { "Unknown word" }
        val correct = AnswerChecker.matches(answer, word.word)
        rateWord(wordId, if (correct) ReviewRating.GOOD else ReviewRating.AGAIN)
        return correct
    }

    @Synchronized
    fun recordReading(articleId: String, answers: List<Int>): ReadingResult {
        val article = requireNotNull(articles().find { it.id == articleId }) { "Unknown reading" }
        require(answers.size == article.questions.size) { "Answer every question before submitting" }
        require(answers.indices.all { answers[it] in article.questions[it].options.indices })
        val correct = article.questions.indices.count { answers[it] == article.questions[it].answerIndex }
        mutate { db ->
            db.insertOrThrow("reading_attempts", null, ContentValues().apply {
                put("article_id", articleId)
                put("day", day())
                put("created_at", System.currentTimeMillis())
                put("correct", correct)
                put("total", article.questions.size)
                put("answers", JSONArray(answers).toString())
            })
        }
        return ReadingResult(articleId, correct, article.questions.size)
    }

    @Synchronized
    fun recordDictation(sentenceId: String, answer: String): DictationResult {
        val sentence = requireNotNull(dictations().find { it.id == sentenceId }) { "Unknown sentence" }
        require(answer.isNotBlank() && answer.length <= 2000) { "Enter an answer of 1 to 2000 characters" }
        val correct = AnswerChecker.matches(answer, sentence.text)
        mutate { db ->
            db.insertOrThrow("dictation_attempts", null, ContentValues().apply {
                put("sentence_id", sentenceId)
                put("day", day())
                put("created_at", System.currentTimeMillis())
                put("correct", if (correct) 1 else 0)
                put("answer", answer)
            })
        }
        return DictationResult(sentenceId, correct, sentence.text)
    }

    fun completedArticleIds(): Set<String> = helper.readableDatabase.rawQuery(
        "SELECT DISTINCT article_id FROM reading_attempts", null,
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun dailyStats(): DailyStats {
        val today = day()
        val args = arrayOf(today)
        return DailyStats(
            date = today,
            newWords = count("SELECT COUNT(DISTINCT word_id) FROM word_reviews WHERE day = ? AND was_new = 1", args),
            reviewedWords = count("SELECT COUNT(DISTINCT word_id) FROM word_reviews WHERE day = ? AND was_new = 0", args),
            studyMinutes = count("SELECT COALESCE(SUM(seconds), 0) FROM study_time WHERE day = ?", args) / 60,
            readingCompleted = count("SELECT COUNT(DISTINCT article_id) FROM reading_attempts WHERE day = ?", args),
            listeningAttempts = count("SELECT COUNT(*) FROM dictation_attempts WHERE day = ?", args),
            listeningCorrect = count("SELECT COUNT(*) FROM dictation_attempts WHERE day = ? AND correct = 1", args),
        )
    }

    /** Caller supplies foreground learning time, never time elapsed while the app was paused. */
    @Synchronized
    fun addStudySeconds(seconds: Int) {
        require(seconds in 0..3600)
        if (seconds == 0) return
        val today = day()
        mutate { db ->
            db.execSQL("INSERT OR IGNORE INTO study_time(day, seconds) VALUES (?, 0)", arrayOf(today))
            db.execSQL("UPDATE study_time SET seconds = seconds + ? WHERE day = ?", arrayOf(seconds, today))
        }
    }

    fun chatCount(): Int = count("SELECT COUNT(*) FROM chat_messages")

    fun chatMessages(limit: Int = 40): List<ChatMessage> = helper.readableDatabase.rawQuery(
        "SELECT id, role, content, created_at FROM chat_messages ORDER BY id DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 500).toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ChatMessage(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
        }.asReversed()
    }

    @Synchronized
    fun addChatMessage(role: String, content: String): ChatMessage {
        require(role == "user" || role == "assistant") { "Only user and assistant messages may be saved" }
        require(content.isNotBlank() && content.length <= 100_000)
        val now = System.currentTimeMillis()
        var id = 0L
        mutate { db ->
            id = db.insertOrThrow("chat_messages", null, ContentValues().apply {
                put("role", role)
                put("content", content)
                put("created_at", now)
            })
        }
        return ChatMessage(id, role, content, now)
    }

    fun memorySummary(): String = helper.readableDatabase.rawQuery(
        "SELECT value FROM metadata WHERE key = 'memory_summary'", null,
    ).use { if (it.moveToFirst()) it.getString(0) else "" }

    @Synchronized
    fun saveMemorySummary(summary: String) {
        require(summary.length <= 30_000)
        mutate { db ->
            db.insertWithOnConflict("metadata", null, ContentValues().apply {
                put("key", "memory_summary")
                put("value", summary)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    @Synchronized
    fun clearChats() = mutate { db ->
        db.delete("chat_messages", null, null)
        db.delete("metadata", "key = ?", arrayOf("memory_summary"))
    }

    fun learningContext(): String {
        val stats = dailyStats()
        return buildString {
            appendLine("学习档案：英语初中基础，目标大学英语四级；每天目标 120 分钟，前期优先词汇约 70 分钟。")
            appendLine("本地学习记录（${stats.date}）：学习 ${stats.studyMinutes} 分钟；新词 ${stats.newWords} 个，复习 ${stats.reviewedWords} 个；阅读完成 ${stats.readingCompleted} 篇；听写 ${stats.listeningAttempts} 次，正确 ${stats.listeningCorrect} 次。")
            appendLine("累计接触 ${learnedCount()} 个词，达到复习稳定标准 ${masteredCount()} 个；当前到期复习 ${dueCount()} 个。")
            val weak = weakWords()
            appendLine(if (weak.isEmpty()) "尚无足够易错词记录；不要编造学生的错误或进步。" else "需要关注的词：" + weak.joinToString("；") { "${it.word}（${it.meaning}）" })
            append("学习数据仅来自本应用；新词或复习计数不等于真正掌握，不能据此断言已达到四级水平。")
        }
    }

    /** Portable, explicitly non-secret backup; chats can contain personal text. */
    @Synchronized
    fun exportBackup(): String {
        val db = helper.readableDatabase
        db.beginTransaction()
        try {
            val output = JSONObject().put("format", "english-book-learning").put("version", 1)
                .put("exportedAt", System.currentTimeMillis())
            BACKUP_TABLES.forEach { table ->
                val rows = JSONArray()
                db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        cursor.columnNames.forEachIndexed { index, name ->
                            row.put(name, when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                else -> cursor.getString(index)
                            })
                        }
                        rows.put(row)
                    }
                }
                output.put(table, rows)
            }
            db.setTransactionSuccessful()
            return output.toString(2)
        } finally { db.endTransaction() }
    }

    /** Replace learning data only after the caller has requested a restore. Validate before writing. */
    @Synchronized
    fun importBackup(json: String) {
        require(json.length <= 16_000_000) { "Backup is too large" }
        val backup = JSONObject(json)
        require(backup.getString("format") == "english-book-learning" && backup.getInt("version") == 1) {
            "Unsupported backup format"
        }
        val db = helper.readableDatabase
        val prepared = BACKUP_TABLES.associateWith { table ->
            val columns = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(2))
                }
            }
            val rows = backup.getJSONArray(table)
            require(rows.length() <= 100_000) { "Too many backup rows" }
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                require(row.keys().asSequence().toSet() == columns.keys) { "Unexpected backup columns" }
                validateBackupRow(table, row)
                ContentValues().apply {
                    columns.forEach { (column, type) ->
                        val value = row.get(column)
                        when (type) {
                            "TEXT" -> {
                                require(value is String)
                                put(column, value)
                            }
                            "INTEGER" -> {
                                require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble())
                                put(column, value.toLong())
                            }
                            "REAL" -> {
                                require(value is Number && value.toDouble().isFinite())
                                put(column, value.toDouble())
                            }
                            else -> error("Unsupported backup column type")
                        }
                    }
                }
            }
        }
        // All deletes and inserts share one transaction; any failure preserves the old data.
        mutate { writable ->
            BACKUP_TABLES.forEach { writable.delete(it, null, null) }
            prepared.forEach { (table, rows) -> rows.forEach { writable.insertOrThrow(table, null, it) } }
        }
    }

    private fun validateBackupRow(table: String, row: JSONObject) {
        if (row.has("id")) require(row.getLong("id") > 0)
        if (row.has("day")) LocalDate.parse(row.getString("day"))
        if (row.has("created_at")) require(row.getLong("created_at") >= 0)
        when (table) {
            "word_progress" -> {
                require(row.getString("word_id") in catalogById)
                require(row.getDouble("interval_days") in 0.0..365.0)
                require(row.getLong("next_review_at") >= 0 && row.getLong("last_reviewed_at") >= 0)
                val attempts = row.getInt("attempts")
                require(attempts >= 1 && row.getInt("lapses") in 0..attempts && row.getInt("correct_streak") in 0..attempts)
                ReviewRating.valueOf(row.getString("last_rating"))
            }
            "word_reviews" -> {
                require(row.getString("word_id") in catalogById)
                require(row.getLong("reviewed_at") >= 0 && row.getInt("was_new") in 0..1)
                ReviewRating.valueOf(row.getString("rating"))
            }
            "study_time" -> require(row.getInt("seconds") in 0..86_400)
            "reading_attempts" -> {
                val article = requireNotNull(articles().find { it.id == row.getString("article_id") })
                val answers = JSONArray(row.getString("answers"))
                require(answers.length() == article.questions.size && row.getInt("total") == article.questions.size)
                val correct = article.questions.indices.count { index ->
                    val answer = answers.getInt(index)
                    require(answer in article.questions[index].options.indices)
                    answer == article.questions[index].answerIndex
                }
                require(row.getInt("correct") == correct)
            }
            "dictation_attempts" -> {
                val sentence = requireNotNull(dictations().find { it.id == row.getString("sentence_id") })
                val answer = row.getString("answer")
                require(answer.isNotBlank() && answer.length <= 2000)
                require(row.getInt("correct") == if (AnswerChecker.matches(answer, sentence.text)) 1 else 0)
            }
            "chat_messages" -> {
                require(row.getString("role") in setOf("user", "assistant"))
                require(row.getString("content").let { it.isNotBlank() && it.length <= 100_000 })
            }
            "metadata" -> require(row.getString("key") == "memory_summary" && row.getString("value").length <= 30_000)
        }
    }

    private fun allProgress(): List<WordProgress> = helper.readableDatabase.query(
        "word_progress", null, null, null, null, null, null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(readProgress(cursor)) } }

    private fun progress(db: SQLiteDatabase, wordId: String): WordProgress? = db.query(
        "word_progress", null, "word_id = ?", arrayOf(wordId), null, null, null,
    ).use { if (it.moveToFirst()) readProgress(it) else null }

    private fun readProgress(cursor: Cursor) = with(cursor) {
        WordProgress(
            getString(getColumnIndexOrThrow("word_id")), getLong(getColumnIndexOrThrow("next_review_at")),
            getDouble(getColumnIndexOrThrow("interval_days")), getInt(getColumnIndexOrThrow("correct_streak")),
            getInt(getColumnIndexOrThrow("attempts")), getInt(getColumnIndexOrThrow("lapses")),
            ReviewRating.valueOf(getString(getColumnIndexOrThrow("last_rating"))), getLong(getColumnIndexOrThrow("last_reviewed_at")),
        )
    }

    private fun progressValues(p: WordProgress) = ContentValues().apply {
        put("word_id", p.wordId); put("next_review_at", p.nextReviewAt); put("interval_days", p.intervalDays)
        put("correct_streak", p.correctStreak); put("attempts", p.attempts); put("lapses", p.lapses)
        put("last_rating", p.lastRating.name); put("last_reviewed_at", p.lastReviewedAt)
    }

    private fun count(sql: String, args: Array<String>? = null): Int =
        helper.readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private inline fun mutate(action: (SQLiteDatabase) -> Unit) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            action(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        _revision.value += 1
    }

    private fun day(now: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    companion object {
        @Volatile private var instance: LearningRepository? = null
        fun get(context: Context): LearningRepository = instance ?: synchronized(this) {
            instance ?: LearningRepository(context).also { instance = it }
        }
        private val BACKUP_TABLES = listOf("word_progress", "word_reviews", "study_time", "reading_attempts", "dictation_attempts", "chat_messages", "metadata")
    }
}

private class LearningDatabase(context: Context) : SQLiteOpenHelper(context, "english_book.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE word_progress (word_id TEXT PRIMARY KEY, next_review_at INTEGER NOT NULL, interval_days REAL NOT NULL, correct_streak INTEGER NOT NULL, attempts INTEGER NOT NULL, lapses INTEGER NOT NULL, last_rating TEXT NOT NULL, last_reviewed_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX progress_due ON word_progress(next_review_at)")
        db.execSQL("CREATE TABLE word_reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, word_id TEXT NOT NULL, reviewed_at INTEGER NOT NULL, day TEXT NOT NULL, rating TEXT NOT NULL, was_new INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX reviews_day ON word_reviews(day)")
        db.execSQL("CREATE TABLE study_time (day TEXT PRIMARY KEY, seconds INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE reading_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, article_id TEXT NOT NULL, day TEXT NOT NULL, created_at INTEGER NOT NULL, correct INTEGER NOT NULL, total INTEGER NOT NULL, answers TEXT NOT NULL)")
        db.execSQL("CREATE INDEX reading_day ON reading_attempts(day)")
        db.execSQL("CREATE TABLE dictation_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, sentence_id TEXT NOT NULL, day TEXT NOT NULL, created_at INTEGER NOT NULL, correct INTEGER NOT NULL, answer TEXT NOT NULL)")
        db.execSQL("CREATE INDEX dictation_day ON dictation_attempts(day)")
        db.execSQL("CREATE TABLE chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL CHECK(role IN ('user', 'assistant')), content TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future versions must add explicit non-destructive migrations here.
        error("Missing database migration from $oldVersion to $newVersion")
    }
}
