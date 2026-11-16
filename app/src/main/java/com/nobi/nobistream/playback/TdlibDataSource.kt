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
 * requested bytes are not downloaded yet, it synchronously downloads exactly
 * that byte range from Telegram and then reads it from the local file.
 * Seeking just re-opens at a new position, which re-downloads that range.
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

    override fun getUri(): Uri? = currentUri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        fileId = dataSpec.uri.host?.toIntOrNull()
            ?: dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IllegalArgumentException("Bad tdlib URI: ${dataSpec.uri}")
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

    /** Block until the requested range is downloaded (up to 3 attempts). */
    private fun ensureDownloaded(position: Long, length: Long) {
        var info = fileInfo ?: return
        var attempts = 0
        while (!info.isComplete && info.downloadedBytes < position + length && attempts < 3) {
            attempts++
            info = runBlocking {
                gateway.ensureRange(fileId, position, length)
            } ?: return
            fileInfo = info
        }
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
