package com.samge.bitrans.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One caption entry inside a session */
@Entity(tableName = "caption_items")
data class CaptionItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val ts: Long,          // wall clock when recognized
    val source: String,
    val langTag: String,
    val target: String,
)

/** A listening session (created on stop; title editable) */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,     // default yyyy-MM-dd HH:mm:ss
    val startedAt: Long,
    val endedAt: Long,
)

@Dao
interface CaptionDao {
    @Insert suspend fun insertSession(s: Session): Long

    @Insert suspend fun insertItems(items: List<CaptionItem>)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun renameSession(id: Long, title: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM caption_items WHERE sessionId = :id")
    suspend fun deleteItemsOf(id: Long)

    @Query("SELECT * FROM sessions ORDER BY endedAt DESC")
    fun sessionsFlow(): Flow<List<Session>>

    @Query("SELECT * FROM caption_items WHERE sessionId = :sid ORDER BY ts ASC")
    fun itemsFlow(sid: Long): Flow<List<CaptionItem>>
}

@Database(entities = [Session::class, CaptionItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun captionDao(): CaptionDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "bistrans.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
