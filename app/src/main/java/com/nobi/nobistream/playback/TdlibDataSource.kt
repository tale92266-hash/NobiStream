package com.nobi.nobistream.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.nobi.nobistream.data.TelegramGateway
import com.nobi.nobistream.data.VideoFileInfo
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * ExoPlayer data source that streams a Telegram video through TDLib.
 *
 * The media URI is `tdlib://<fileId>`. On open, the source asks TDLib for
 * the local file (starting its download when needed). On read, if the
 * requested bytes are not downloaded yet, it synchronously downloads that
 * byte range (plus an 8 MB lookahead) from Telegram and then reads it
 * from the local file. Seeking just re-opens at a new position; already
 * downloaded ranges are reused from TDLib's file cache in filesDir
 * (useFileDatabase is on), so a seek never re-downloads what is there.
 *
 * v5 seek fix — the v4 code threw after 3 immediate attempts when a
 * range wasn't downloaded yet, so a +10s seek (whose bytes simply
 * hadn't arrived) showed the playback-error screen instead of
 * buffering. Now ensureDownloaded() waits patiently (up to 60s) for
 * the range to arrive, proving coverage from the returned state on
 * every attempt — a range that still isn't proven after 60s throws,
 * so the error screen with Retry remains for genuinely stuck
 * downloads. Garbage is still never served: only proven ranges are
 * read, and prefetchedUntil is only ever advanced to a proven end.
 */
@UnstableApi
class TdlibDataSource(
    private val gateway: TelegramGateway
) : BaseDataSource(false) {

    private var fileId: Int = 0
    private var fileInfo: VideoFileInfo? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened = false
    private var currentUri: Uri? = null

    companion object {
        /** One range download covers at least this much lookahead. */
        private const val LOOKAHEAD_BYTES = 8L * 1024L * 1024L

        /**
         * v5 seek fix: how long a read may WAIT for its bytes to
         * arrive before it is allowed to fail. A +10s seek lands on
         * a range that is simply not downloaded yet — that is
         * buffering, not failure, and the player shows its buffering
         * overlay while this loop waits. The old code gave up after
         * 3 immediate attempts and threw, so a seek showed the
         * playback-error screen instead of buffering.
         */
        private const val MAX_ENSURE_WAIT_MS = 60_000L
        private const val ENSURE_RETRY_DELAY_MS = 500L
    }

    /**
     * End of the furthest range a synchronous DownloadFile call has
     * completed for this file. Ranges below this are known-downloaded
     * without asking TDLib again (TDLib's file cache persists them).
     */
    private var prefetchedUntil: Long = 0L

    override fun getUri(): Uri? = currentUri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        val newFileId = dataSpec.uri.host?.toIntOrNull()
            ?: dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IllegalArgumentException("Bad tdlib URI: ${dataSpec.uri}")
        if (newFileId != fileId) {
            // A different video: previous prefetch bookkeeping is void.
            prefetchedUntil = 0L
        }
        fileId = newFileId
        val info = runBlocking {
            var found: VideoFileInfo? = null
            // v5 seek fix: a seek open needs bytes AT the seek
            // position, not at offset 0. The old code only started a
            // from-the-start download here and then threw after 30s
            // when the local path still wasn't ready — exactly the
            // +10s seek error. Synchronously fetch the target range
            // first; only fall back to the from-start download when
            // that cannot produce a readable file yet.
            if (dataSpec.position > 0) {
                found = runCatching {
                    gateway.ensureRange(fileId, dataSpec.position, LOOKAHEAD_BYTES)
                }.getOrNull()?.takeIf { range ->
                    range.localPath.isNotEmpty() && File(range.localPath).exists()
                }
            }
            if (found == null) {
                for (attempt in 0 until 60) {
                    val candidate = gateway.downloadFileInfo(fileId)
                    // A path can be assigned before the file exists
                    // on disk — opening it would throw
                    // FileNotFoundException straight into the error
                    // screen, so wait for the real file.
                    if (candidate != null && File(candidate.localPath).exists()) {
                        found = candidate
                        break
                    }
                    delay(500L)
                }
            }
            found
        } ?: throw java.io.IOException("Could not start download of video $fileId")
        fileInfo = info
        readPosition = dataSpec.position
        val totalSize = info.sizeBytes
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize > 0L -> totalSize - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        randomAccessFile = RandomAccessFile(File(info.localPath), "r").apply {
            seek(readPosition)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        if (toRead <= 0) return C.RESULT_END_OF_INPUT
        ensureDownloaded(readPosition, toRead.toLong())
        val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
        val read = raf.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        readPosition += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        return read
    }

    /**
     * Block until the requested range is downloaded — or throw.
     *
     * NEVER serve bytes that aren't proven downloaded: the old code
     * gave up after 3 attempts and read the region anyway, and
     * TDLib's local file can hold garbage in not-yet-downloaded
     * parts. An IOException here makes ExoPlayer rebuffer and retry
     * the load instead of decoding corrupt frames.
     *
     * v5 seek fix: "not here yet" is BUFFERING, not failure. A +10s
     * seek lands on a range the download has not reached, and the
     * old code threw after 3 immediate attempts — so the seek showed
     * the playback-error screen instead of the buffering spinner.
     * This loop now waits patiently (up to 60s, 500ms between
     * attempts) for the range to arrive, proving coverage from the
     * returned state on EVERY attempt. A synchronous DownloadFile
     * that returns early (superseded / failed download) proves
     * nothing and is retried, never trusted. Only a genuinely stuck
     * download — nothing proven after 60s — still throws, so the
     * error screen with Retry remains for real failures.
     *
     * How a range is proven downloaded (in order):
     *  1. the whole file is complete, or
     *  2. the contiguous readable prefix (downloadedPrefixSize,
     *     starting at downloadOffset) covers it, or
     *  3. our own bookkeeping: a range already proven in this
     *     session covers it.
     * A null file state is retried like any other unproven state.
     */
    private fun ensureDownloaded(position: Long, length: Long) {
        val info = fileInfo ?: return
        if (info.isComplete) return
        if (position + length <= prefetchedUntil) return
        if (covers(info, position, length)) {
            prefetchedUntil = maxOf(prefetchedUntil, provenEnd(info))
            return
        }
        // One range download covers at least 8 MB ahead, so the
        // loader doesn't stall on a synchronous TDLib call per small
        // read — the main stutter cause on 1080p+.
        val remaining = if (info.sizeBytes > 0L) {
            (info.sizeBytes - position).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        val want = minOf(maxOf(length, LOOKAHEAD_BYTES), remaining.coerceAtLeast(length))
        val deadline = SystemClock.elapsedRealtime() + MAX_ENSURE_WAIT_MS
        var attempts = 0
        while (true) {
            // The player is tearing down — stop waiting at once.
            if (!opened) {
                throw java.io.IOException("Download interrupted (file $fileId)")
            }
            attempts++
            val downloaded = runBlocking {
                runCatching { gateway.ensureRange(fileId, position, want) }.getOrNull()
            }
            if (downloaded != null) {
                fileInfo = downloaded
                if (covers(downloaded, position, length)) {
                    prefetchedUntil = maxOf(prefetchedUntil, provenEnd(downloaded))
                    return
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw java.io.IOException(
                    "Could not download video bytes at $position " +
                        "(file $fileId) after $attempts attempts"
                )
            }
            runBlocking { delay(ENSURE_RETRY_DELAY_MS) }
        }
    }

    /**
     * True when [info] PROVES the bytes [position, position+length)
     * are readable: the whole file is complete, or the contiguous
     * downloaded prefix (which starts at downloadOffset, not
     * always at 0 — a range download reports its own offset) covers
     * the range. downloadedSize is progress-only and is never used
     * here — bytes beyond the prefix may be garbage.
     */
    private fun covers(info: VideoFileInfo, position: Long, length: Long): Boolean {
        if (info.isComplete) return true
        val prefixStart = info.downloadOffset
        val prefixEnd = prefixStart + info.downloadedPrefixSize
        return prefixStart <= position && prefixEnd >= position + length
    }

    /** Furthest byte proven downloaded by [info] (Long.MAX_VALUE when complete). */
    private fun provenEnd(info: VideoFileInfo): Long {
        if (info.isComplete) {
            return if (info.sizeBytes > 0L) info.sizeBytes else Long.MAX_VALUE
        }
        return info.downloadOffset + info.downloadedPrefixSize
    }

    override fun close() {
        if (opened) {
            opened = false
            try {
                randomAccessFile?.close()
            } catch (_: Exception) {
            } finally {
                randomAccessFile = null
            }
            currentUri = null
            transferEnded()
        }
    }

    /** Factory used by ExoPlayer's ProgressiveMediaSource. */
    class Factory(private val gateway: TelegramGateway) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdlibDataSource(gateway)
    }
}
