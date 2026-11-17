package com.nobi.nobistream.playback

import android.net.Uri
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
 * v4 stutter/corruption fix — the old ensureDownloaded() gated reads on
 * `downloadedSize`, which the TL scheme documents as progress-only
 * ("some parts of it may contain garbage"). For any seek past the
 * total downloaded sum the gate stayed open, the code burned 3
 * synchronous calls per read (loader stalls = stutter) and then read
 * the region ANYWAY, unverified — garbage bytes in, purple/green
 * macroblocks out. Now: readiness is judged by the contiguous
 * readable prefix (downloadedPrefixSize) plus our own bookkeeping of
 * ranges a synchronous DownloadFile call already completed, and a
 * range that still isn't there after retries throws instead of
 * serving unchecked bytes (ExoPlayer rebuffers/retries instead of
 * decoding garbage).
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
        private const val MAX_ENSURE_ATTEMPTS = 3
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
            for (attempt in 0 until 60) {
                found = gateway.downloadFileInfo(fileId)
                if (found != null) break
                delay(500L)
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
     * How a range is proven downloaded (in order):
     *  1. the whole file is complete, or
     *  2. the contiguous readable prefix (downloadedPrefixSize, from
     *     downloadOffset) already covers it, or
     *  3. our own bookkeeping: a synchronous DownloadFile call for
     *     this file already completed a range covering it, or
     *  4. one synchronous DownloadFile call for [position, +lookahead)
     *     returns without error — TDLib's documented contract is
     *     that a synchronous call returns only after that part is
     *     downloaded (or failed/cancelled/superseded, which surface
     *     as an exception or a null file state, never as success).
     * A null file state is retried; after 3 attempts it throws.
     */
    private fun ensureDownloaded(position: Long, length: Long) {
        val info = fileInfo ?: return
        if (info.isComplete) return
        if (position + length <= prefetchedUntil) return
        if (info.downloadOffset == 0L &&
            info.downloadedPrefixSize >= position + length
        ) {
            prefetchedUntil = maxOf(prefetchedUntil, position + length)
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
        var attempts = 0
        var last: VideoFileInfo? = null
        while (attempts < MAX_ENSURE_ATTEMPTS) {
            attempts++
            last = runBlocking {
                runCatching { gateway.ensureRange(fileId, position, want) }.getOrNull()
            }
            if (last != null) break
        }
        val downloaded = last
            ?: throw java.io.IOException(
                "Could not download video bytes at $position (file $fileId)"
            )
        fileInfo = downloaded
        prefetchedUntil = maxOf(prefetchedUntil, position + want)
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
