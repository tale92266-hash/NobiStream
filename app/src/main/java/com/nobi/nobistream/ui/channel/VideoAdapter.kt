package com.nobi.nobistream.ui.channel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nobi.nobistream.R
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.core.VideoQuality
import com.nobi.nobistream.databinding.ItemLibraryHeaderBinding
import com.nobi.nobistream.databinding.ItemVideoBinding
import com.nobi.nobistream.ui.components.PressFeedback

/**
 * v9.1 #8: the feed is rows now, not just videos — a Library
 * section header (channel name + count, full-width) or a video
 * card. Channel / Search still submit plain videos (every row is an
 * Item); the Library submits sectioned rows via [submitLibraryRows].
 * Headers span the full grid width — the host sets a SpanSizeLookup
 * that asks [isHeader] (see MainActivity).
 */
sealed interface VideoRow {
    data class Header(val title: String, val count: Int) : VideoRow
    data class Item(val video: VideoItem) : VideoRow
}

class VideoAdapter(
    private val onClick: (VideoItem, ImageView) -> Unit,
    private val onThumbNeeded: (VideoItem, ImageView) -> Unit,
    /**
     * v5 Smart Filter: tapped when the "N copies" badge on a card is
     * pressed. Receives the best copy and its thumbnail so the
     * caller can show the copies picker with the shared-element
     * transition. Null where no copies picker exists.
     */
    private val onCopiesClick: ((VideoItem, ImageView) -> Unit)? = null,
    /**
     * v8: when true the meta line leads with the channel name
     * ("Channel Name • 12 Nov • 1.4 GB") — Search / Library, where
     * cards mix channels. Channel itself leaves this off (the
     * channel name would just repeat on every card).
     * v9.1 #8: with the channel name the meta is TWO lines —
     * channel on line 1, date • size on line 2.
     */
    private val showChannelName: Boolean = false,
    /**
     * v8: resolves a channel name for a video. Only read when
     * [showChannelName] is true; blank means "no name, fall back
     * to the plain date • size meta".
     */
    private val channelNameProvider: ((VideoItem) -> String)? = null,
    /**
     * v8: true when the video file is fully cached locally — the
     * card then shows the cached check badge (top-left). Callers
     * pass a free cache read
     * (locator.gateway.getCachedFileState(item.fileId)?.isComplete),
     * never a download, so bind stays cheap.
     */
    private val cachedProvider: ((VideoItem) -> Boolean)? = null
) : ListAdapter<VideoRow, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        private val DIFF = object : DiffUtil.ItemCallback<VideoRow>() {
            override fun areItemsTheSame(a: VideoRow, b: VideoRow): Boolean {
                return when {
                    a is VideoRow.Header && b is VideoRow.Header -> a.title == b.title
                    a is VideoRow.Item && b is VideoRow.Item ->
                        a.video.chatId == b.video.chatId &&
                            a.video.messageId == b.video.messageId
                    else -> false
                }
            }

            override fun areContentsTheSame(a: VideoRow, b: VideoRow) = a == b
        }
    }

    /** Plain video list (Channel / Search) — every row is an Item. */
    fun submitVideos(videos: List<VideoItem>) {
        submitList(videos.map { VideoRow.Item(it) })
    }

    /** Sectioned library rows (headers + items, in sort order). */
    fun submitLibraryRows(rows: List<VideoRow>) {
        submitList(rows)
    }

    /** True when the row at [position] is a section header (full grid span). */
    fun isHeader(position: Int): Boolean {
        if (position < 0 || position >= itemCount) return false
        return getItem(position) is VideoRow.Header
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is VideoRow.Header -> TYPE_HEADER
            is VideoRow.Item -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val binding = ItemLibraryHeaderBinding.inflate(inflater, parent, false)
            HeaderViewHolder(binding)
        } else {
            val binding = ItemVideoBinding.inflate(inflater, parent, false)
            VideoViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is VideoRow.Header -> (holder as HeaderViewHolder).bind(row)
            is VideoRow.Item -> (holder as VideoViewHolder).bind(row.video)
        }
    }

    inner class HeaderViewHolder(private val binding: ItemLibraryHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(header: VideoRow.Header) {
            binding.libraryHeaderTitle.text = header.title
            binding.libraryHeaderCount.text = itemView.context.getString(
                R.string.home_videos_count, header.count
            )
        }
    }

    inner class VideoViewHolder(private val binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            PressFeedback.apply(binding.root)
        }

        fun bind(item: VideoItem) {
            // v9: headline hygiene — leading emoji stripped (display only).
            binding.videoTitle.text = FormatUtils.stripLeadingEmoji(item.title)
            val date = FormatUtils.formatDate(item.dateEpochSec)
            val size = FormatUtils.formatSize(item.sizeBytes)
            val dateSize = if (date.isEmpty()) size else "$date • $size"
            // v8: Search / Library cards lead with the channel name.
            // v9.1 #8: two-line meta when the name shows.
            val channelName = if (showChannelName) {
                channelNameProvider?.invoke(item).orEmpty()
            } else {
                ""
            }
            if (channelName.isNotEmpty()) {
                binding.videoMeta.maxLines = 2
                binding.videoMeta.text = "$channelName\n$dateSize"
            } else {
                binding.videoMeta.maxLines = 1
                binding.videoMeta.text = dateSize
            }
            if (item.quality != VideoQuality.UNKNOWN) {
                binding.qualityBadge.visibility = View.VISIBLE
                binding.qualityBadge.text = item.quality.label
            } else {
                binding.qualityBadge.visibility = View.GONE
            }
            if (item.durationSec > 0) {
                binding.durationBadge.visibility = View.VISIBLE
                binding.durationBadge.text = FormatUtils.formatDuration(item.durationSec)
            } else {
                binding.durationBadge.visibility = View.GONE
            }
            // v9.1 #7: no more "Watched" pill — a watched card shows
            // a FULL progress bar + an accent check-dot at the bar's
            // right end. The duration pill lifts so the dot never
            // sits on it.
            val watched = item.watched
            if (watched) {
                binding.videoProgress.visibility = View.VISIBLE
                binding.videoProgress.progress = 100
                binding.watchedDot.visibility = View.VISIBLE
            } else if (item.progressSec > 0L) {
                binding.videoProgress.visibility = View.VISIBLE
                binding.videoProgress.progress = (item.progressFraction * 100).toInt()
                binding.watchedDot.visibility = View.GONE
            } else {
                binding.videoProgress.visibility = View.GONE
                binding.watchedDot.visibility = View.GONE
            }
            val durationLp = binding.durationBadge.layoutParams as FrameLayout.LayoutParams
            val wantedBottom = binding.root.resources.getDimensionPixelSize(
                if (watched) R.dimen.spacing_24 else R.dimen.spacing_8
            )
            if (durationLp.bottomMargin != wantedBottom) {
                durationLp.bottomMargin = wantedBottom
                binding.durationBadge.layoutParams = durationLp
            }
            // v8: cached badge — only when the file is FULLY
            // downloaded locally (isComplete). A partial / streaming
            // file shows no badge.
            binding.cachedBadge.visibility =
                if (cachedProvider?.invoke(item) == true) View.VISIBLE else View.GONE
            // v5 Smart Filter: "N copies" badge on the thumbnail's
            // top-right corner (quality sits top-left, duration
            // bottom-right). Tapping it opens the copies picker; the
            // badge consumes the tap so the card itself does not open.
            if (item.hiddenCopies > 0 && onCopiesClick != null) {
                binding.copiesBadge.visibility = View.VISIBLE
                binding.copiesBadge.text = itemView.context.getString(
                    R.string.smart_copies_badge, item.hiddenCopies
                )
                binding.copiesBadge.setOnClickListener {
                    onCopiesClick.invoke(item, binding.videoThumb)
                }
            } else {
                binding.copiesBadge.visibility = View.GONE
                binding.copiesBadge.setOnClickListener(null)
            }
            onThumbNeeded(item, binding.videoThumb)
            binding.videoThumb.transitionName = "video_thumb_${item.messageId}"
            binding.root.setOnClickListener { onClick(item, binding.videoThumb) }
        }
    }
}
