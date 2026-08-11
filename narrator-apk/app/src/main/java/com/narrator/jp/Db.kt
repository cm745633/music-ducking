package com.narrator.jp

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "play_log")
data class PlayLog(
    @PrimaryKey(autoGenerate = true) val logId: Long = 0L,
    val clipId: String,
    val playedAt: Long
)

/**
 * logId 指向 PlayLog：標記的對象是「播放歷史中的某個位置」，不是「當前這條」。
 * clipId 是刻意的反正規化，讓匯出與排除不必每次 join。
 * latencyMs = flaggedAt - playedAt，用來事後判斷這筆標記可不可信。
 */
@Entity(tableName = "flag")
data class Flag(
    @PrimaryKey(autoGenerate = true) val flagId: Long = 0L,
    val logId: Long,
    val clipId: String,
    val flaggedAt: Long,
    val latencyMs: Long,
    val reason: String? = null,
    val note: String? = null
)

/** 單條增益微調，-6dB ～ +6dB，播放時疊加於主音量。 */
@Entity(tableName = "clip_gain")
data class ClipGain(
    @PrimaryKey val clipId: String,
    val gainDb: Float
)

@Dao
interface NarratorDao {

    @Insert
    suspend fun insertLog(log: PlayLog): Long

    @Query("SELECT * FROM play_log WHERE logId = :logId LIMIT 1")
    suspend fun logById(logId: Long): PlayLog?

    @Query("SELECT * FROM play_log WHERE playedAt >= :since ORDER BY playedAt DESC")
    suspend fun logsSince(since: Long): List<PlayLog>

    @Query("SELECT * FROM play_log ORDER BY playedAt DESC LIMIT :n")
    suspend fun recentLogs(n: Int): List<PlayLog>

    @Query("SELECT * FROM play_log ORDER BY playedAt ASC")
    suspend fun allLogs(): List<PlayLog>

    @Query("SELECT COUNT(*) FROM play_log WHERE playedAt >= :since")
    suspend fun countLogsSince(since: Long): Int

    @Query("DELETE FROM play_log WHERE playedAt < :before")
    suspend fun purgeLogsBefore(before: Long)

    @Insert
    suspend fun insertFlag(flag: Flag): Long

    @Query("SELECT * FROM flag WHERE logId = :logId LIMIT 1")
    suspend fun flagForLog(logId: Long): Flag?

    @Query("SELECT * FROM flag ORDER BY flaggedAt DESC")
    suspend fun allFlags(): List<Flag>

    @Query("SELECT * FROM flag WHERE reason IS NULL ORDER BY flaggedAt DESC")
    suspend fun unclassifiedFlags(): List<Flag>

    @Query("SELECT logId FROM flag")
    suspend fun flaggedLogIds(): List<Long>

    @Query("SELECT COUNT(*) FROM flag WHERE flaggedAt >= :since")
    suspend fun countFlagsSince(since: Long): Int

    @Query("UPDATE flag SET reason = :reason, note = :note WHERE flagId = :flagId")
    suspend fun classify(flagId: Long, reason: String?, note: String?)

    @Query("DELETE FROM flag WHERE flagId = :flagId")
    suspend fun deleteFlag(flagId: Long)

    @Query("DELETE FROM flag WHERE logId = :logId")
    suspend fun deleteFlagByLog(logId: Long)

    /** 排除池：已標記且未被判定為「標錯」或「好句」的 clip。排除是暫時的，不刪檔案。 */
    @Query("SELECT DISTINCT clipId FROM flag WHERE reason IS NULL OR reason NOT IN ('mistake', 'good')")
    suspend fun excludedClipIds(): List<String>

    @Query("SELECT * FROM clip_gain")
    suspend fun allGains(): List<ClipGain>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setGain(gain: ClipGain)
}

@Database(
    entities = [PlayLog::class, Flag::class, ClipGain::class],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {

    abstract fun dao(): NarratorDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(ctx: Context): AppDb {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val db = Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDb::class.java,
                    "narrator.db"
                ).fallbackToDestructiveMigration().build()
                instance = db
                return db
            }
        }
    }
}
