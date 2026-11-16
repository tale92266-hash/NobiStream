package com.nobi.nobistream.ui.channel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.core.VideoQuality
import com.nobi.nobistream.databinding.ItemVideoBinding

class VideoAdapter(
    private val onClick: (VideoItem, ImageView) -> Unit,
    private val onThumbNeeded: (VideoItem, ImageView) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VideoItem) {
            binding.videoTitle.text = item.title
            val date = FormatUtils.formatDate(item.dateEpochSec)
            val size = FormatUtils.formatSize(item.sizeBytes)
            binding.videoMeta.text = if (date.isEmpty()) size else "$date • $size"
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
            binding.watchedBadge.visibility = if (item.watched) View.VISIBLE else View.GONE
            if (item.progressSec > 0L && !item.watched) {
                binding.videoProgress.visibility = View.VISIBLE
                binding.videoProgress.progress = (item.progressFraction * 100).toInt()
            } else {
                binding.videoProgress.visibility = View.GONE
            }
            onThumbNeeded(item, binding.videoThumb)
            binding.videoThumb.transitionName = "video_thumb_${item.messageId}"
            binding.root.setOnClickListener { onClick(item, binding.videoThumb) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(a: VideoItem, b: VideoItem) =
                a.chatId == b.chatId && a.messageId == b.messageId

            override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
        }
    }
}
