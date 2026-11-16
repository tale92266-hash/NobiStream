package com.nobi.nobistream.data

import android.content.Context
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.core.VideoQuality
import com.nobi.nobistream.data.db.AppDatabase
import com.nobi.nobistream.data.db.SavedGroupEntity
import com.nobi.nobistream.data.db.VideoPositionEntity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only class screens talk to for Telegram data. It combines the TDLib
 * gateway with the local Room database (saved groups, resume positions).
 */
class NobiRepository(
    private val context: Context,
    private val gateway: TelegramGateway,
    private val database: AppDatabase,
    val settingsStore: SettingsStore,
    val credentialsStore: ApiCredentialsStore
) {

    val authState: Flow<AuthState> = gateway.authState

    val currentUser = gateway.currentUser

    /** Start the Telegram client if credentials exist. False when none exist. */
    suspend fun ensureStarted(): Boolean {
        if (!credentialsStore.hasCredentials()) return false
        if (gateway.isStarted) return true
        return try {
            gateway.start(credentialsStore.getApiId(), credentialsStore.getApiHash())
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendPhoneNumber(phone: String) = gateway.sendPhoneNumber(phone)

    suspend fun sendCode(code: String) = gateway.sendCode(code)

    suspend fun sendPassword(password: String) = gateway.sendPassword(password)

    suspend fun logout() {
        gateway.logout()
    }

    fun observeGroups(): Flow<List<GroupItem>> =
        database.groupDao().observeGroups().map { rows ->
            rows.map { row ->
                GroupItem(
                    chatId = row.chatId,
                    username = row.username,
                    title = row.title,
                    photoLocalPath = row.photoLocalPath,
                    videoCount = row.videoCount,
                    lastUpdatedEpochSec = row.lastUpdatedEpochSec
                )
            }
        }

    suspend fun addGroup(link: String): GroupItem {
        val group = gateway.resolveChat(link)
        database.groupDao().upsert(
            SavedGroupEntity(
                chatId = group.chatId,
                username = group.username,
                title = group.title,
                photoLocalPath = group.photoLocalPath,
                videoCount = group.videoCount,
                lastUpdatedEpochSec = group.lastUpdatedEpochSec
            )
        )
        return group
    }

    suspend fun removeGroup(chatId: Long) {
        database.groupDao().delete(chatId)
    }

    /** Load one page of videos and merge in saved resume positions. */
    suspend fun loadVideos(chatId: Long, fromMessageId: Long, limit: Int = 50): VideoPage {
        val page = gateway.loadVideos(chatId, fromMessageId, limit)
        if (fromMessageId == 0L && page.totalCount > 0) {
            database.groupDao().updateCount(
                chatId,
                page.totalCount,
                System.currentTimeMillis() / 1000L
            )
        }
        if (page.videos.isEmpty()) return page
        val positions = database.positionDao().getPositionsForChat(chatId)
            .associateBy { it.messageId }
        val merged = page.videos.map { video ->
            val saved = positions[video.messageId]
            if (saved != null) {
                video.copy(progressSec = saved.positionSec, watched = saved.watched)
            } else {
                video
            }
        }
        return VideoPage(videos = merged, totalCount = page.totalCount)
    }

    suspend fun downloadThumbnail(fileId: Int): String? =
        gateway.downloadThumbnail(fileId)

    suspend fun savePosition(item: VideoItem, positionSec: Long) {
        val watched = item.durationSec > 0 && positionSec >= item.durationSec - 10L
        database.positionDao().upsert(
            VideoPositionEntity(
                chatId = item.chatId,
                messageId = item.messageId,
                title = item.title,
                caption = item.caption,
                qualityLabel = item.quality.label,
                durationSec = item.durationSec,
                sizeBytes = item.sizeBytes,
                dateEpochSec = item.dateEpochSec,
                thumbFileId = item.thumbFileId,
                thumbLocalPath = item.thumbLocalPath,
                fileId = item.fileId,
                remoteFileId = item.remoteFileId,
                positionSec = positionSec,
                watched = watched,
                updatedAtEpochSec = System.currentTimeMillis() / 1000L
            )
        )
    }

    suspend fun getPositionSec(chatId: Long, messageId: Long): Long =
        database.positionDao().getPosition(chatId, messageId)?.positionSec ?: 0L

    fun observeContinueWatching(): Flow<List<VideoItem>> =
        database.positionDao().observeContinueWatching().map { rows ->
            rows.map { row ->
                VideoItem(
                    messageId = row.messageId,
                    chatId = row.chatId,
                    fileId = row.fileId,
                    remoteFileId = row.remoteFileId,
                    title = row.title,
                    caption = row.caption,
                    quality = VideoQuality.fromLabel(row.qualityLabel),
                    durationSec = row.durationSec,
                    sizeBytes = row.sizeBytes,
                    dateEpochSec = row.dateEpochSec,
                    thumbFileId = row.thumbFileId,
                    thumbLocalPath = row.thumbLocalPath,
                    progressSec = row.positionSec,
                    watched = row.watched
                )
            }
        }

    /** Delete ExoPlayer / image caches. TDLib's own files are left alone. */
    suspend fun clearCache() {
        val cacheDir = context.cacheDir
        File(cacheDir, "media3").deleteRecursively()
        File(cacheDir, "exoplayer").deleteRecursively()
        File(cacheDir, "image_cache").deleteRecursively()
    }
}
