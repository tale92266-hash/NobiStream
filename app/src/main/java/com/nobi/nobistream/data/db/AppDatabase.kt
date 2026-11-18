package com.nobi.nobistream.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** A Telegram group or channel the user added on Home. */
@Entity(tableName = "saved_groups")
data class SavedGroupEntity(
    @PrimaryKey val chatId: Long,
    val username: String,
    val title: String,
    val photoLocalPath: String?,
    val videoCount: Int,
    val lastUpdatedEpochSec: Long
)

/** Resume position (and display data) for one video message. */
@Entity(tableName = "video_positions", primaryKeys = ["chatId", "messageId"])
data class VideoPositionEntity(
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val caption: String,
    val qualityLabel: String,
    val durationSec: Int,
    val sizeBytes: Long,
    val dateEpochSec: Long,
    val thumbFileId: Int?,
    val thumbLocalPath: String?,
    val fileId: Int,
    val remoteFileId: String,
    val positionSec: Long,
    val watched: Boolean,
    val updatedAtEpochSec: Long
)

/** Download status for one row of the `downloads` table. */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

/**
 * v2.6.0: one queued / in-progress / completed / failed whole-file
 * download. The local TDLib file id is NEVER stored here (it dies on
 * client restart) — [remoteFileId] is the persistent identity and is
 * re-resolved via GetRemoteFile on every start/resume.
 */
@Entity(tableName = "downloads", primaryKeys = ["chatId", "messageId"])
data class DownloadEntity(
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val qualityLabel: String,
    val sizeBytes: Long,
    val downloadedBytes: Long,
    val localPath: String,
    val remoteFileId: String,
    /** [DownloadStatus.name]. */
    val status: String,
    val errorMessage: String?,
    val updatedAtEpochSec: Long
)

/**
 * v2.6.0: one user-bookmarked video message (Library Watchlist tab).
 * The video itself is untouched in Telegram; deleting the row only
 * un-bookmarks it.
 */
@Entity(tableName = "bookmarked_videos", primaryKeys = ["chatId", "messageId"])
data class BookmarkedVideoEntity(
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val caption: String,
    val qualityLabel: String,
    val durationSec: Int,
    val sizeBytes: Long,
    val dateEpochSec: Long,
    val thumbFileId: Int?,
    val thumbLocalPath: String?,
    val remoteFileId: String,
    val bookmarkedAtEpochSec: Long
)

/**
 * v2.6.0: one user-marked filler episode (Detail "Skip Fillers").
 * Telegram carries no filler data, so marks are manual only.
 */
@Entity(tableName = "filler_marks", primaryKeys = ["chatId", "messageId"])
data class FillerMarkEntity(
    val chatId: Long,
    val messageId: Long,
    val markedAtEpochSec: Long
)

@Dao
interface GroupDao {
    @Query("SELECT * FROM saved_groups ORDER BY title ASC")
    fun observeGroups(): Flow<List<SavedGroupEntity>>

    @Query("SELECT * FROM saved_groups WHERE chatId = :chatId LIMIT 1")
    suspend fun getGroup(chatId: Long): SavedGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: SavedGroupEntity)

    @Query("UPDATE saved_groups SET videoCount = :count, lastUpdatedEpochSec = :updatedAt WHERE chatId = :chatId")
    suspend fun updateCount(chatId: Long, count: Int, updatedAt: Long)

    @Query("DELETE FROM saved_groups WHERE chatId = :chatId")
    suspend fun delete(chatId: Long)
}

@Dao
interface PositionDao {
    @Query("SELECT * FROM video_positions WHERE positionSec > 0 AND watched = 0 ORDER BY updatedAtEpochSec DESC LIMIT 10")
    fun observeContinueWatching(): Flow<List<VideoPositionEntity>>

    /**
     * v4: the same list WITHOUT the 10-item cap, for the full
     * Continue Watching ("See all") screen. Same query otherwise —
     * no schema change, no migration.
     */
    @Query("SELECT * FROM video_positions WHERE positionSec > 0 AND watched = 0 ORDER BY updatedAtEpochSec DESC")
    fun observeContinueWatchingAll(): Flow<List<VideoPositionEntity>>

    /** The single most recently watched (unfinished) video, for the Home hero card and auto-redirect. */
    @Query("SELECT * FROM video_positions WHERE positionSec > 0 AND watched = 0 ORDER BY updatedAtEpochSec DESC LIMIT 1")
    suspend fun getMostRecent(): VideoPositionEntity?

    @Query("SELECT * FROM video_positions WHERE chatId = :chatId AND messageId = :messageId LIMIT 1")
    suspend fun getPosition(chatId: Long, messageId: Long): VideoPositionEntity?

    @Query("SELECT * FROM video_positions WHERE chatId = :chatId")
    suspend fun getPositionsForChat(chatId: Long): List<VideoPositionEntity>

    /**
     * v4-fb2: remember a downloaded thumbnail's local path, so the
     * next bind loads it from disk with no TDLib call. No schema
     * change — the column already exists, it was just never written.
     */
    @Query("UPDATE video_positions SET thumbLocalPath = :path WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun updateThumbPath(chatId: Long, messageId: Long, path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: VideoPositionEntity)

    /**
     * v2.6.0: manual watched toggle. Only the flag changes —
     * positionSec is left untouched, so un-marking a video restores
     * its resume position.
     */
    @Query("UPDATE video_positions SET watched = :watched WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun updateWatched(chatId: Long, messageId: Long, watched: Boolean): Int

    /** v2.6.0: how many videos the user has marked watched (Control stats). */
    @Query("SELECT COUNT(*) FROM video_positions WHERE watched = 1")
    fun observeWatchedCount(): Flow<Int>
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAtEpochSec DESC")
    fun observeDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE chatId = :chatId AND messageId = :messageId LIMIT 1")
    suspend fun getDownload(chatId: Long, messageId: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :bytes, status = :status, updatedAtEpochSec = :updatedAt WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun updateProgress(chatId: Long, messageId: Long, bytes: Long, status: String, updatedAt: Long)

    @Query("DELETE FROM downloads WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun delete(chatId: Long, messageId: Long)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    /** Rows still to run, in queue (oldest-first) order. */
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING') ORDER BY updatedAtEpochSec ASC")
    suspend fun getActive(): List<DownloadEntity>

    /** Total bytes of completed downloads (Control "Downloads use X" row). */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM downloads WHERE status = 'COMPLETED'")
    fun observeCompletedBytes(): Flow<Long>
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarked_videos ORDER BY bookmarkedAtEpochSec DESC")
    fun observeBookmarks(): Flow<List<BookmarkedVideoEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarked_videos WHERE chatId = :chatId AND messageId = :messageId)")
    fun isBookmarked(chatId: Long, messageId: Long): Flow<Boolean>

    @Query("SELECT * FROM bookmarked_videos WHERE chatId = :chatId AND messageId = :messageId LIMIT 1")
    suspend fun getBookmark(chatId: Long, messageId: Long): BookmarkedVideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkedVideoEntity)

    @Query("DELETE FROM bookmarked_videos WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun delete(chatId: Long, messageId: Long)

    @Query("SELECT COUNT(*) FROM bookmarked_videos")
    fun observeCount(): Flow<Int>
}

@Dao
interface FillerMarkDao {
    @Query("SELECT * FROM filler_marks")
    fun observeMarks(): Flow<List<FillerMarkEntity>>

    @Query("SELECT * FROM filler_marks WHERE chatId = :chatId AND messageId = :messageId LIMIT 1")
    suspend fun getMark(chatId: Long, messageId: Long): FillerMarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mark: FillerMarkEntity)

    @Query("DELETE FROM filler_marks WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun delete(chatId: Long, messageId: Long)
}

/**
 * v2.6.0: adds the downloads, bookmarked_videos and filler_marks
 * tables. Plain CREATE TABLE statements — saved groups and resume
 * positions survive the upgrade untouched (no destructive migration).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `downloads` (" +
                "`chatId` INTEGER NOT NULL, " +
                "`messageId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`qualityLabel` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`downloadedBytes` INTEGER NOT NULL, " +
                "`localPath` TEXT NOT NULL, " +
                "`remoteFileId` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`errorMessage` TEXT, " +
                "`updatedAtEpochSec` INTEGER NOT NULL, " +
                "PRIMARY KEY(`chatId`, `messageId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookmarked_videos` (" +
                "`chatId` INTEGER NOT NULL, " +
                "`messageId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`caption` TEXT NOT NULL, " +
                "`qualityLabel` TEXT NOT NULL, " +
                "`durationSec` INTEGER NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`dateEpochSec` INTEGER NOT NULL, " +
                "`thumbFileId` INTEGER, " +
                "`thumbLocalPath` TEXT, " +
                "`remoteFileId` TEXT NOT NULL, " +
                "`bookmarkedAtEpochSec` INTEGER NOT NULL, " +
                "PRIMARY KEY(`chatId`, `messageId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `filler_marks` (" +
                "`chatId` INTEGER NOT NULL, " +
                "`messageId` INTEGER NOT NULL, " +
                "`markedAtEpochSec` INTEGER NOT NULL, " +
                "PRIMARY KEY(`chatId`, `messageId`))"
        )
    }
}

@Database(
    entities = [
        SavedGroupEntity::class,
        VideoPositionEntity::class,
        DownloadEntity::class,
        BookmarkedVideoEntity::class,
        FillerMarkEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun positionDao(): PositionDao
    abstract fun downloadDao(): DownloadDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun fillerMarkDao(): FillerMarkDao
}
