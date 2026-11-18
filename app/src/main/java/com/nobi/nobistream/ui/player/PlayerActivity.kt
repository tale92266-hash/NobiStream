package com.nobi.nobistream.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.data.SubtitleTrack
import com.nobi.nobistream.databinding.ActivityPlayerBinding
import com.nobi.nobistream.databinding.LayoutBottomSheetPlayerBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.playback.TdlibDataSource
import com.nobi.nobistream.ui.CopiesSheet
import com.nobi.nobistream.ui.ThumbLoader
import com.nobi.nobistream.ui.components.SortSheet
import java.io.File
import java.io.IOException
import java.util.Locale
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
 *
 * v6 (YouTube-style playback): a stall is NEVER an error screen —
 * STATE_BUFFERING shows only the spinner while the download retries in
 * the background (Rule 1), with an offline line after 10s of one
 * continuous stall and network-vs-file error wording (Rule 2); buffer
 * targets are 50s/120s/1s/2s + 30s back buffer (Rule 3); there is NO
 * per-segment ABR — Telegram has no per-segment renditions to switch
 * between (Rule 5); subtitles come from .srt/.vtt sidecars discovered
 * near the video message, with a CC button only when a track exists
 * (Rule 6); and the resume path is race-free (setMediaItem at the
 * saved position + stall-classified resume errors) so opening a video
 * can no longer show the error screen for a far, unbuffered range.
 * v6 also removes the resume dialog entirely (Sam, 2026-11-17): every
 * open auto-resumes SILENTLY from the saved position, YouTube-style —
 * no prompt, no Restart button; a near-complete video (saved within
 * 10s of the end) restarts from 0 instead.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_MESSAGE_ID = "message_id"

        private const val CONTROLLER_TIMEOUT_MS = 3000
        /** Netflix-style fade for the controls (spec: 200–300ms). */
        private const val CONTROLLER_FADE_MS = 250L

        /**
         * v6 Rule 1: a stall is retried at most this many times at
         * the Activity level before the error screen appears. OUR
         * deliberate choice — NOT a YouTube number (YouTube publishes
         * no such bound; see the research report). ExoPlayer already
         * retries each load 3x internally before onPlayerError, so
         * 10 here is ~30+ load attempts against a genuinely stuck
         * download. Counts CONSECUTIVE failures — a successful load
         * resets it to 0.
         */
        private const val MAX_STALL_RETRIES = 10

        /** v6 Rule 2: offline text after this long in one continuous stall. OUR choice. */
        private const val OFFLINE_TEXT_DELAY_MS = 10_000L

        private const val SCROLL_NONE = 0
        private const val SCROLL_VERTICAL = 1
        private const val SCROLL_HORIZONTAL = 2

        /** v8 §4.4: Up Next countdown length. */
        private const val UP_NEXT_COUNTDOWN_SEC = 5

        /** v8 §4.4: press-and-hold delay before 2x kicks in. */
        private const val HOLD_2X_DELAY_MS = 400L
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
    /**
     * v8.3: true while a fullscreen orientation request is HELD.
     * v8 treated fullscreen as a nudge and released it in
     * applyOrientation() — a phone held in portrait then snapped
     * straight back to the 16:9 portrait player the moment the user
     * tapped fullscreen ("auto fit normal screen"). The hold is only
     * released after an exit to portrait (see applyOrientation).
     */
    private var fullscreenHeld = false
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
     * v6 Rule 1/2 — stall handling. A load/IO failure while playing
     * or buffering is a STALL (spinner + background retry), never an
     * error screen, until MAX_STALL_RETRIES consecutive failures.
     * Only a genuinely fatal error (decoder / renderer / parse, or
     * a file that cannot even be resolved) shows the error screen.
     */
    private var stallRetryCount = 0
    private var hasPlayed = false
    private var resumeSeekPending = false
    /** True when NEITHER resolve path produced a fresh file id — the file itself is gone. */
    private var currentItemUnresolvable = false
    private val stallHandler = Handler(Looper.getMainLooper())
    private val stallRetryRunnable = Runnable { retryStalledLoad() }
    private val offlineHandler = Handler(Looper.getMainLooper())
    private val offlineRunnable = Runnable {
        binding.bufferingOfflineText.visibility = View.VISIBLE
    }

    // v6 Rule 6 — subtitles.
    private var subtitleTracks: List<SubtitleTrack> = emptyList()
    private var subtitleDiscoveryJob: Job? = null
    private var subtitlesEnabled = false
    /** Message id whose tracks are already attached to currentMediaItem. */
    private var attachedSubtitleMessageId: Long = -1L
    private var currentMediaItem: MediaItem? = null
    private lateinit var ctrlSubtitles: ImageButton

    private var enterTransitionStarted = false

    /**
     * v4-player-UX: THE single source of truth for whether the
     * controls are open or hidden. Every tap flips this boolean and
     * every UI change goes through setControlsVisible() — nothing
     * reads PlayerView's internal controller state
     * (isControllerFullyVisible) any more, so the app can never
     * drift out of sync with what the user sees. Starts visible.
     */
    private var isControlsVisible = true
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        if (player?.isPlaying == true) setControlsVisible(false)
    }

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

    // v8 player redesign (§4.4) — Up Next card, portrait details
    // panel, hold-to-2x, seekbar preview, double-tap feedback.
    /** Saved-group title for the details-panel meta; null = unknown, omitted. */
    private var channelName: String? = null
    /** Language of the currently enabled subtitle track, for the chooser's selected row. */
    private var currentSubtitleLanguage: String? = null
    private val upNextHandler = Handler(Looper.getMainLooper())
    private var upNextRemainingSec = 0
    private var upNextTargetIndex = -1
    private val upNextTickRunnable = Runnable { tickUpNextCountdown() }
    private val holdHandler = Handler(Looper.getMainLooper())
    private var holdPending = false
    private var holdSpeedActive = false
    private var speedIndexBeforeHold = -1
    private val holdRunnable = Runnable { startHold2x() }
    private val doubleTapHideHandler = Handler(Looper.getMainLooper())
    private val hideDoubleTapRunnable = Runnable { binding.doubleTapPill.visibility = View.GONE }
    private var scrubStartPositionMs = 0L

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

        // Controller views first: applyOrientation() touches the fullscreen icon.
        findControllerViews()
        // v8 §4.4: no forced orientation any more — the manifest is
        // fullSensor, so the activity opens in the device's current
        // orientation and applyOrientation() lays out for it.
        applyOrientation(resources.configuration.orientation)
        // v4-player-UX: PlayerView NEVER drives visibility on its own
        // any more — no auto-show on play/pause, no own timeout, no
        // tap-toggle. isControlsVisible + setControlsVisible() are
        // the only driver (see below). The 3s auto-hide while playing
        // is our own Handler, not PlayerView's timeout.
        binding.playerView.setControllerAutoShow(false)
        binding.playerView.setControllerShowTimeoutMs(0)
        binding.playerView.setControllerHideOnTouch(false)
        setControlsVisible(true, animate = false)
        wireControllerButtons()
        binding.lockOverlayButton.setOnClickListener { setLocked(false) }
        // v8 §4.4: the "Tap to unlock" pill unlocks too; Up Next card
        // buttons; portrait details-panel action pills.
        binding.unlockPill.setOnClickListener { setLocked(false) }
        binding.upNextCancel.setOnClickListener { hideUpNextCard() }
        binding.upNextPlayNow.setOnClickListener { advanceToUpNext() }
        binding.upNextPlay.setOnClickListener { advanceToUpNext() }
        binding.detailsSubtitles.setOnClickListener { onSubtitlesButtonClicked() }
        binding.detailsSpeed.setOnClickListener { setSpeed((currentSpeedIndex + 1) % speeds.size) }
        binding.detailsAspect.setOnClickListener { cycleAspect() }
        binding.playerError.apply {
            setTitle(getString(R.string.player_error_title))
            setBody(getString(R.string.player_error_body))
            // v4-fb3: Retry genuinely re-requests — playAt() resolves
            // a fresh file id and builds a new MediaItem/data source.
            // v6: Retry resumes silently from the saved position
            // (no dialog exists any more).
            // v6 Rule 7: Retry also resets the stall-retry counter
            // (playAt does that) so a fresh attempt gets the full
            // retry budget.
            setRetry { playAt(index) }
            setDismiss { finish() }
        }

        lifecycleScope.launch {
            repository.settingsStore.autoplay.collect { value ->
                autoplay = value
            }
        }
        setupGestures()
        setupSeekPreview()
        // v8 §4.4: channel name for the portrait details-panel meta
        // ("Channel • Date • Size • Quality") — read once from the
        // saved groups; omitted when unknown, never invented.
        lifecycleScope.launch(Dispatchers.IO) {
            val name = runCatching {
                repository.observeGroups().first()
                    .firstOrNull { it.chatId == chatId }?.title
            }.getOrNull()
            withContext(Dispatchers.Main) {
                channelName = name
                refreshDetailsPanel()
            }
        }
        bufferHandler.post(bufferUpdater)
        // v6 Rule 6: caption style from Settings, applied once — the
        // same PlayerView (and its SubtitleView) serves every video.
        applySubtitleStyle()

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
                    val videos = page?.videos ?: emptyList()
                    // v5: the fallback reload is a channel list too —
                    // dedup it like one, so Next never replays the
                    // same episode in another quality.
                    val smart = runCatching {
                        repository.settingsStore.smartFilter.first()
                    }.getOrNull() ?: true
                    playlist = if (smart) SmartFilter.dedup(videos) else videos
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
        ctrlSubtitles = view.findViewById(R.id.ctrlSubtitles)
    }

    private fun wireControllerButtons() {
        val view = binding.playerView
        ctrlPlayPause.setOnClickListener { togglePlayPause() }
        // v6: Next/Previous auto-resume silently from the saved
        // position, like every other open (no dialog any more).
        view.findViewById<View>(R.id.ctrlPrev).setOnClickListener { playAt(index - 1) }
        view.findViewById<View>(R.id.ctrlNext).setOnClickListener { playAt(index + 1) }
        view.findViewById<View>(R.id.ctrlRew).setOnClickListener { seekBy(-10_000L) }
        view.findViewById<View>(R.id.ctrlFfwd).setOnClickListener { seekBy(10_000L) }
        view.findViewById<View>(R.id.ctrlBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        ctrlFullscreen.setOnClickListener { setFullscreen(!isFullscreen) }
        view.findViewById<View>(R.id.ctrlAspect).setOnClickListener { cycleAspect() }
        view.findViewById<View>(R.id.ctrlPip).setOnClickListener { enterPip() }
        ctrlLock.setOnClickListener { setLocked(true) }
        view.findViewById<View>(R.id.ctrlOptions).setOnClickListener { showOptionsSheet() }
        ctrlSpeedLabel.setOnClickListener { showOptionsSheet() }
        ctrlSubtitles.setOnClickListener { onSubtitlesButtonClicked() }
    }

    /**
     * v8.3: fullscreen is a HOLD, not a nudge. v8 asked the system to
     * rotate (SENSOR_LANDSCAPE) and then released the request in
     * applyOrientation() — with the phone held in portrait the
     * sensor immediately rotated the activity back to portrait, so
     * tapping fullscreen "auto-fit" back to the normal screen. Now
     * entering fullscreen HOLDS sensor-landscape until the user exits
     * (fullscreen button or Back — the Back handler already exits
     * fullscreen first); exiting requests PORTRAIT, and the release
     * back to the sensor happens in applyOrientation(). System
     * bars, the icon and the layout all live in applyOrientation.
     */
    private fun setFullscreen(fullscreen: Boolean) {
        fullscreenHeld = fullscreen
        requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * v8 §4.4: the single place orientation layout happens. Called
     * from onCreate and onConfigurationChanged (the activity is not
     * recreated — configChanges covers orientation).
     *
     * Landscape = immersive fullscreen player, details panel gone,
     * content allowed into the display cutout (SHORT_EDGES). Portrait
     * = 16:9 player on top (dimensionRatio) + details panel below,
     * system bars shown, default cutout mode.
     */
    private fun applyOrientation(orientation: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        isFullscreen = landscape
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (landscape) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrlFullscreen.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            ctrlFullscreen.setImageResource(R.drawable.ic_fullscreen)
        }
        // Display cutout: landscape fullscreen draws into the
        // cutout area; portrait keeps the default. API 28+ only
        // (minSdk 26) — guarded, never touched on older devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = if (landscape) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            window.attributes = attrs
        }
        val lp = binding.playerView.layoutParams as ConstraintLayout.LayoutParams
        if (landscape) {
            lp.dimensionRatio = null
            lp.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.detailsPanel.visibility = View.GONE
        } else {
            lp.dimensionRatio = "16:9"
            lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            lp.bottomToTop = R.id.detailsPanel
            binding.detailsPanel.visibility = View.VISIBLE
            refreshDetailsPanel()
        }
        binding.playerView.layoutParams = lp
        // v8.3: release the orientation request ONLY after an exit to
        // portrait — from here the sensor governs again, so rotating
        // the device rotates the player. NEVER release while a
        // fullscreen hold is active: releasing on the landscape
        // transition is what snapped a portrait-held phone straight
        // back to the 16:9 player (the v8 fullscreen bug). In
        // landscape the request stays held; the Back handler and the
        // fullscreen button are the only exits.
        if (!landscape && !fullscreenHeld) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig.orientation)
    }

    private fun setLocked(value: Boolean) {
        locked = value
        // v8 §4.4: a hold-to-2x in progress must not outlive the lock.
        cancelHoldPending()
        if (holdSpeedActive) endHold2x()
        if (value) {
            // Locking hides the controls through the single source of
            // truth — never PlayerView directly.
            setControlsVisible(false)
            binding.lockOverlayButton.visibility = View.VISIBLE
            // v8 §4.4: only the lock icon + the subtle "Tap to
            // unlock" pill remain while locked.
            binding.unlockPill.visibility = View.VISIBLE
            ctrlLock.setImageResource(R.drawable.ic_lock)
            Toast.makeText(this, getString(R.string.player_locked), Toast.LENGTH_SHORT).show()
        } else {
            binding.lockOverlayButton.visibility = View.GONE
            binding.unlockPill.visibility = View.GONE
            ctrlLock.setImageResource(R.drawable.ic_lock_open)
            setControlsVisible(true)
            Toast.makeText(this, getString(R.string.player_unlocked), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleAspect() {
        setAspect((aspectIndex + 1) % aspectModes.size)
    }

    private fun setAspect(index: Int) {
        aspectIndex = index.coerceIn(aspectModes.indices)
        binding.playerView.resizeMode = aspectModes[aspectIndex]
        binding.detailsAspect.text = aspectLabels[aspectIndex]
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
            // PiP hides the controls through the single source of
            // truth (no fade — the window is already shrinking).
            setControlsVisible(false, animate = false)
            binding.bufferingOverlay.visibility = View.GONE
            binding.gestureFeedback.visibility = View.GONE
            binding.playerError.visibility = View.GONE
            binding.lockOverlayButton.visibility = View.GONE
            // v8 §4.4: every floating overlay goes away in PiP too.
            hideUpNextCard()
            cancelHoldPending()
            if (holdSpeedActive) endHold2x()
            binding.holdSpeedPill.visibility = View.GONE
            binding.seekPreviewPill.visibility = View.GONE
            binding.doubleTapRipple.visibility = View.GONE
            binding.doubleTapPill.visibility = View.GONE
            binding.unlockPill.visibility = View.GONE
            binding.detailsPanel.visibility = View.GONE
        } else if (locked) {
            binding.lockOverlayButton.visibility = View.VISIBLE
            binding.unlockPill.visibility = View.VISIBLE
        } else {
            // Leaving PiP: restore the layout for the current
            // orientation (details panel included), then the controls.
            applyOrientation(resources.configuration.orientation)
            setControlsVisible(true, animate = false)
        }
    }

    /**
     * Player options bottom sheet (pixel spec §07): rows 56dp, speed chips,
     * autoplay switch, aspect chips. While the sheet is open the controls
     * must NOT auto-hide; the 3s timer is restored when the sheet dismisses.
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

        // While the sheet is open the controls must NOT auto-hide;
        // the 3s timer is ours, so just cancel it, and restore the
        // controls (with a fresh timer) when the sheet dismisses.
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        setControlsVisible(true)
        dialog.setContentView(sheetBinding.root)
        dialog.setOnDismissListener {
            if (optionsSheet == dialog) optionsSheet = null
            setControlsVisible(true)
        }
        dialog.show()
    }

    private fun setSpeed(index: Int) {
        setSpeedQuiet(index)
        showFeedback(R.drawable.ic_speed, "Speed ${speeds[currentSpeedIndex]}x")
    }

    /** Applies a speed without the feedback pill — used by hold-to-2x
     * restore, where the pill would be noise on every release. */
    private fun setSpeedQuiet(index: Int) {
        currentSpeedIndex = index.coerceIn(speeds.indices)
        val speed = speeds[currentSpeedIndex]
        player?.playbackParameters = PlaybackParameters(speed)
        ctrlSpeedLabel.text = "${speed}x"
        binding.detailsSpeed.text = "${speed}x"
    }

    /**
     * v4-player-UX: the ONLY place controller visibility changes.
     * Flips [isControlsVisible] and drives PlayerView from it — the
     * app never asks PlayerView what it thinks. Showing fades the
     * controller in (250ms) and (re)arms the 3s auto-hide while
     * playing; hiding fades it out and only then sets it GONE, so
     * the tools never blink away mid-tap. While locked, showing is
     * refused outright — the lock overlay is the only UI.
     */
    private fun setControlsVisible(visible: Boolean, animate: Boolean = true) {
        if (visible && locked) return
        val wasVisible = isControlsVisible
        isControlsVisible = visible
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        val view = binding.playerView
        val controller = view.findViewById<View>(R.id.playerControllerRoot)
        if (visible) {
            if (!wasVisible) {
                view.showController()
                if (controller != null) {
                    controller.animate().cancel()
                    controller.alpha = if (animate) 0f else 1f
                    if (animate) {
                        controller.animate().alpha(1f)
                            .setDuration(CONTROLLER_FADE_MS).start()
                    }
                }
            }
            scheduleAutoHide()
        } else {
            if (wasVisible) {
                if (animate && controller != null) {
                    controller.animate().cancel()
                    controller.animate().alpha(0f)
                        .setDuration(CONTROLLER_FADE_MS)
                        .withEndAction { view.hideController() }
                        .start()
                } else {
                    view.hideController()
                }
            }
        }
    }

    /** 3s auto-hide, only while actually playing. */
    private fun scheduleAutoHide() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying == true) {
            hideControlsHandler.postDelayed(hideControlsRunnable, CONTROLLER_TIMEOUT_MS.toLong())
        }
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
        seekBySilent(deltaMs)
        val label = if (deltaMs < 0) "-${-deltaMs / 1000}s" else "+${deltaMs / 1000}s"
        val icon = if (deltaMs < 0) R.drawable.ic_replay_10 else R.drawable.ic_forward_10
        showFeedback(icon, label)
    }

    /** Seeks without the feedback pill — double-tap shows its own pill. */
    private fun seekBySilent(deltaMs: Long) {
        val exo = player ?: return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, exo.duration.coerceAtLeast(0L))
        exo.seekTo(target)
    }

    private fun startPlayer() {
        val factory = TdlibDataSource.Factory(locator.gateway)
        val mediaSourceFactory = ProgressiveMediaSource.Factory(factory)
        // v6 Rule 3 — YouTube-like buffer targets. ExoPlayer's current
        // defaults are 50s/50s/1s/2s; YouTube web measures ~120s ahead
        // (research report §2). OUR deliberate choice: min 50s, max
        // 120s, start 1s, rebuffer 2s, plus a 30s back buffer so
        // backward seeks are cheap. Buffer health <3s is the danger
        // zone; ExoPlayer's loader already prioritises the current
        // window and TdlibDataSource's 8MB lookahead keeps the
        // download ahead — no extra download-priority machinery is
        // invented here.
        //
        // v6 Rule 5 — NO per-segment ABR quality switching, by design:
        // YouTube switches quality every 2–4s because it has
        // per-segment renditions. Telegram does NOT — each quality is
        // a separate whole file, so NobiStream cannot switch quality
        // mid-video. Quality stays: Smart Filter picks the best copy
        // per episode, the quality chip is manual. A slow network
        // copes by buffering + spinner, not by dropping quality. Do
        // NOT "fix" this by adding ABR — there is nothing to switch
        // between.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 120_000, 1_000, 2_000)
            .setBackBuffer(30_000, true)
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
                Player.STATE_BUFFERING -> {
                    // v6 Rule 1: a stall shows ONLY the spinner (and,
                    // after 10s, the small offline line) — never an
                    // error screen, however long it lasts. The last
                    // frame stays frozen underneath; the download
                    // keeps running in the background.
                    binding.bufferingOverlay.visibility = View.VISIBLE
                    startOfflineTimer()
                }
                Player.STATE_READY -> {
                    binding.bufferingOverlay.visibility = View.GONE
                    binding.playerError.visibility = View.GONE
                    cancelOfflineTimer()
                    // v6: a successful load clears the stall budget —
                    // the 10-attempt bound counts CONSECUTIVE
                    // failures, and a recovered stall is not one.
                    stallRetryCount = 0
                    stallHandler.removeCallbacks(stallRetryRunnable)
                    resumeSeekPending = false
                    startPostponedEnterTransitionOnce()
                    updatePlayPauseIcon()
                }
                Player.STATE_ENDED -> {
                    binding.bufferingOverlay.visibility = View.GONE
                    cancelOfflineTimer()
                    savePositionNow()
                    // v8 §4.4: no silent jump any more — the Up Next
                    // card gets its 5s (or a static Play button) first.
                    onVideoEnded()
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            if (isPlaying) {
                hasPlayed = true
                startSaveTicker()
                // Playing again: the 3s auto-hide resumes.
                scheduleAutoHide()
            } else {
                stopSaveTicker()
                // Paused: controls STAY visible (spec). But only when
                // actually paused (playWhenReady false) — during a
                // rebuffer playWhenReady is still true and the
                // controls must not pop back in on their own.
                if (player?.playWhenReady == false) {
                    hideControlsHandler.removeCallbacks(hideControlsRunnable)
                    setControlsVisible(true)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // v6: a seek is a position change worth persisting NOW,
            // not at the next 5s tick — so a seek-then-exit still
            // resumes from the seek target.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) savePositionNow()
        }

        override fun onPlayerError(error: PlaybackException) {
            // v4-fb3: log the REAL error (the old code logged
            // nothing, so the failure was un-diagnosable).
            Log.e(
                "PlayerActivity",
                "Playback failed for ${playlist.getOrNull(index)?.title}",
                error
            )
            if (!currentItemUnresolvable && isStallError(error)) {
                // v6 Rule 1: a load/IO failure is a STALL, not a
                // fatal error. Keep the spinner, keep downloading in
                // the background, retry with backoff. A resume seek
                // in flight is not disturbed — recovery still lands
                // on STATE_READY at the saved position.
                stallRetryCount++
                if (stallRetryCount >= MAX_STALL_RETRIES) {
                    // Retries exhausted: the download is genuinely
                    // stuck. Only now does the error screen appear.
                    showFatalError(networkFailure = true)
                } else {
                    binding.bufferingOverlay.visibility = View.VISIBLE
                    binding.playerError.visibility = View.GONE
                    startOfflineTimer()
                    scheduleStallRetry()
                }
                return
            }
            // Fatal: decoder / renderer / parse failure, or a file
            // that could not even be resolved. Error screen at once.
            showFatalError(networkFailure = isNetworkError(error) && !currentItemUnresolvable)
        }
    }

    /**
     * v6 Rule 1: classify a PlaybackException. A STALL is any failure
     * whose cause chain holds an IOException (our data source throws
     * IOException when bytes cannot be downloaded yet) or whose error
     * code is an IO / timeout / behind-live-window code — ExoPlayer
     * surfaces exactly these while the download is merely slow or
     * the network is down. Everything else (decoder, renderer,
     * parse, DRM) is FATAL: the file itself cannot play, and
     * retrying would only spin forever. File-not-found and
     * no-permission are fatal too: the bytes do not exist, so no
     * amount of retrying helps.
     */
    private fun isStallError(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is IOException) return true
            cause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_TIMEOUT -> true
            else -> false
        }
    }

    /** True when a fatal error's cause chain is an IO/network failure (drives the error wording). */
    private fun isNetworkError(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is IOException) return true
            cause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
            else -> false
        }
    }

    /**
     * v6 Rule 2: the ONLY place the error screen appears.
     * [networkFailure] picks the wording: the connection died after
     * retries were exhausted vs the video file itself failed.
     */
    private fun showFatalError(networkFailure: Boolean) {
        // A failed source must never leave a stale resume behind.
        resumeSeekPending = false
        cancelOfflineTimer()
        stallHandler.removeCallbacks(stallRetryRunnable)
        stallRetryCount = 0
        binding.bufferingOverlay.visibility = View.GONE
        binding.playerError.apply {
            setTitle(getString(R.string.player_error_title))
            setBody(
                getString(
                    if (networkFailure) R.string.player_error_network_body
                    else R.string.player_error_file_body
                )
            )
        }
        binding.playerError.visibility = View.VISIBLE
    }

    /**
     * v6 Rule 1: schedule the next stall retry with backoff 2s ->
     * 5s -> 10s (OUR deliberate choice, not a YouTube number). The
     * retry itself is just ExoPlayer.prepare() — the documented
     * retry after a failure — which re-opens the source at the exact
     * stalled position with playWhenReady untouched, so a user pause
     * stays paused and a stall auto-resumes when data arrives.
     */
    private fun scheduleStallRetry() {
        stallHandler.removeCallbacks(stallRetryRunnable)
        val delayMs = when (stallRetryCount) {
            1 -> 2_000L
            2 -> 5_000L
            else -> 10_000L
        }
        stallHandler.postDelayed(stallRetryRunnable, delayMs)
    }

    private fun retryStalledLoad() {
        player?.prepare()
    }

    private fun cancelStallRetry() {
        stallHandler.removeCallbacks(stallRetryRunnable)
    }

    /**
     * v6 Rule 2: after 10s of one continuous stall, show the small
     * "You're offline — retrying…" line under the spinner. The timer
     * restarts on every fresh STATE_BUFFERING and is cancelled on
     * READY / fatal error / a new video. It only runs for a real
     * stall (mid-playback, a resume seek, or inside the retry loop)
     * — a slow first load just shows the spinner and the buffered %.
     * 10s is OUR deliberate choice.
     */
    private fun startOfflineTimer() {
        cancelOfflineTimer()
        if (!hasPlayed && !resumeSeekPending && stallRetryCount == 0) return
        offlineHandler.postDelayed(offlineRunnable, OFFLINE_TEXT_DELAY_MS)
    }

    private fun cancelOfflineTimer() {
        offlineHandler.removeCallbacks(offlineRunnable)
        binding.bufferingOfflineText.visibility = View.GONE
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
     *   NobiRepository.resolveVideoItemDetailed) — the stored id is
     *   dead after a restart, which is why open / Retry / Next /
     *   Previous ALL failed on Ep 361 with 00:00/00:00.
     * - v6 SILENT AUTO-RESUME (Sam, 2026-11-17): there is NO resume
     *   dialog any more — every open resumes silently from the saved
     *   position, YouTube-style. The media item is set WITH its start
     *   position in ONE call and prepared once (the race-free resume
     *   API: setMediaItem(item, startPositionMs) + playWhenReady +
     *   a single prepare(); no separate seekTo before prepare()).
     *   The old flow — prepare at 0, wait for STATE_READY, then seek
     *   — is exactly what caused the resume bug, and none of it
     *   remains. A saved position within 10s of the end counts as
     *   finished and restarts from 0 (the only restart case now).
     * - Any load error on the resume path is classified as a STALL
     *   (spinner + background retry, Rule 1), never an error screen.
     */
    private fun playAt(newIndex: Int, firstLoad: Boolean = false, saveCurrent: Boolean = true) {
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
        // v8: the Copies substitution path already saved the
        // outgoing item and must not attribute its position to
        // the incoming copy.
        if (saveCurrent) savePositionNow()
        // v8 §4.4: a manual Next/Previous (or a countdown firing)
        // dismisses any Up Next card still showing.
        hideUpNextCard()
        index = newIndex
        val item = playlist[index]
        ctrlTitle.text = item.title
        ctrlCounter.text = if (playlist.size > 1) "${index + 1} / ${playlist.size}" else ""
        refreshDetailsPanel()
        binding.playerError.visibility = View.GONE
        // v6: a new video cancels any pending stall retry / offline
        // timer and resets the stall budget; it also drops the
        // previous video's subtitle tracks and discovery job.
        cancelStallRetry()
        cancelOfflineTimer()
        stallRetryCount = 0
        resumeSeekPending = false
        currentItemUnresolvable = false
        subtitleDiscoveryJob?.cancel()
        subtitleTracks = emptyList()
        subtitlesEnabled = false
        currentSubtitleLanguage = null
        attachedSubtitleMessageId = -1L
        currentMediaItem = null
        updateCcButton()
        val exo = player ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val detailed =
                runCatching { repository.resolveVideoItemDetailed(item) }.getOrNull()
            val resolved = detailed?.item ?: item
            val saved = repository.getPositionSec(resolved.chatId, resolved.messageId)
            withContext(Dispatchers.Main) {
                // The user may have moved on while resolving — never
                // hijack the newer episode with the older one.
                if (index != newIndex || player == null) return@withContext
                currentItemUnresolvable = detailed?.freshIdResolved == false
                currentMediaItem = buildMediaItem(resolved, emptyList())
                val resumable = saved > 5L && resolved.durationSec > 0 &&
                    saved < resolved.durationSec - 10L
                // Silent resume: ONE setMediaItem(item, startPosition)
                // + prepare() directly at the saved position, so
                // ExoPlayer opens the source there instead of loading
                // from 0 first and then seeking. ROOT CAUSE of the v5
                // resume bug (verified in the v5 code): a cold-start
                // resume did prepare() at position 0 (starting a
                // from-0 download) and then seekTo()'d into a far,
                // unbuffered range (e.g. Ep 361, saved 22:28 of
                // 23:06). TDLib's sequential download could not prove
                // that far range inside the data source's 60s
                // ensureDownloaded window, so the IOException surfaced
                // as the fatal "Video couldn't play" screen. Not
                // resumable (never watched, or finished) starts at 0.
                val targetMs = if (resumable) {
                    resumeSeekPending = true
                    clampResumeTarget(saved * 1000L, resolved.durationSec)
                } else {
                    0L
                }
                exo.setMediaItem(currentMediaItem!!, targetMs)
                exo.prepare()
                exo.play()
                // v6 Rule 6: subtitle discovery runs async and NEVER
                // blocks playback — the video starts now, the CC
                // button appears when (and if) a sidecar is found.
                startSubtitleDiscovery(resolved)
            }
        }
    }

    /**
     * v6: clamp a resume target to duration-2s so a stale / near-end
     * saved position can never seek past the end. The resumable rule
     * (saved < duration-10s) already keeps it inside; this is the
     * belt-and-braces for a duration that only becomes known later.
     */
    private fun clampResumeTarget(savedMs: Long, durationSec: Int): Long {
        if (durationSec <= 0) return savedMs.coerceAtLeast(0L)
        return savedMs.coerceIn(0L, durationSec * 1000L - 2_000L)
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
                collected.addAll(page.videos)
                if (page.videos.any { it.messageId == currentId }) found = true
                if (page.rawCount < 50 || page.lastMessageId <= 0L) break
                fromId = page.lastMessageId
            }
            if (!found) return@launch
            // v5: dedup FIRST (best copy per episode), then the
            // quality filter — the same order as the channel list,
            // so the expanded playlist never contains two copies of
            // one episode and never shows an episode whose best copy
            // the filter excludes. If the episode being watched is
            // filtered out by this order, the swap is skipped and
            // the original slice stays — never a worse state.
            val smart = runCatching {
                repository.settingsStore.smartFilter.first()
            }.getOrNull() ?: true
            var finalList = if (smart) SmartFilter.dedup(collected) else collected
            if (filter != "All") {
                finalList = finalList.filter { it.quality.label == filter }
            }
            val newIndex = finalList.indexOfFirst { it.messageId == currentId }
            if (newIndex < 0) return@launch
            withContext(Dispatchers.Main) {
                // Only swap while still on the same episode — if the
                // user already moved on, the slice stays correct.
                if (playlist.getOrNull(index)?.messageId != currentId) return@withContext
                playlist = finalList
                index = newIndex
                ctrlCounter.text =
                    if (playlist.size > 1) "${index + 1} / ${playlist.size}" else ""
                refreshDetailsPanel()
            }
        }
    }

    // ---- v6 Rule 6: subtitles ----

    /** Build the MediaItem for a video, with subtitle configs when tracks are known. */
    private fun buildMediaItem(item: VideoItem, subs: List<SubtitleTrack>): MediaItem {
        val builder = MediaItem.Builder().setUri("tdlib://${item.fileId}")
        if (subs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subs.mapIndexed { i, track ->
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(track.localPath)))
                    .setMimeType(
                        if (track.isVtt) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
                    )
                    .setLanguage(track.language)
                    .setSelectionFlags(
                        if (i == 0) C.SELECTION_FLAG_DEFAULT else 0
                    )
                    .build()
            })
        }
        return builder.build()
    }

    /**
     * v6 Rule 6: discover subtitle sidecars WITHOUT blocking
     * playback. Runs on IO while the video already plays; when (and
     * if) tracks are found, the CC button appears and — when
     * "Always show subtitles" is on — the first track is enabled.
     * Attaching tracks needs one re-prepare at the same position
     * (Media3 has no live track injection for progressive sources);
     * TDLib's cache already holds the video bytes, so this is fast
     * and position-exact. The CC toggle itself never re-prepares.
     */
    private fun startSubtitleDiscovery(item: VideoItem) {
        subtitleDiscoveryJob?.cancel()
        subtitleDiscoveryJob = lifecycleScope.launch(Dispatchers.IO) {
            val tracks = runCatching { repository.findSubtitles(item) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                val exo = player ?: return@withContext
                if (index !in playlist.indices) return@withContext
                if (playlist[index].messageId != item.messageId) return@withContext
                subtitleTracks = tracks
                updateCcButton()
                if (tracks.isEmpty()) return@withContext
                if (attachedSubtitleMessageId == item.messageId) return@withContext
                // Never yank the error screen away to attach tracks —
                // Retry re-runs discovery after a fresh playAt.
                if (binding.playerError.visibility == View.VISIBLE) return@withContext
                attachedSubtitleMessageId = item.messageId
                val position = exo.currentPosition
                val wasPlaying = exo.playWhenReady
                currentMediaItem = buildMediaItem(item, tracks)
                exo.setMediaItem(currentMediaItem!!, position)
                exo.prepare()
                exo.playWhenReady = wasPlaying
                applySubtitleStyle()
                lifecycleScope.launch {
                    val alwaysShow = runCatching {
                        repository.settingsStore.subtitleAlwaysShow.first()
                    }.getOrNull() ?: false
                    if (alwaysShow && player != null && subtitleTracks.isNotEmpty()) {
                        setSubtitlesEnabled(true, subtitleTracks.first().language)
                    }
                }
            }
        }
    }

    private fun onSubtitlesButtonClicked() {
        if (subtitleTracks.isEmpty()) return
        if (subtitleTracks.size == 1) {
            setSubtitlesEnabled(!subtitlesEnabled, subtitleTracks.first().language)
            return
        }
        showSubtitleChooser()
    }

    /**
     * v8 §4.9: the subtitle picker is the shared SortSheet bottom
     * sheet now (radio rows, accent checkmark) — the raw AlertDialog
     * is gone. Options are "Off" + one row per track; the selected
     * row is Off when subtitles are off, else the enabled language.
     */
    private fun showSubtitleChooser() {
        val options = mutableListOf(getString(R.string.player_subtitles_off))
        options.addAll(subtitleTracks.map { displayLanguage(it) })
        val selected = if (!subtitlesEnabled) {
            0
        } else {
            val langIndex = subtitleTracks.indexOfFirst { it.language == currentSubtitleLanguage }
            if (langIndex >= 0) langIndex + 1 else 1
        }
        SortSheet.show(
            this,
            getString(R.string.player_subtitle_chooser_title),
            options,
            selected
        ) { which ->
            if (which == 0) {
                setSubtitlesEnabled(false)
            } else {
                setSubtitlesEnabled(true, subtitleTracks[which - 1].language)
            }
        }
    }

    /**
     * v6 Rule 6: one tap toggles subtitles mid-playback — NO player
     * restart, NO rebuffer. This only flips the text track
     * selection; the video source is untouched.
     */
    private fun setSubtitlesEnabled(enabled: Boolean, language: String? = null) {
        val exo = player ?: return
        subtitlesEnabled = enabled
        currentSubtitleLanguage = if (enabled) language else null
        val params = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
        if (enabled && language != null) params.setPreferredTextLanguage(language)
        exo.trackSelectionParameters = params.build()
        updateCcButton()
        updateDetailsSubtitlesState()
    }

    private fun updateCcButton() {
        val hasTracks = subtitleTracks.isNotEmpty()
        ctrlSubtitles.visibility = if (hasTracks) View.VISIBLE else View.GONE
        // v8 §4.4: the portrait Subtitles pill follows the CC button.
        binding.detailsSubtitles.visibility =
            if (hasTracks) View.VISIBLE else View.GONE
        if (!hasTracks) return
        if (subtitlesEnabled) {
            ctrlSubtitles.setColorFilter(getColor(R.color.primary))
        } else {
            ctrlSubtitles.clearColorFilter()
        }
    }

    private fun displayLanguage(track: SubtitleTrack): String {
        val code = track.language
        if (code != null) {
            val name = Locale.forLanguageTag(code).displayLanguage
            if (name.isNotBlank()) return name
            return code
        }
        return track.fileName
    }

    /**
     * v6 Rule 6: caption style from Settings (size / colour /
     * background), rendered through PlayerView's SubtitleView.
     * Two honest limits, also stated in Settings: a video with no
     * subtitle file gets no CC button, and subtitles burned into the
     * video itself can never be turned off — they are part of the
     * video, not a track.
     */
    private fun applySubtitleStyle() {
        val view = binding.playerView.subtitleView ?: return
        lifecycleScope.launch {
            val size = runCatching {
                repository.settingsStore.subtitleSize.first()
            }.getOrNull() ?: "Medium"
            val color = runCatching {
                repository.settingsStore.subtitleColor.first()
            }.getOrNull() ?: "White"
            val background = runCatching {
                repository.settingsStore.subtitleBackground.first()
            }.getOrNull() ?: "Semi-black"
            view.setFractionalTextSize(
                when (size) {
                    "Small" -> 0.04f
                    "Large" -> 0.07f
                    else -> 0.0533f
                }
            )
            val foreground = when (color) {
                "Yellow" -> 0xFFFFFF00.toInt()
                "Cyan" -> 0xFF00E5FF.toInt()
                else -> Color.WHITE
            }
            val bg = when (background) {
                "Black" -> 0xCC000000.toInt()
                "Transparent" -> Color.TRANSPARENT
                else -> 0x99000000.toInt()
            }
            view.setStyle(
                CaptionStyleCompat(
                    foreground, bg, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, Typeface.DEFAULT
                )
            )
            // Our Settings win over any style embedded in the .vtt file.
            view.setApplyEmbeddedStyles(false)
        }
    }

    // ---- v8 §4.4: Up Next countdown card ----

    /**
     * An episode ended. If a next episode exists, show the floating
     * Up Next card instead of jumping straight on: autoplay ON gives
     * a 5s countdown (Cancel / Play Now), autoplay OFF a static card
     * with a Play button only. If there is no next episode, the old
     * behaviour stands (playAt toasts "last video" when autoplay is
     * on, nothing when it is off). Cancel leaves the player paused
     * at the end — it never advances.
     */
    private fun onVideoEnded() {
        hideUpNextCard()
        val nextIndex = index + 1
        if (nextIndex >= playlist.size) {
            if (autoplay) playAt(nextIndex)
            return
        }
        // In PiP there is nothing to tap — keep the old silent
        // behaviour instead of showing a card in a tiny window.
        if (isInPictureInPictureMode) {
            if (autoplay) playAt(nextIndex)
            return
        }
        val next = playlist[nextIndex]
        upNextTargetIndex = nextIndex
        binding.upNextTitle.text = next.title
        binding.upNextThumb.setImageResource(R.drawable.ic_video_placeholder)
        ThumbLoader.load(lifecycleScope, repository, next, binding.upNextThumb)
        if (autoplay) {
            upNextRemainingSec = UP_NEXT_COUNTDOWN_SEC
            binding.upNextCountdown.visibility = View.VISIBLE
            binding.upNextCountdown.text =
                getString(R.string.player_up_next_in, upNextRemainingSec)
            binding.upNextCancel.visibility = View.VISIBLE
            binding.upNextPlayNow.visibility = View.VISIBLE
            binding.upNextPlay.visibility = View.GONE
            upNextHandler.postDelayed(upNextTickRunnable, 1000L)
        } else {
            binding.upNextCountdown.visibility = View.GONE
            binding.upNextCancel.visibility = View.GONE
            binding.upNextPlayNow.visibility = View.GONE
            binding.upNextPlay.visibility = View.VISIBLE
        }
        binding.upNextCard.visibility = View.VISIBLE
    }

    private fun tickUpNextCountdown() {
        if (upNextTargetIndex < 0) return
        upNextRemainingSec--
        if (upNextRemainingSec <= 0) {
            val target = upNextTargetIndex
            hideUpNextCard()
            playAt(target)
        } else {
            binding.upNextCountdown.text =
                getString(R.string.player_up_next_in, upNextRemainingSec)
            upNextHandler.postDelayed(upNextTickRunnable, 1000L)
        }
    }

    private fun advanceToUpNext() {
        val target = upNextTargetIndex
        hideUpNextCard()
        if (target >= 0) playAt(target)
    }

    private fun hideUpNextCard() {
        upNextHandler.removeCallbacks(upNextTickRunnable)
        upNextTargetIndex = -1
        binding.upNextCard.visibility = View.GONE
    }

    // ---- v8 §4.4: portrait details panel ----

    /**
     * Portrait details panel: title, "Channel • Date • Size •
     * Quality" meta (channel omitted when unknown — never invented),
     * and the action pills. Subtitles pill follows the CC-button
     * condition exactly (a track exists); Copies pill follows the
     * current episode's rebuilt EpisodeGroup.
     */
    private fun refreshDetailsPanel() {
        val item = playlist.getOrNull(index) ?: return
        binding.detailsTitle.text = item.title
        val parts = mutableListOf<String>()
        channelName?.takeIf { it.isNotBlank() }?.let { parts += it }
        FormatUtils.formatDate(item.dateEpochSec)
            .takeIf { it.isNotEmpty() }?.let { parts += it }
        if (item.sizeBytes > 0L) parts += FormatUtils.formatSize(item.sizeBytes)
        if (item.quality.label.isNotEmpty()) parts += item.quality.label
        binding.detailsMeta.text = parts.joinToString(" • ")
        binding.detailsMeta.visibility =
            if (parts.isEmpty()) View.GONE else View.VISIBLE
        binding.detailsSubtitles.visibility =
            if (subtitleTracks.isNotEmpty()) View.VISIBLE else View.GONE
        updateDetailsSubtitlesState()
        binding.detailsSpeed.text = "${speeds[currentSpeedIndex]}x"
        binding.detailsAspect.text = aspectLabels[aspectIndex]
        refreshCopiesPill()
    }

    private fun updateDetailsSubtitlesState() {
        // Full opacity = subtitles on, dimmed = off (same state as
        // the CC button's tint, which updateCcButton owns).
        binding.detailsSubtitles.alpha = if (subtitlesEnabled) 1.0f else 0.55f
    }

    private fun refreshCopiesPill() {
        val item = playlist.getOrNull(index) ?: return
        // v8 coordinator fix: prefer the grouping stashed at open
        // time (PlayerPlaylist.episodeGroups). The playlist itself
        // holds only best copies when Smart Filter is on, so
        // rebuilding from it would list a single copy. Fall back
        // to the rebuild when no stash exists (Continue / Search).
        // One copy means nothing to pick — the pill stays hidden.
        // Match ANY copy in the group, not just best: after the
        // user picks a non-best copy, the current item IS that
        // copy, and the pill must stay visible so they can switch
        // back. (The rebuild fallback can't do this — a substituted
        // playlist holds only the one copy — but the stash can.)
        val group = PlayerPlaylist.episodeGroups.firstOrNull { group ->
            group.copies.any { it.chatId == item.chatId && it.messageId == item.messageId }
        } ?: SmartFilter.groupEpisodes(playlist).firstOrNull {
            it.best.chatId == item.chatId && it.best.messageId == item.messageId
        }
        if (group == null || group.copies.size <= 1) {
            binding.detailsCopies.visibility = View.GONE
            return
        }
        binding.detailsCopies.visibility = View.VISIBLE
        binding.detailsCopies.text =
            getString(R.string.player_details_copies_count, group.copies.size)
        binding.detailsCopies.setOnClickListener {
            CopiesSheet.show(this, group) { copy ->
                val target = playlist.indexOfFirst {
                    it.chatId == copy.chatId && it.messageId == copy.messageId
                }
                if (target >= 0) {
                    if (target != index) playAt(target)
                } else {
                    // The copy isn't in the playlist (Smart Filter
                    // hid it): substitute it for the current item,
                    // the same way the list pickers do (v5). Save
                    // the outgoing item's position first, and skip
                    // playAt's own save so the position isn't
                    // attributed to the incoming copy.
                    savePositionNow()
                    playlist = playlist.toMutableList().also { it[index] = copy }
                    playAt(index, saveCurrent = false)
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
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (locked) return true
                    // v4-player-UX: ONE tap flips the single source of
                    // truth — every tool hides, next tap shows them
                    // all again. onSingleTapConfirmed (not onDown /
                    // onSingleTapUp) so a double-tap seek never also
                    // toggles, and swipes never reach here as taps.
                    setControlsVisible(!isControlsVisible)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (locked) return true
                    // v8 §4.4: a double-tap must never trigger the
                    // hold-to-2x timer armed by its second DOWN.
                    cancelHoldPending()
                    val width = binding.playerView.width
                    val left = e.x < width / 2f
                    val delta = if (left) -10_000L else 10_000L
                    seekBySilent(delta)
                    showDoubleTapFeedback(left, delta)
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
                        // v8 §4.4: a swipe starting cancels a pending
                        // hold-to-2x (and ends an active one) — the
                        // swipe owns the gesture from here on.
                        cancelHoldPending()
                        if (holdSpeedActive) endHold2x()
                        // §2E: subtle haptic when a swipe starts.
                        binding.playerView.performHapticFeedback(
                            HapticFeedbackConstants.KEYBOARD_TAP
                        )
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
                    // v8 §4.4: arm hold-to-2x. Cancelled on UP, on a
                    // double-tap, on a swipe starting, and on lock —
                    // so only a genuine press-and-hold fires it.
                    holdPending = true
                    holdHandler.postDelayed(holdRunnable, HOLD_2X_DELAY_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelHoldPending()
                    if (holdSpeedActive) endHold2x()
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

    // ---- v8 §4.4: double-tap seek feedback + hold-to-2x + seekbar preview ----

    /**
     * Double-tap seek feedback: a radial semi-transparent ripple +
     * an animated "-10s / +10s" pill on the tapped half (bias 0.25
     * left, 0.75 right), plus a subtle KEYBOARD_TAP haptic. The seek
     * itself already happened in seekBySilent; the centred
     * gestureFeedback pill stays out of it so there is exactly one
     * pill on screen.
     */
    private fun showDoubleTapFeedback(left: Boolean, deltaMs: Long) {
        binding.playerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val bias = if (left) 0.25f else 0.75f
        val rippleLp = binding.doubleTapRipple.layoutParams as ConstraintLayout.LayoutParams
        rippleLp.horizontalBias = bias
        binding.doubleTapRipple.layoutParams = rippleLp
        val pillLp = binding.doubleTapPill.layoutParams as ConstraintLayout.LayoutParams
        pillLp.horizontalBias = bias
        binding.doubleTapPill.layoutParams = pillLp
        binding.doubleTapPillIcon.setImageResource(
            if (deltaMs < 0) R.drawable.ic_replay_10 else R.drawable.ic_forward_10
        )
        binding.doubleTapPillText.text =
            if (deltaMs < 0) "-${-deltaMs / 1000}s" else "+${deltaMs / 1000}s"
        binding.doubleTapRipple.apply {
            visibility = View.VISIBLE
            alpha = 0.45f
            animate().cancel()
            animate().alpha(0f).setDuration(450L)
                .withEndAction { visibility = View.GONE }.start()
        }
        binding.doubleTapPill.apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.85f
            scaleY = 0.85f
            animate().cancel()
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
        }
        doubleTapHideHandler.removeCallbacks(hideDoubleTapRunnable)
        doubleTapHideHandler.postDelayed(hideDoubleTapRunnable, 700L)
    }

    private fun cancelHoldPending() {
        if (holdPending) {
            holdPending = false
            holdHandler.removeCallbacks(holdRunnable)
        }
    }

    /**
     * Hold-to-2x: press-and-hold on the player surface temporarily
     * plays at 2.0x with the top-center "2x Speed »" pill. Release
     * restores the speed the user had selected (not always 1.0x).
     * Only the bare surface arms it — control buttons are child
     * views and never reach the PlayerView touch listener.
     */
    private fun startHold2x() {
        holdPending = false
        if (locked || player == null) return
        if (scrollMode != SCROLL_NONE) return
        speedIndexBeforeHold = currentSpeedIndex
        holdSpeedActive = true
        player?.playbackParameters = PlaybackParameters(2.0f)
        ctrlSpeedLabel.text = "2.0x"
        binding.holdSpeedPill.visibility = View.VISIBLE
        binding.playerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun endHold2x() {
        if (!holdSpeedActive) return
        holdSpeedActive = false
        binding.holdSpeedPill.visibility = View.GONE
        val restore = speedIndexBeforeHold.coerceIn(speeds.indices)
        speedIndexBeforeHold = -1
        setSpeedQuiet(restore)
    }

    /**
     * Seekbar floating preview on the stock Media3 DefaultTimeBar —
     * no custom TimeBar. While scrubbing, a dark pill floats above
     * the thumb with the exact target timestamp (mm:ss / total) and
     * the delta vs the position where the scrub started (+02:15 /
     * −01:10); it hides on scrub end.
     */
    private fun setupSeekPreview() {
        val timeBar = binding.playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
            ?: return
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                scrubStartPositionMs = player?.currentPosition ?: 0L
                showSeekPreview(timeBar, position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                showSeekPreview(timeBar, position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                binding.seekPreviewPill.visibility = View.GONE
            }
        })
    }

    private fun showSeekPreview(timeBar: TimeBar, positionMs: Long) {
        val exo = player ?: return
        val durationMs = exo.duration.coerceAtLeast(0L)
        val target = positionMs.coerceIn(0L, durationMs)
        val total = if (durationMs > 0) {
            FormatUtils.formatDuration((durationMs / 1000L).toInt())
        } else {
            "--:--"
        }
        val deltaSec = ((target - scrubStartPositionMs) / 1000L).toInt()
        val delta = if (deltaSec == 0) {
            ""
        } else {
            val sign = if (deltaSec > 0) "+" else "−"
            "  $sign${FormatUtils.formatDuration(kotlin.math.abs(deltaSec))}"
        }
        binding.seekPreviewPill.text =
            "${FormatUtils.formatDuration((target / 1000L).toInt())} / $total$delta"
        binding.seekPreviewPill.visibility = View.VISIBLE
        // Slide the pill to sit above the thumb. The TimeBar lives
        // inside the PlayerView, which spans the full width, so the
        // thumb x maps 1:1 onto the activity root; clamp the shift so
        // the pill never runs off-screen.
        binding.seekPreviewPill.post {
            val barView = timeBar as View
            val fraction = if (durationMs > 0) target.toFloat() / durationMs else 0f
            val thumbX = barView.left + fraction * barView.width
            val halfPill = binding.seekPreviewPill.width / 2f
            val center = binding.root.width / 2f
            val shift = (thumbX - center)
                .coerceIn(-center + halfPill + 8f, center - halfPill - 8f)
            binding.seekPreviewPill.translationX = shift
        }
    }

    override fun onPause() {
        super.onPause()
        savePositionNow()
        // v8 §4.4: leaving the player (incl. PiP) cancels the Up Next
        // countdown — it must never fire playAt while the user is gone.
        hideUpNextCard()
        // Don't pause when entering picture-in-picture — the video keeps playing there.
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        optionsSheet?.dismiss()
        optionsSheet = null
        bufferHandler.removeCallbacks(bufferUpdater)
        feedbackHandler.removeCallbacks(hideFeedback)
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        // v6: cancel the stall retry + offline timer + subtitle
        // discovery so nothing fires after the activity is gone.
        cancelStallRetry()
        cancelOfflineTimer()
        subtitleDiscoveryJob?.cancel()
        // v8 §4.4: cancel the Up Next countdown, a pending/active
        // hold-to-2x and the double-tap hide timer.
        hideUpNextCard()
        cancelHoldPending()
        if (holdSpeedActive) endHold2x()
        doubleTapHideHandler.removeCallbacks(hideDoubleTapRunnable)
        savePositionNow()
        stopSaveTicker()
        player?.release()
        player = null
    }
}
