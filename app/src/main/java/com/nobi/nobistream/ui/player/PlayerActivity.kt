package com.nobi.nobistream.ui.player

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
 * Video player: ExoPlayer over the TDLib data source. Double-tap seeks 10s,
 * side swipes change brightness (left) / volume (right), speed cycles
 * 0.5x-2x, Next / Previous follow the channel list order, and the position
 * is saved every 5s and on pause so playback resumes where it stopped.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_MESSAGE_ID = "message_id"
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

    private val feedbackHandler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable { binding.gestureFeedback.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
        playlist = PlayerPlaylist.items
        index = playlist.indexOfFirst { it.messageId == messageId }.takeIf { it >= 0 } ?: 0
        binding.prevButton.setOnClickListener { playAt(index - 1) }
        binding.nextButton.setOnClickListener { playAt(index + 1) }
        binding.speedButton.setOnClickListener { cycleSpeed() }
        binding.autoplaySwitch.setOnCheckedChangeListener { _, checked ->
            autoplay = checked
            lifecycleScope.launch { repository.settingsStore.setAutoplay(checked) }
        }
        lifecycleScope.launch {
            repository.settingsStore.autoplay.collect { value ->
                autoplay = value
                if (binding.autoplaySwitch.isChecked != value) {
                    binding.autoplaySwitch.isChecked = value
                }
            }
        }
        setupGestures()
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
            if (state == Player.STATE_ENDED) {
                savePositionNow()
                if (autoplay) playAt(index + 1)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startSaveTicker() else stopSaveTicker()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.player_error),
                Toast.LENGTH_LONG
            ).show()
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
        binding.playerTitle.text = item.title
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

    private fun cycleSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speeds.size
        val speed = speeds[currentSpeedIndex]
        player?.playbackParameters = PlaybackParameters(speed)
        binding.speedButton.text = "${speed}x"
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
                    val view = binding.playerView
                    if (view.isControllerFullyVisible) view.hideController() else view.showController()
                    gestureHandled = true
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val exo = player ?: return true
                    val width = binding.playerView.width
                    if (e.x < width / 2f) {
                        exo.seekTo(maxOf(0L, exo.currentPosition - 10_000L))
                        showFeedback("-10s")
                    } else {
                        exo.seekTo(exo.currentPosition + 10_000L)
                        showFeedback("+10s")
                    }
                    gestureHandled = true
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    val view = binding.playerView
                    val x = e2.x
                    if (x < view.width / 3f) {
                        adjustBrightness(distanceY)
                    } else if (x > view.width * 2f / 3f) {
                        adjustVolume(distanceY)
                    }
                    return true
                }
            }
        )
        binding.playerView.setOnTouchListener { _, event ->
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
        showFeedback("Brightness ${(next * 100).toInt()}%")
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
        showFeedback("Volume $next / $max")
    }

    private fun showFeedback(text: String) {
        binding.gestureFeedback.text = text
        binding.gestureFeedback.visibility = View.VISIBLE
        feedbackHandler.removeCallbacks(hideFeedback)
        feedbackHandler.postDelayed(hideFeedback, 900L)
    }

    override fun onPause() {
        super.onPause()
        savePositionNow()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePositionNow()
        stopSaveTicker()
        player?.release()
        player = null
    }
}
