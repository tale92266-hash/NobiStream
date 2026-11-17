package com.nobi.nobistream.data

import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.TelegramUser
import com.nobi.nobistream.core.VideoItem
import kotlinx.coroutines.flow.StateFlow

/** Local info about one TDLib file, enough for the player to stream it. */
data class VideoFileInfo(
    val fileId: Int,
    val sizeBytes: Long,
    val localPath: String,
    val downloadedBytes: Long,
    val isComplete: Boolean
)

/** One page of chat history. */
data class VideoPage(
    val videos: List<VideoItem>,
    val totalCount: Int
)

/**
 * Seam between the app and Telegram (MTProto via TDLib). The rest of the app
 * only talks to this interface, so the TDLib implementation can be replaced
 * without touching any screen.
 */
interface TelegramGateway {

    /** Current login state, driven by TDLib authorization updates. */
    val authState: StateFlow<AuthState>

    /** Signed-in user once [AuthState.READY], null otherwise. */
    val currentUser: StateFlow<TelegramUser?>

    /** True once a TDLib client has been created in this process. */
    val isStarted: Boolean

    /** Create the TDLib client and start the login handshake. Idempotent. */
    suspend fun start(apiId: Int, apiHash: String)

    /** Send the phone number (with country code, e.g. +91...). */
    suspend fun sendPhoneNumber(phone: String)

    /** Send the verification code Telegram sent to the account. */
    suspend fun sendCode(code: String)

    /** Send the two-step verification password, when TDLib asks for it. */
    suspend fun sendPassword(password: String)

    /** Log out of Telegram. Saved groups stay; sign-in is needed again. */
    suspend fun logout()

    /** Resolve a t.me link / @username / username to a group or channel. */
    suspend fun resolveChat(linkOrUsername: String): GroupItem

    /** Load one page of video messages, newest first. */
    suspend fun loadVideos(chatId: Long, fromMessageId: Long, limit: Int): VideoPage

    /**
     * Server-side search of video messages in one chat (TDLib
     * SearchChatMessages + SearchMessagesFilterVideo). Returns newest
     * first; empty when nothing matches.
     */
    suspend fun searchVideos(chatId: Long, query: String, limit: Int): VideoPage

    /** Download a thumbnail file synchronously; returns its local path. */
    suspend fun downloadThumbnail(fileId: Int): String?

    /** Info about a video file, starting its download if needed. */
    suspend fun downloadFileInfo(fileId: Int): VideoFileInfo?

    /** Synchronously download the requested byte range of a video file. */
    suspend fun ensureRange(fileId: Int, offset: Long, length: Long): VideoFileInfo?

    /** Release the TDLib client. */
    fun close()
}
