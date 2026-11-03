package com.lyricstage.lyrics.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Cached lyrics for a track key. [raw] is LRC text, plain text, or empty for instrumentals. */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val trackKey: String,
    val raw: String,
    val synced: Boolean,
    val instrumental: Boolean,
    /** "lrclib" or "import". */
    val source: String,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val updatedAt: Long,
)

/** Per-track user sync offset in milliseconds. */
@Entity(tableName = "offsets")
data class OffsetEntity(
    @PrimaryKey val trackKey: String,
    val offsetMs: Long,
)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE trackKey = :key")
    fun observe(key: String): Flow<LyricsEntity?>

    @Query("SELECT * FROM lyrics WHERE trackKey = :key")
    suspend fun get(key: String): LyricsEntity?

    @Query("SELECT * FROM lyrics ORDER BY updatedAt DESC")
    suspend fun all(): List<LyricsEntity>

    @Upsert
    suspend fun upsert(entity: LyricsEntity)
}

@Dao
interface OffsetDao {
    @Query("SELECT offsetMs FROM offsets WHERE trackKey = :key")
    fun observe(key: String): Flow<Long?>

    @Upsert
    suspend fun upsert(entity: OffsetEntity)
}

@Database(entities = [LyricsEntity::class, OffsetEntity::class], version = 1, exportSchema = true)
abstract class LyricsDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsDao
    abstract fun offsetDao(): OffsetDao

    companion object {
        fun create(context: Context): LyricsDatabase =
            Room.databaseBuilder(context, LyricsDatabase::class.java, "lyricstage.db").build()
    }
}
