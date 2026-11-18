package com.nobi.nobistream.data

import android.content.Context
import android.os.StatFs
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.data.db.AppDatabase
import com.nobi.nobistream.data.db.DownloadEntity
import com.nobi.nobistream.data.db.DownloadStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v2.6.0: the real download manager (spec §6).
 *
 * Whole-file downloads through TDLib (`DownloadFile`, offset 0 /
 * limit 0), Room-backed queue, FIFO, ONE active download at a time.
 * The downloaded file lives in TDLib's own files dir — the same
 * bytes the player streams — so no copy is made and no new
 * permission is needed. No foreground service, no WorkManager, no
 * notification: downloads run while the app process lives and
 * auto-resume on the next launch from their partial bytes.
 *
 * The local TDLib file id is NEVER trusted across restarts: every
 * start / resume re-resolves it from the stored [DownloadEntity.remoteFileId].
 */
class DownloadManager(
    context: Context,
    private val gateway: TelegramGateway,
    private val database: AppDatabase
) {

    private val appContext = context.applicationContext
    private val dao = database.downloadDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One active download at a time — the pump holds this while a file runs. */
    private val pumpMutex = Mutex()
    private val pumpRunning = AtomicBoolean(false)

    /** All download rows, newest first (Library Offline tab). */
    val downloads: Flow<List<DownloadEntity>> = dao.observeDownloads()

    /** Total bytes of COMPLETED downloads (Control "Downloads use X" row). */
    val completedBytes: Flow<Long> = dao.observeCompletedBytes()

    enum class EnqueueResult {
        QUEUED,
        ALREADY_PRESENT,
        ALREADY_COMPLETED,
        NO_SPACE
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    private fun shortError(e: Throwable): String =
        (e.message?.takeIf { it.isNotBlank() } ?: "Download failed").take(80)

    /**
     * Queue a video for whole-file download. Resolves a fresh local
     * file id first; when the video no longer resolves, a FAILED row
     * ("Video no longer available") is created instead and
     * [EnqueueResult.QUEUED] is returned (the enum has no FAILED
     * value — the FAILED row is visible through [downloads]).
     * A FAILED row re-queued here keeps its partial bytes, so TDLib
     * resumes from them instead of starting over.
     */
    suspend fun enqueue(item: VideoItem): EnqueueResult {
        val existing = dao.getDownload(item.chatId, item.messageId)
        when (existing?.status) {
            DownloadStatus.QUEUED.name, DownloadStatus.DOWNLOADING.name ->
                return EnqueueResult.ALREADY_PRESENT
            DownloadStatus.COMPLETED.name -> return EnqueueResult.ALREADY_COMPLETED
            else -> Unit
        }
        // Free-space check BEFORE queueing — no row is created on refusal.
        if (item.sizeBytes > 0L) {
            val freeBytes = runCatching {
                StatFs(appContext.filesDir.absolutePath).availableBytes
            }.getOrNull()
            if (freeBytes != null && item.sizeBytes > freeBytes) {
                return EnqueueResult.NO_SPACE
            }
        }
        val fileId = runCatching { gateway.resolveFileId(item.remoteFileId) }.getOrNull()
        val now = nowSec()
        if (fileId == null) {
            dao.upsert(
                DownloadEntity(
                    chatId = item.chatId,
                    messageId = item.messageId,
                    title = item.title,
                    qualityLabel = item.quality.label,
                    sizeBytes = item.sizeBytes,
                    downloadedBytes = existing?.downloadedBytes ?: 0L,
                    localPath = existing?.localPath ?: "",
                    remoteFileId = item.remoteFileId,
                    status = DownloadStatus.FAILED.name,
                    errorMessage = "Video no longer available",
                    updatedAtEpochSec = now
                )
            )
            return EnqueueResult.QUEUED
        }
        dao.upsert(
            DownloadEntity(
                chatId = item.chatId,
                messageId = item.messageId,
                title = item.title,
                qualityLabel = item.quality.label,
                sizeBytes = item.sizeBytes,
                downloadedBytes = existing?.downloadedBytes ?: 0L,
                localPath = existing?.localPath ?: "",
                remoteFileId = item.remoteFileId,
                status = DownloadStatus.QUEUED.name,
                errorMessage = null,
                updatedAtEpochSec = now
            )
        )
        // The pump may not have been started yet — starting is idempotent.
        start()
        return EnqueueResult.QUEUED
    }

    /** Re-queue a FAILED row (TDLib resumes from its partial bytes). No-op otherwise. */
    suspend fun retry(chatId: Long, messageId: Long) {
        val row = dao.getDownload(chatId, messageId) ?: return
        if (row.status != DownloadStatus.FAILED.name) return
        dao.upsert(
            row.copy(
                status = DownloadStatus.QUEUED.name,
                errorMessage = null,
                updatedAtEpochSec = nowSec()
            )
        )
        start()
    }

    /**
     * Cancel a queued / downloading row: deletes the Room row ONLY.
     * The partial bytes stay in TDLib's files dir as streaming cache
     * — a later stream or re-download reuses them.
     */
    suspend fun cancel(chatId: Long, messageId: Long) {
        dao.delete(chatId, messageId)
    }

    /**
     * Delete a download: the Room row AND the local file (via
     * DeleteFile on a freshly resolved id). When the file no longer
     * resolves, the row is still deleted. A non-completed row is
     * row-only (same rule as [cancel]).
     */
    suspend fun deleteCompleted(chatId: Long, messageId: Long) {
        val row = dao.getDownload(chatId, messageId) ?: return
        if (row.status == DownloadStatus.COMPLETED.name) {
            val fileId = runCatching { gateway.resolveFileId(row.remoteFileId) }.getOrNull()
            if (fileId != null) {
                runCatching { gateway.deleteFile(fileId) }
            }
        }
        dao.delete(chatId, messageId)
    }

    /** Per-row rules: completed rows lose file + row, the rest row only. */
    suspend fun deleteAll() {
        val rows = dao.observeDownloads().first()
        for (row in rows) {
            if (row.status == DownloadStatus.COMPLETED.name) {
                val fileId = runCatching { gateway.resolveFileId(row.remoteFileId) }.getOrNull()
                if (fileId != null) {
                    runCatching { gateway.deleteFile(fileId) }
                }
            }
            dao.delete(row.chatId, row.messageId)
        }
    }

    /**
     * Re-issue all QUEUED / DOWNLOADING rows in queue order on app
     * start. The stored local id is never trusted — each row is
     * re-resolved first; a row whose video no longer resolves
     * becomes FAILED ("Video no longer available"). Resolvable rows
     * are left for the pump, which starts them in the same order.
     */
    suspend fun resumePending() {
        val active = dao.getActive()
        for (row in active) {
            val fileId = runCatching { gateway.resolveFileId(row.remoteFileId) }.getOrNull()
            if (fileId == null) {
                dao.upsert(
                    row.copy(
                        status = DownloadStatus.FAILED.name,
                        errorMessage = "Video no longer available",
                        updatedAtEpochSec = nowSec()
                    )
                )
            }
        }
    }

    /**
     * Logout: delete the rows only. TDLib logout already destroys
     * the local files (including the file database), so there is
     * nothing left to delete.
     */
    suspend fun clearAllOnLogout() {
        dao.deleteAll()
    }

    /** Start the internal pump coroutine. Safe to call repeatedly. */
    fun start() {
        if (pumpRunning.compareAndSet(false, true)) {
            scope.launch { pumpLoop() }
        }
    }

    private suspend fun pumpLoop() {
        while (true) {
            pumpMutex.withLock {
                val next = dao.getActive().firstOrNull()
                if (next == null) {
                    delay(1_000L)
                } else {
                    downloadOne(next)
                }
            }
        }
    }

    /**
     * Run one download to completion / failure. The row is checked
     * on every poll so a [cancel] (row deleted) stops the loop.
     * A download that makes no progress at all for
     * [STALL_TIMEOUT_MS] fails with "Network error" — TDLib exposes
     * no error field on the file state, so a long stall is the only
     * observable failure signal.
     */
    private suspend fun downloadOne(row: DownloadEntity) {
        val fileId = runCatching { gateway.resolveFileId(row.remoteFileId) }.getOrNull()
        if (fileId == null) {
            dao.upsert(
                row.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = "Video no longer available",
                    updatedAtEpochSec = nowSec()
                )
            )
            return
        }
        val started = try {
            gateway.startWholeFileDownload(fileId)
        } catch (e: Exception) {
            dao.upsert(
                row.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = shortError(e),
                    updatedAtEpochSec = nowSec()
                )
            )
            return
        }
        dao.upsert(
            row.copy(
                status = DownloadStatus.DOWNLOADING.name,
                downloadedBytes = started?.downloadedBytes ?: row.downloadedBytes,
                localPath = started?.localPath?.takeIf { it.isNotEmpty() } ?: row.localPath,
                updatedAtEpochSec = nowSec()
            )
        )
        var lastWrittenBytes = started?.downloadedBytes ?: row.downloadedBytes
        var lastWriteMs = System.currentTimeMillis()
        var lastProgressMs = System.currentTimeMillis()
        var lastSeenBytes = lastWrittenBytes
        while (true) {
            delay(1_000L)
            // Cancelled (or deleted) while running — stop quietly.
            val current = dao.getDownload(row.chatId, row.messageId) ?: return
            if (current.status != DownloadStatus.DOWNLOADING.name) return
            val state = gateway.getCachedFileState(fileId)
            if (state == null) {
                if (System.currentTimeMillis() - lastProgressMs > STALL_TIMEOUT_MS) {
                    dao.upsert(
                        current.copy(
                            status = DownloadStatus.FAILED.name,
                            errorMessage = "Network error",
                            updatedAtEpochSec = nowSec()
                        )
                    )
                    return
                }
                continue
            }
            val totalSize = if (row.sizeBytes > 0L) row.sizeBytes else state.sizeBytes
            if (state.isComplete) {
                dao.upsert(
                    current.copy(
                        status = DownloadStatus.COMPLETED.name,
                        sizeBytes = totalSize,
                        downloadedBytes = totalSize,
                        localPath = state.localPath.takeIf { it.isNotEmpty() } ?: current.localPath,
                        updatedAtEpochSec = nowSec()
                    )
                )
                return
            }
            val bytes = state.downloadedBytes
            if (bytes > lastSeenBytes) {
                lastSeenBytes = bytes
                lastProgressMs = System.currentTimeMillis()
            }
            // Throttle: at most one Room write per second AND at
            // least a 1% change (any increase when the size is unknown).
            val nowMs = System.currentTimeMillis()
            val minDelta = if (totalSize > 0L) totalSize / 100L else 0L
            if (nowMs - lastWriteMs >= 1_000L && bytes - lastWrittenBytes > minDelta) {
                dao.updateProgress(
                    row.chatId,
                    row.messageId,
                    bytes,
                    DownloadStatus.DOWNLOADING.name,
                    nowSec()
                )
                lastWriteMs = nowMs
                lastWrittenBytes = bytes
            }
            if (nowMs - lastProgressMs > STALL_TIMEOUT_MS) {
                dao.upsert(
                    current.copy(
                        status = DownloadStatus.FAILED.name,
                        errorMessage = "Network error",
                        updatedAtEpochSec = nowSec()
                    )
                )
                return
            }
        }
    }

    companion object {
        /** No progress at all for this long = the download has failed. */
        private const val STALL_TIMEOUT_MS = 120_000L
    }
}
