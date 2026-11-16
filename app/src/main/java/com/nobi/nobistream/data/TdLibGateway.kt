package com.nobi.nobistream.data

import android.content.Context
import android.os.Build
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.QualityParser
import com.nobi.nobistream.core.TelegramUser
import com.nobi.nobistream.core.VideoItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resumeWithException

/**
 * [TelegramGateway] on top of TDLib's official Java bindings
 * (org.drinkless.tdlib.Client / TdApi, TDLib 1.8.64, prebuilt AAR).
 *
 * The login flow is driven by TDLib authorization-state updates, exactly as
 * the official TDLib example does: WaitTdlibParameters -> send parameters,
 * WaitPhoneNumber -> UI asks for the phone number, WaitCode -> UI asks for
 * the code, WaitPassword -> UI asks for the 2FA password, Ready -> signed in.
 */
class TdLibGateway(context: Context) : TelegramGateway {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val authStateFlow = MutableStateFlow(AuthState.UNKNOWN)
    override val authState: StateFlow<AuthState> = authStateFlow.asStateFlow()

    private val userFlow = MutableStateFlow<TelegramUser?>(null)
    override val currentUser: StateFlow<TelegramUser?> = userFlow.asStateFlow()

    private val fileStates = ConcurrentHashMap<Int, TdApi.File>()
    private val paramsSent = AtomicBoolean(false)

    @Volatile
    private var client: Client? = null

    @Volatile
    private var pendingApiId: Int = 0

    @Volatile
    private var pendingApiHash: String = ""

    override val isStarted: Boolean
        get() = client != null

    private val updateHandler = Client.ResultHandler { obj -> onUpdate(obj) }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuthState(obj.authorizationState)
            is TdApi.UpdateFile -> fileStates[obj.file.id] = obj.file
        }
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                if (paramsSent.compareAndSet(false, true)) {
                    sendParams()
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                authStateFlow.value = AuthState.NEED_PHONE
            is TdApi.AuthorizationStateWaitCode ->
                authStateFlow.value = AuthState.NEED_CODE
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                authStateFlow.value = AuthState.NEED_CODE
            is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode ->
                authStateFlow.value = AuthState.NEED_CODE
            is TdApi.AuthorizationStateWaitPassword ->
                authStateFlow.value = AuthState.NEED_PASSWORD
            is TdApi.AuthorizationStateWaitRegistration ->
                authStateFlow.value = AuthState.NEED_PHONE
            is TdApi.AuthorizationStateReady -> {
                authStateFlow.value = AuthState.READY
                scope.launch { refreshMe() }
            }
            is TdApi.AuthorizationStateLoggingOut ->
                authStateFlow.value = AuthState.LOGGED_OUT
            is TdApi.AuthorizationStateClosed -> {
                authStateFlow.value = AuthState.LOGGED_OUT
                userFlow.value = null
            }
            else -> Unit
        }
    }

    private fun sendParams() {
        val baseDir = File(appContext.filesDir, "tdlib")
        val dbDir = File(baseDir, "db").apply { mkdirs() }
        val filesDir = File(baseDir, "files").apply { mkdirs() }
        val params = TdApi.SetTdlibParameters(
            false,
            dbDir.absolutePath,
            filesDir.absolutePath,
            ByteArray(0),
            true,
            true,
            true,
            false,
            pendingApiId,
            pendingApiHash,
            "en",
            "Android",
            Build.VERSION.RELEASE ?: "13",
            "1.0.0"
        )
        client?.send(
            params,
            Client.ResultHandler { },
            Client.ExceptionHandler { paramsSent.set(false) }
        )
    }

    override suspend fun start(apiId: Int, apiHash: String) {
        if (client != null) return
        pendingApiId = apiId
        pendingApiHash = apiHash
        paramsSent.set(false)
        withContext(Dispatchers.IO) {
            try {
                System.loadLibrary("tdjni")
            } catch (_: Throwable) {
                // Client loads the library itself when present; ignore.
            }
            try {
                Client.execute(TdApi.SetLogVerbosityLevel(1))
            } catch (_: Throwable) {
                // Logging is best-effort.
            }
            val created = Client.create(
                updateHandler,
                Client.ExceptionHandler { },
                Client.ExceptionHandler { }
            )
            client = created
        }
        // Wait until the handshake reaches a login step or Ready. If the
        // parameters were rejected (bad credentials), TDLib stays in
        // WaitTdlibParameters and this times out.
        val reached = withTimeoutOrNull(30_000L) {
            authStateFlow.first { it != AuthState.UNKNOWN }
        }
        if (reached == null) {
            throw IllegalStateException(
                "Could not connect to Telegram. Check the API credentials and try again."
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : TdApi.Object> send(fn: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val activeClient = client
            if (activeClient == null) {
                cont.resumeWithException(IllegalStateException("Telegram client is not started"))
                return@suspendCancellableCoroutine
            }
            activeClient.send(
                fn,
                Client.ResultHandler { result ->
                    if (!cont.isActive) return@ResultHandler
                    if (result is TdApi.Error) {
                        cont.resumeWithException(
                            IllegalStateException("Telegram error ${result.code}: ${result.message}")
                        )
                    } else {
                        try {
                            cont.resume(result as T)
                        } catch (e: ClassCastException) {
                            cont.resumeWithException(e)
                        }
                    }
                },
                Client.ExceptionHandler { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
            )
        }

    private suspend fun refreshMe() {
        try {
            val me = send(TdApi.GetMe())
            userFlow.value = TelegramUser(me.firstName, null)
        } catch (_: Exception) {
            // Not fatal; the name is only shown in the UI.
        }
    }

    override suspend fun sendPhoneNumber(phone: String) {
        val clean = phone.trim().replace(" ", "").replace("-", "")
        val full = if (clean.startsWith("+")) clean else "+$clean"
        send(
            TdApi.SetAuthenticationPhoneNumber(
                full,
                TdApi.PhoneNumberAuthenticationSettings(
                    false, false, false, false, false, null, null
                )
            )
        )
    }

    override suspend fun sendCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code.trim()))
    }

    override suspend fun sendPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    override suspend fun logout() {
        try {
            send(TdApi.LogOut())
        } finally {
            userFlow.value = null
        }
    }

    override suspend fun resolveChat(linkOrUsername: String): GroupItem {
        val username = parseUsername(linkOrUsername)
        val chat = send(TdApi.SearchPublicChat(username))
        var photoPath: String? = null
        val smallPhotoId = chat.photo?.small?.id
        if (smallPhotoId != null) {
            try {
                photoPath = downloadThumbnail(smallPhotoId)
            } catch (_: Exception) {
                photoPath = null
            }
        }
        return GroupItem(
            chatId = chat.id,
            username = username,
            title = chat.title,
            photoLocalPath = photoPath,
            videoCount = 0,
            lastUpdatedEpochSec = System.currentTimeMillis() / 1000L
        )
    }

    override suspend fun loadVideos(chatId: Long, fromMessageId: Long, limit: Int): VideoPage {
        val safeLimit = limit.coerceIn(1, 100)
        val result = send(
            TdApi.GetChatHistory(chatId, fromMessageId, 0, safeLimit, false)
        )
        // With offset 0 the fromMessageId message itself is included again.
        val messages = result.messages.filter { it.id != fromMessageId }
        val videos = messages.mapNotNull { mapMessage(it) }
        return VideoPage(videos = videos, totalCount = result.totalCount)
    }

    private fun mapMessage(message: TdApi.Message): VideoItem? {
        return when (val content = message.content) {
            is TdApi.MessageVideo -> {
                val video = content.video
                val file = video.video
                val caption = content.caption?.text ?: ""
                VideoItem(
                    messageId = message.id,
                    chatId = message.chatId,
                    fileId = file.id,
                    remoteFileId = file.remote.id,
                    title = QualityParser.extractTitle(caption, message.id),
                    caption = caption,
                    quality = QualityParser.parseQuality(caption),
                    durationSec = video.duration,
                    sizeBytes = if (file.size > 0L) file.size else file.expectedSize,
                    dateEpochSec = message.date.toLong(),
                    thumbFileId = video.thumbnail?.photo?.id,
                    thumbLocalPath = null
                )
            }
            is TdApi.MessageDocument -> {
                val document = content.document
                if (!document.mimeType.startsWith("video")) return null
                val file = document.document
                val caption = content.caption?.text ?: ""
                VideoItem(
                    messageId = message.id,
                    chatId = message.chatId,
                    fileId = file.id,
                    remoteFileId = file.remote.id,
                    title = QualityParser.extractTitle(caption, message.id),
                    caption = caption,
                    quality = QualityParser.parseQuality(caption),
                    durationSec = 0,
                    sizeBytes = if (file.size > 0L) file.size else file.expectedSize,
                    dateEpochSec = message.date.toLong(),
                    thumbFileId = document.thumbnail?.photo?.id,
                    thumbLocalPath = null
                )
            }
            else -> null
        }
    }

    override suspend fun downloadThumbnail(fileId: Int): String? {
        val file = send(TdApi.DownloadFile(fileId, 1, 0, 0, true))
        fileStates[file.id] = file
        val path = file.local.path
        return if (path.isNotEmpty() && File(path).exists()) path else null
    }

    override suspend fun downloadFileInfo(fileId: Int): VideoFileInfo? {
        fileStates[fileId]?.let { cached ->
            toInfo(cached)?.let { return it }
        }
        val file = send(TdApi.DownloadFile(fileId, 1, 0, 0, false))
        fileStates[file.id] = file
        return toInfo(file)
    }

    override suspend fun ensureRange(fileId: Int, offset: Long, length: Long): VideoFileInfo? {
        val file = send(TdApi.DownloadFile(fileId, 32, offset, length, true))
        fileStates[file.id] = file
        return toInfo(file)
    }

    private fun toInfo(file: TdApi.File): VideoFileInfo? {
        val path = file.local.path
        if (path.isEmpty()) return null
        return VideoFileInfo(
            fileId = file.id,
            sizeBytes = if (file.size > 0L) file.size else file.expectedSize,
            localPath = path,
            downloadedBytes = file.local.downloadedSize,
            isComplete = file.local.isDownloadingCompleted
        )
    }

    override fun close() {
        scope.launch {
            try {
                send(TdApi.Close())
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** "https://t.me/name/123", "@name", "name" -> "name". */
        fun parseUsername(input: String): String {
            var text = input.trim()
            text = text.removePrefix("https://").removePrefix("http://")
            text = text.removePrefix("t.me/").removePrefix("telegram.me/")
            if (text.startsWith("s/")) text = text.removePrefix("s/")
            if (text.startsWith("c/")) {
                throw IllegalArgumentException(
                    "Private group links are not supported. Use a public group or channel link."
                )
            }
            text = text.removePrefix("@")
            text = text.substringBefore("/").substringBefore("?").trim()
            if (text.isEmpty() || text.length < 3) {
                throw IllegalArgumentException("Enter a valid Telegram group or channel link.")
            }
            return text
        }
    }
}
