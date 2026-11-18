package com.nobi.nobistream.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ItemContinueBinding
import com.nobi.nobistream.ui.components.PressFeedback

class ContinueAdapter(
    private val onClick: (VideoItem, ImageView) -> Unit,
    private val onThumbNeeded: (VideoItem, ImageView) -> Unit,
    private val metaProvider: (VideoItem) -> String
) : ListAdapter<VideoItem, ContinueAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContinueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemContinueBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            PressFeedback.apply(binding.root)
        }

        fun bind(item: VideoItem) {
            // v9: headline hygiene — leading emoji stripped (display only).
            binding.continueTitle.text = FormatUtils.stripLeadingEmoji(item.title)
            binding.continueMeta.text = metaProvider(item)
            // v8: bottom-right dark duration pill on the thumbnail.
            if (item.durationSec > 0) {
                binding.continueDuration.visibility = View.VISIBLE
                binding.continueDuration.text =
                    FormatUtils.formatDuration(item.durationSec)
            } else {
                binding.continueDuration.visibility = View.GONE
            }
            binding.continueProgress.progress = (item.progressFraction * 100).toInt()
            // v9.1 #27: "left" pill — time remaining, bottom-left.
            // Hidden for live / unknown-duration videos.
            val remaining = (item.durationSec - item.progressSec).coerceAtLeast(0L)
            if (item.durationSec > 0 && !item.watched) {
                binding.continueLeft.visibility = View.VISIBLE
                binding.continueLeft.text = itemView.context.getString(
                    R.string.home_left, FormatUtils.formatDuration(remaining.toInt())
                )
            } else {
                binding.continueLeft.visibility = View.GONE
            }
            onThumbNeeded(item, binding.continueThumb)
            // v9.1 #1: shared-element transition into the player.
            binding.continueThumb.transitionName = "video_thumb_${item.messageId}"
            binding.root.setOnClickListener { onClick(item, binding.continueThumb) }
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
