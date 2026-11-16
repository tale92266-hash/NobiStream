package com.nobi.nobistream.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.PopupMenu
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ActivityPlayerBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.playback.TdlibDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2 video player: fullscreen landscape, all controls ON the video
 * (custom exo_player_control_view.xml, Material Symbols icons), MX-style
 * extras — picture-in-picture, aspect-ratio cycle, controls lock,
 * horizontal swipe to seek — and a real buffering overlay (spinner +
 * buffered %) so buffering is never confused with a stuck frame.
 *
 * The data source is unchanged from v1 (TDLib streaming); only the
 * presentation changed.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_MESSAGE_ID = "message_id"

        private const val MENU_AUTOPLAY = 1
        private const val MENU_SPEED_BASE = 100
        private const val GROUP_SPEED = 2

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
        binding.playerView.setControllerShowTimeoutMs(3000)
        wireControllerButtons()
        binding.lockOverlayButton.setOnClickListener { setLocked(false) }

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
                            getString(R.string.player_error),
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
        }
    }

    private fun findControllerViews() {
        val view = binding.playerView
        ctrlPlayPause = view.findViewById(R.id.ctrlPlayPause)
        ctrlFullscreen = view.findViewById(R.id.ctrlFullscreen)
        ctrlLock = view.findViewById(R.id.ctrlLock)
        ctrlTitle = view.findViewById(R.id.ctrlTitle)
        ctrlSpeedLabel = view.findViewById(R.id.ctrlSpeedLabel)
    }

    private fun wireControllerButtons() {
        val view = binding.playerView
        ctrlPlayPause.setOnClickListener { togglePlayPause() }
        view.findViewById<View>(R.id.ctrlPrev).setOnClickListener { playAt(index - 1) }
        view.findViewById<View>(R.id.ctrlNext).setOnClickListener { playAt(index + 1) }
        view.findViewById<View>(R.id.ctrlRew).setOnClickListener { seekBy(-10_000L) }
        view.findViewById<View>(R.id.ctrlFfwd).setOnClickListener { seekBy(10_000L) }
        view.findViewById<View>(R.id.ctrlBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        ctrlFullscreen.setOnClickListener { setFullscreen(!isFullscreen) }
        view.findViewById<View>(R.id.ctrlAspect).setOnClickListener { cycleAspect() }
        view.findViewById<View>(R.id.ctrlPip).setOnClickListener { enterPip() }
        ctrlLock.setOnClickListener { setLocked(true) }
        view.findViewById<View>(R.id.ctrlOptions).setOnClickListener { showOptionsMenu(it) }
        ctrlSpeedLabel.setOnClickListener { showOptionsMenu(it) }
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
            binding.lockOverlayButton.visibility = View.GONE
        } else if (locked) {
            binding.lockOverlayButton.visibility = View.VISIBLE
        }
    }

    private fun showOptionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_AUTOPLAY, 0, getString(R.string.player_autoplay)).apply {
            isCheckable = true
            isChecked = autoplay
        }
        speeds.forEachIndexed { i, speed ->
            popup.menu.add(GROUP_SPEED, MENU_SPEED_BASE + i, i + 1, "${speed}x").apply {
                isCheckable = true
                isChecked = i == currentSpeedIndex
            }
        }
        popup.menu.setGroupCheckable(GROUP_SPEED, true, true)
        popup.setOnMenuItemClickListener { item ->
            when {
                item.itemId == MENU_AUTOPLAY -> {
                    autoplay = !autoplay
                    lifecycleScope.launch {
                        repository.settingsStore.setAutoplay(autoplay)
                    }
                    true
                }
                item.groupId == GROUP_SPEED -> {
                    setSpeed(item.itemId - MENU_SPEED_BASE)
                    true
                }
                else -> false
            }
        }
        popup.show()
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
        player = ExoPlayer.Builder(this)
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
                    startPostponedEnterTransitionOnce()
                    updatePlayPauseIcon()
                }
                Player.STATE_ENDED -> {
                    binding.bufferingOverlay.visibility = View.GONE
                    savePositionNow()
                    if (autoplay) playAt(index + 1)
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            if (isPlaying) startSaveTicker() else stopSaveTicker()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            binding.bufferingOverlay.visibility = View.GONE
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.player_error),
                Toast.LENGTH_LONG
            ).show()
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

    private fun playAt(newIndex: Int, firstLoad: Boolean = false) {
        if (newIndex < 0 || newIndex >= playlist.size) {
            if (!firstLoad) {
                Toast.makeText(
                    this,
                    if (newIndex < 0) "This is the first video" else "This is the last video",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        savePositionNow()
        index = newIndex
        val item = playlist[index]
        ctrlTitle.text = item.title
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri("tdlib://${item.fileId}"))
        exo.prepare()
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = repository.getPositionSec(item.chatId, item.messageId)
            withContext(Dispatchers.Main) {
                if (saved > 5L && item.durationSec > 0 && saved < item.durationSec - 10L) {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setMessage(
                            getString(
                                R.string.player_resume_from,
                                FormatUtils.formatDuration(saved.toInt())
                            )
                        )
                        .setPositiveButton(R.string.player_resume) { _, _ ->
                            exo.seekTo(saved * 1000L)
                            exo.play()
                        }
                        .setNegativeButton(R.string.player_restart) { _, _ ->
                            exo.seekTo(0L)
                            exo.play()
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    exo.play()
                }
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
        var gestureHandled = false
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (locked) return true
                    val view = binding.playerView
                    if (view.isControllerFullyVisible) view.hideController() else view.showController()
                    gestureHandled = true
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (locked) return true
                    val width = binding.playerView.width
                    if (e.x < width / 2f) seekBy(-10_000L) else seekBy(10_000L)
                    gestureHandled = true
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
            gestureHandled = false
            detector.onTouchEvent(event)
            gestureHandled
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
        bufferHandler.removeCallbacks(bufferUpdater)
        feedbackHandler.removeCallbacks(hideFeedback)
        savePositionNow()
        stopSaveTicker()
        player?.release()
        player = null
    }
}
