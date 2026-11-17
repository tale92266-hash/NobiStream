package com.nobi.nobistream.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ActivityPlayerBinding
import com.nobi.nobistream.databinding.LayoutBottomSheetPlayerBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.playback.TdlibDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3 video player: fullscreen landscape, all controls ON the video
 * (custom exo_player_control_view.xml, Material Symbols icons), MX-style
 * extras — picture-in-picture, aspect-ratio cycle, controls lock,
 * horizontal swipe to seek — and a real buffering overlay (spinner +
 * buffered %) so buffering is never confused with a stuck frame.
 *
 * v3 changes are presentation only: secondary controls (speed, autoplay,
 * aspect) moved from a popup menu into a bottom sheet; playback failures
 * show an error state with Retry instead of a toast; the bottom row shows
 * the episode counter. The engine (TDLib streaming) is untouched.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_MESSAGE_ID = "message_id"

        private const val CONTROLLER_TIMEOUT_MS = 3000

        private const val SCROLL_NONE = 0
        private const val SCROLL_VERTICAL = 1
        private const val SCROLL_HORIZONTAL = 2
    }

    private lateinit var binding: ActivityPlayerBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private var player: ExoPlayer? = null
    private var playlist: List<VideoItem> = emptyList()
    private var chatId: Long = 0L
    private var index: Int = 0
    private var saveJob: Job? = null
    private var autoplay: Boolean = true
    private var currentSpeedIndex = 2
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var audioManager: AudioManager? = null

    private var isFullscreen = true
    private var locked = false
    private var aspectIndex = 0
    private val aspectModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
    )
    private val aspectLabels = arrayOf("Fit", "Fill", "Stretch")

    private var scrollMode = SCROLL_NONE
    private var scrollStartPosition = 0L
    private var pendingSeekTarget = -1L
    private var scrollTotalDx = 0f

    /**
     * v4-fb3: resume dialog is ARMED here and only SHOWN once the
     * player reaches STATE_READY (see playerListener). Arming it in
     * playAt() showed the dialog over the error screen when the
     * source failed, and on every Next/Previous. Cleared on error
     * and on every new playAt().
     */
    private var pendingResumeSec: Long? = null
    private var pendingResumeIndex: Int = -1

    private var enterTransitionStarted = false

    private val feedbackHandler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable { binding.gestureFeedback.visibility = View.GONE }
    private val bufferHandler = Handler(Looper.getMainLooper())
    private val bufferUpdater = object : Runnable {
        override fun run() {
            updateBufferingPercent()
            bufferHandler.postDelayed(this, 500L)
        }
    }

    // Controller views (found inside PlayerView after inflation).
    private lateinit var ctrlPlayPause: ImageButton
    private lateinit var ctrlFullscreen: ImageButton
    private lateinit var ctrlLock: ImageButton
    private lateinit var ctrlTitle: TextView
    private lateinit var ctrlSpeedLabel: TextView
    private lateinit var ctrlCounter: TextView

    private var optionsSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
        playlist = PlayerPlaylist.items
        index = playlist.indexOfFirst { it.messageId == messageId }.takeIf { it >= 0 } ?: 0

        binding.playerView.transitionName = "video_thumb_$messageId"
        postponeEnterTransition()
        feedbackHandler.postDelayed({ startPostponedEnterTransitionOnce() }, 1000L)

        // Controller views first: setFullscreen() touches the fullscreen icon.
        findControllerViews()
        setFullscreen(true)
        binding.playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS)
        // v4: PlayerView's own tap-to-toggle is OFF — our GestureDetector
        // below is the ONLY tap handler (see setupGestures). With both
        // active, one tap toggled the controller twice (show then hide)
        // and the tap looked dead. The 3s auto-hide timeout still applies.
        binding.playerView.setControllerHideOnTouch(false)
        wireControllerButtons()
        binding.lockOverlayButton.setOnClickListener { setLocked(false) }
        binding.playerError.apply {
            setTitle(getString(R.string.player_error_title))
            setBody(getString(R.string.player_error_body))
            // v4-fb3: Retry genuinely re-requests — playAt() resolves
            // a fresh file id and builds a new MediaItem/data source,
            // and resumes silently (no dialog on a retry).
            setRetry { playAt(index, askResume = false) }
            setDismiss { finish() }
        }

        lifecycleScope.launch {
            repository.settingsStore.autoplay.collect { value ->
                autoplay = value
            }
        }
        setupGestures()
        bufferHandler.post(bufferUpdater)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    locked -> setLocked(false)
                    isFullscreen -> setFullscreen(false)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (playlist.isEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val page = runCatching { repository.loadVideos(chatId, 0L, 50) }.getOrNull()
                withContext(Dispatchers.Main) {
                    playlist = page?.videos ?: emptyList()
                    if (playlist.isEmpty()) {
                        Toast.makeText(
                            this@PlayerActivity,
                            getString(R.string.player_no_video),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                        return@withContext
                    }
                    index = playlist.indexOfFirst { it.messageId == messageId }
                        .takeIf { it >= 0 } ?: 0
                    startPlayer()
                }
            }
        } else {
            startPlayer()
            // v4-fb3: Continue Watching opens with a Room-only slice —
            // widen it to the channel's full filtered list in the
            // background so Next/Previous walk the whole channel.
            maybeExpandContinuePlaylist()
        }
    }

    private fun findControllerViews() {
        val view = binding.playerView
        ctrlPlayPause = view.findViewById(R.id.ctrlPlayPause)
        ctrlFullscreen = view.findViewById(R.id.ctrlFullscreen)
        ctrlLock = view.findViewById(R.id.ctrlLock)
        ctrlTitle = view.findViewById(R.id.ctrlTitle)
        ctrlSpeedLabel = view.findViewById(R.id.ctrlSpeedLabel)
        ctrlCounter = view.findViewById(R.id.ctrlCounter)
    }

    private fun wireControllerButtons() {
        val view = binding.playerView
        ctrlPlayPause.setOnClickListener { togglePlayPause() }
        // v4-fb3: Next/Previous never ask — silent seek to the saved
        // position (see playAt's askResume flag).
        view.findViewById<View>(R.id.ctrlPrev).setOnClickListener { playAt(index - 1, askResume = false) }
        view.findViewById<View>(R.id.ctrlNext).setOnClickListener { playAt(index + 1, askResume = false) }
        view.findViewById<View>(R.id.ctrlRew).setOnClickListener { seekBy(-10_000L) }
        view.findViewById<View>(R.id.ctrlFfwd).setOnClickListener { seekBy(10_000L) }
        view.findViewById<View>(R.id.ctrlBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        ctrlFullscreen.setOnClickListener { setFullscreen(!isFullscreen) }
        view.findViewById<View>(R.id.ctrlAspect).setOnClickListener { cycleAspect() }
        view.findViewById<View>(R.id.ctrlPip).setOnClickListener { enterPip() }
        ctrlLock.setOnClickListener { setLocked(true) }
        view.findViewById<View>(R.id.ctrlOptions).setOnClickListener { showOptionsSheet() }
        ctrlSpeedLabel.setOnClickListener { showOptionsSheet() }
    }

    /** Fullscreen = sensor landscape + immersive (system bars hidden). */
    private fun setFullscreen(fullscreen: Boolean) {
        isFullscreen = fullscreen
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrlFullscreen.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            controller.show(WindowInsetsCompat.Type.systemBars())
            ctrlFullscreen.setImageResource(R.drawable.ic_fullscreen)
        }
    }

    private fun setLocked(value: Boolean) {
        locked = value
        if (value) {
            binding.playerView.hideController()
            binding.lockOverlayButton.visibility = View.VISIBLE
            ctrlLock.setImageResource(R.drawable.ic_lock)
            Toast.makeText(this, getString(R.string.player_locked), Toast.LENGTH_SHORT).show()
        } else {
            binding.lockOverlayButton.visibility = View.GONE
            ctrlLock.setImageResource(R.drawable.ic_lock_open)
            binding.playerView.showController()
            Toast.makeText(this, getString(R.string.player_unlocked), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleAspect() {
        aspectIndex = (aspectIndex + 1) % aspectModes.size
        binding.playerView.resizeMode = aspectModes[aspectIndex]
        showFeedback(R.drawable.ic_aspect_ratio, "Aspect: ${aspectLabels[aspectIndex]}")
    }

    private fun setAspect(index: Int) {
        aspectIndex = index.coerceIn(aspectModes.indices)
        binding.playerView.resizeMode = aspectModes[aspectIndex]
        showFeedback(R.drawable.ic_aspect_ratio, "Aspect: ${aspectLabels[aspectIndex]}")
    }

    private fun enterPip() {
        val exo = player
        if (exo == null || !exo.isPlaying) {
            Toast.makeText(this, getString(R.string.player_pip_needs_playing), Toast.LENGTH_SHORT).show()
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && !locked) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.playerView.hideController()
            binding.bufferingOverlay.visibility = View.GONE
            binding.gestureFeedback.visibility = View.GONE
            binding.playerError.visibility = View.GONE
            binding.lockOverlayButton.visibility = View.GONE
        } else if (locked) {
            binding.lockOverlayButton.visibility = View.VISIBLE
        }
    }

    /**
     * Player options bottom sheet (pixel spec §07): rows 56dp, speed chips,
     * autoplay switch, aspect chips. While the sheet is open the controls
     * must NOT auto-hide (timeout 0 = shown indefinitely); the 3s timeout
     * is restored when the sheet dismisses.
     */
    private fun showOptionsSheet() {
        if (optionsSheet?.isShowing == true) return
        val sheetBinding = LayoutBottomSheetPlayerBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        optionsSheet = dialog

        speeds.forEachIndexed { i, speed ->
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_option_chip, sheetBinding.speedChips, false) as Chip
            chip.text = "${speed}x"
            chip.isChecked = i == currentSpeedIndex
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    setSpeed(i)
                    dialog.dismiss()
                }
            }
            sheetBinding.speedChips.addView(chip)
        }
        aspectLabels.forEachIndexed { i, label ->
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_option_chip, sheetBinding.aspectChips, false) as Chip
            chip.text = label
            chip.isChecked = i == aspectIndex
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    setAspect(i)
                    dialog.dismiss()
                }
            }
            sheetBinding.aspectChips.addView(chip)
        }
        sheetBinding.sheetAutoplaySwitch.isChecked = autoplay
        sheetBinding.sheetAutoplaySwitch.setOnCheckedChangeListener { _, checked ->
            autoplay = checked
            lifecycleScope.launch {
                repository.settingsStore.setAutoplay(checked)
            }
        }

        binding.playerView.setControllerShowTimeoutMs(0)
        binding.playerView.showController()
        dialog.setContentView(sheetBinding.root)
        dialog.setOnDismissListener {
            if (optionsSheet == dialog) optionsSheet = null
            binding.playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS)
            binding.playerView.showController()
        }
        dialog.show()
    }

    private fun setSpeed(index: Int) {
        currentSpeedIndex = index.coerceIn(speeds.indices)
        val speed = speeds[currentSpeedIndex]
        player?.playbackParameters = PlaybackParameters(speed)
        ctrlSpeedLabel.text = "${speed}x"
        showFeedback(R.drawable.ic_speed, "Speed ${speed}x")
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        updatePlayPauseIcon()
    }

    private fun updatePlayPauseIcon() {
        ctrlPlayPause.setImageResource(
            if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, exo.duration.coerceAtLeast(0L))
        exo.seekTo(target)
        val label = if (deltaMs < 0) "-${-deltaMs / 1000}s" else "+${deltaMs / 1000}s"
        val icon = if (deltaMs < 0) R.drawable.ic_replay_10 else R.drawable.ic_forward_10
        showFeedback(icon, label)
    }

    private fun startPlayer() {
        val factory = TdlibDataSource.Factory(locator.gateway)
        val mediaSourceFactory = ProgressiveMediaSource.Factory(factory)
        // v4: bigger buffers for high-bitrate 1080p+ over MTProto,
        // which is far slower than a CDN — start only after 5s is
        // buffered, keep up to 60s prefetched, and rebuffer with a
        // 10s runway. (ExoPlayer defaults: 50s / 50s / 2.5s / 5s —
        // the 2.5s start was rebuffering almost immediately on 1080p.)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 5_000, 10_000)
            .build()
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                binding.playerView.player = exo
                exo.addListener(playerListener)
            }
        playAt(index, firstLoad = true)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> binding.bufferingOverlay.visibility = View.VISIBLE
                Player.STATE_READY -> {
                    binding.bufferingOverlay.visibility = View.GONE
                    binding.playerError.visibility = View.GONE
                    startPostponedEnterTransitionOnce()
                    updatePlayPauseIcon()
                    // v4-fb3: the resume dialog fires ONLY here — once
                    // the source is actually READY. Showing it in
                    // playAt() put it over the error screen when the
                    // download failed, before anything could play.
                    pendingResumeSec?.let { saved ->
                        pendingResumeSec = null
                        if (pendingResumeIndex == index) showResumeDialog(saved)
                    }
                }
                Player.STATE_ENDED -> {
                    binding.bufferingOverlay.visibility = View.GONE
                    savePositionNow()
                    if (autoplay) playAt(index + 1, askResume = false)
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            if (isPlaying) startSaveTicker() else stopSaveTicker()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // v4-fb3: a failed source must never get a resume dialog
            // over the error screen — and log the REAL error (the old
            // code logged nothing, so the failure was un-diagnosable).
            pendingResumeSec = null
            Log.e(
                "PlayerActivity",
                "Playback failed for ${playlist.getOrNull(index)?.title}",
                error
            )
            binding.bufferingOverlay.visibility = View.GONE
            binding.playerError.visibility = View.VISIBLE
        }
    }

    private fun startPostponedEnterTransitionOnce() {
        if (!enterTransitionStarted) {
            enterTransitionStarted = true
            startPostponedEnterTransition()
        }
    }

    private fun updateBufferingPercent() {
        val exo = player ?: return
        if (binding.bufferingOverlay.visibility != View.VISIBLE) return
        val duration = exo.duration
        if (duration > 0) {
            val percent = (exo.bufferedPosition * 100L / duration).coerceIn(0L, 100L)
            binding.bufferingPercent.text = getString(R.string.player_buffered_percent, percent)
        } else {
            binding.bufferingPercent.text = ""
        }
    }

    /**
     * v4-fb3: plays playlist[newIndex].
     *
     * - The Room item's fileId is re-resolved to a FRESH local id in
     *   this TDLib session before the MediaItem is built (see
     *   NobiRepository.resolveVideoItem) — the stored id is dead
     *   after a restart, which is why open / Retry / Next / Previous
     *   ALL failed on Ep 361 with 00:00/00:00.
     * - [askResume] is true ONLY for opening from a list. Next /
     *   Previous / autoplay / Retry pass false and seek silently to
     *   the saved position — the dialog never appears there.
     * - When askResume is true, the dialog is only ARMED here and
     *   shown on STATE_READY, never over an error screen.
     */
    private fun playAt(newIndex: Int, firstLoad: Boolean = false, askResume: Boolean = true) {
        if (newIndex < 0 || newIndex >= playlist.size) {
            if (!firstLoad) {
                Toast.makeText(
                    this,
                    if (newIndex < 0) getString(R.string.player_first_video)
                    else getString(R.string.player_last_video),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        savePositionNow()
        index = newIndex
        val item = playlist[index]
        ctrlTitle.text = item.title
        ctrlCounter.text = if (playlist.size > 1) "${index + 1} / ${playlist.size}" else ""
        binding.playerError.visibility = View.GONE
        pendingResumeSec = null
        val exo = player ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val resolved = runCatching { repository.resolveVideoItem(item) }.getOrElse { item }
            val saved = repository.getPositionSec(resolved.chatId, resolved.messageId)
            withContext(Dispatchers.Main) {
                // The user may have moved on while resolving — never
                // hijack the newer episode with the older one.
                if (index != newIndex || player == null) return@withContext
                exo.setMediaItem(MediaItem.fromUri("tdlib://${resolved.fileId}"))
                exo.prepare()
                val resumable = saved > 5L && resolved.durationSec > 0 &&
                    saved < resolved.durationSec - 10L
                when {
                    !resumable -> exo.play()
                    askResume -> {
                        pendingResumeSec = saved
                        pendingResumeIndex = newIndex
                    }
                    else -> {
                        exo.seekTo(saved * 1000L)
                        exo.play()
                    }
                }
            }
        }
    }

    /** Resume / Restart dialog — only from STATE_READY, only for list opens. */
    private fun showResumeDialog(savedSec: Long) {
        val exo = player ?: return
        AlertDialog.Builder(this)
            .setMessage(
                getString(
                    R.string.player_resume_from,
                    FormatUtils.formatDuration(savedSec.toInt())
                )
            )
            .setPositiveButton(R.string.player_resume) { _, _ ->
                exo.seekTo(savedSec * 1000L)
                exo.play()
            }
            .setNegativeButton(R.string.player_restart) { _, _ ->
                exo.seekTo(0L)
                exo.play()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * v4-fb3: when opened from Continue Watching, the playlist is
     * only the Room slice (max 10 — "2 / 2" for Sam). Page the
     * channel's history in the background (up to 5 pages of 50) with
     * the channel's last-used filter applied, and swap the playlist
     * in place once the current episode is found in it — preserving
     * the index and the counter. Playback starts immediately on the
     * Continue slice; the swap only widens Next/Previous. If the
     * current episode is never found (filter excludes it, or deeper
     * than 250 messages), the original playlist stays — never a
     * worse state.
     */
    private fun maybeExpandContinuePlaylist() {
        if (!PlayerPlaylist.fromContinue || chatId == 0L) return
        val currentId = playlist.getOrNull(index)?.messageId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val lastChat = runCatching { repository.settingsStore.lastChatId.first() }
                .getOrNull() ?: 0L
            val filter = if (lastChat == chatId) {
                runCatching { repository.settingsStore.lastFilter.first() }.getOrNull() ?: "All"
            } else {
                "All"
            }
            val collected = mutableListOf<VideoItem>()
            var fromId = 0L
            var found = false
            var pages = 0
            while (!found && pages < 5) {
                pages++
                val page = runCatching { repository.loadVideos(chatId, fromId, 50) }
                    .getOrNull() ?: break
                val videos = if (filter != "All") {
                    page.videos.filter { it.quality.label == filter }
                } else {
                    page.videos
                }
                collected.addAll(videos)
                if (videos.any { it.messageId == currentId }) found = true
                if (page.rawCount < 50 || page.lastMessageId <= 0L) break
                fromId = page.lastMessageId
            }
            if (!found) return@launch
            withContext(Dispatchers.Main) {
                val newIndex = collected.indexOfFirst { it.messageId == currentId }
                if (newIndex < 0) return@withContext
                // Only swap while still on the same episode — if the
                // user already moved on, the slice stays correct.
                if (playlist.getOrNull(index)?.messageId != currentId) return@withContext
                playlist = collected
                index = newIndex
                ctrlCounter.text =
                    if (playlist.size > 1) "${index + 1} / ${playlist.size}" else ""
            }
        }
    }

    private fun startSaveTicker() {
        stopSaveTicker()
        saveJob = lifecycleScope.launch {
            while (isActive) {
                delay(5000L)
                savePositionNow()
            }
        }
    }

    private fun stopSaveTicker() {
        saveJob?.cancel()
        saveJob = null
    }

    private fun savePositionNow() {
        val exo = player ?: return
        if (index !in playlist.indices) return
        val item = playlist[index]
        val positionSec = exo.currentPosition / 1000L
        if (positionSec <= 0L) return
        lifecycleScope.launch(Dispatchers.IO) {
            repository.savePosition(item, positionSec)
        }
    }

    private fun setupGestures() {
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (locked) return true
                    val view = binding.playerView
                    if (view.isControllerFullyVisible) view.hideController() else view.showController()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (locked) return true
                    val width = binding.playerView.width
                    if (e.x < width / 2f) seekBy(-10_000L) else seekBy(10_000L)
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (locked) return true
                    val exo = player ?: return true
                    if (scrollMode == SCROLL_NONE) {
                        scrollMode = if (kotlin.math.abs(distanceX) > kotlin.math.abs(distanceY) * 1.5f) {
                            scrollStartPosition = exo.currentPosition
                            pendingSeekTarget = scrollStartPosition
                            scrollTotalDx = 0f
                            SCROLL_HORIZONTAL
                        } else {
                            SCROLL_VERTICAL
                        }
                    }
                    if (scrollMode == SCROLL_HORIZONTAL) {
                        val view = binding.playerView
                        scrollTotalDx -= distanceX
                        val duration = exo.duration.coerceAtLeast(0L)
                        val delta = if (view.width > 0 && duration > 0) {
                            (scrollTotalDx / view.width.toFloat() * duration).toLong()
                        } else {
                            0L
                        }
                        pendingSeekTarget = (scrollStartPosition + delta).coerceIn(0L, duration)
                        val icon = if (delta < 0) R.drawable.ic_replay_10 else R.drawable.ic_forward_10
                        showFeedback(
                            icon,
                            "Seek to ${FormatUtils.formatDuration((pendingSeekTarget / 1000L).toInt())}"
                        )
                    } else {
                        val view = binding.playerView
                        val x = e2.x
                        if (x < view.width / 3f) {
                            adjustBrightness(distanceY)
                        } else if (x > view.width * 2f / 3f) {
                            adjustVolume(distanceY)
                        }
                    }
                    return true
                }
            }
        )
        binding.playerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    scrollMode = SCROLL_NONE
                    pendingSeekTarget = -1L
                    scrollTotalDx = 0f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (scrollMode == SCROLL_HORIZONTAL && pendingSeekTarget >= 0L) {
                        player?.seekTo(pendingSeekTarget)
                    }
                    scrollMode = SCROLL_NONE
                    pendingSeekTarget = -1L
                    scrollTotalDx = 0f
                }
            }
            detector.onTouchEvent(event)
            // v4: consume EVERY surface touch. The old code returned
            // `gestureHandled`, which was still false on DOWN/UP
            // (onSingleTapConfirmed only fires ~300ms later, once a
            // double-tap is ruled out), so PlayerView's own tap-toggle
            // ALSO ran — one tap toggled the controller twice and the
            // tap looked dead. Buttons are child views and still get
            // their own taps first; only the bare surface reaches here.
            true
        }
    }

    private fun adjustBrightness(distanceY: Float) {
        val attrs = window.attributes
        val current = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
        val height = binding.playerView.height.takeIf { it > 0 } ?: 1
        val next = (current + distanceY / height.toFloat() * 0.5f).coerceIn(0.01f, 1.0f)
        attrs.screenBrightness = next
        window.attributes = attrs
        showFeedback(R.drawable.ic_brightness, "Brightness ${(next * 100).toInt()}%")
    }

    private fun adjustVolume(distanceY: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val delta = when {
            distanceY > 30f -> 1
            distanceY < -30f -> -1
            else -> return
        }
        val next = (current + delta).coerceIn(0, max)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        showFeedback(R.drawable.ic_volume, "Volume $next / $max")
    }

    private fun showFeedback(iconRes: Int, text: String) {
        binding.feedbackIcon.setImageResource(iconRes)
        binding.feedbackText.text = text
        binding.gestureFeedback.visibility = View.VISIBLE
        feedbackHandler.removeCallbacks(hideFeedback)
        feedbackHandler.postDelayed(hideFeedback, 900L)
    }

    override fun onPause() {
        super.onPause()
        savePositionNow()
        // Don't pause when entering picture-in-picture — the video keeps playing there.
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        optionsSheet?.dismiss()
        optionsSheet = null
        bufferHandler.removeCallbacks(bufferUpdater)
        feedbackHandler.removeCallbacks(hideFeedback)
        savePositionNow()
        stopSaveTicker()
        player?.release()
        player = null
    }
}
