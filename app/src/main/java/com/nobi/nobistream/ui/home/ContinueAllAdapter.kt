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
import com.nobi.nobistream.databinding.ItemContinueAllBinding
import com.nobi.nobistream.ui.components.PressFeedback

/**
 * v8 "See All" list (spec §4.8): full-width rows, NOT the 200dp
 * Home carousel cards. Left: 128dp-wide 16:9 thumbnail (8dp
 * radius) with a duration pill + 3dp progress bar on it. Right:
 * title (14sp, 2 lines) + meta ("Channel • 12:34 left" via the
 * same continueMeta the Home row uses).
 */
class ContinueAllAdapter(
    private val onClick: (VideoItem, ImageView) -> Unit,
    private val onThumbNeeded: (VideoItem, ImageView) -> Unit,
    private val metaProvider: (VideoItem) -> String
) : ListAdapter<VideoItem, ContinueAllAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContinueAllBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemContinueAllBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            PressFeedback.apply(binding.root)
        }

        fun bind(item: VideoItem) {
            // v9: headline hygiene — leading emoji stripped (display only).
            binding.continueAllTitle.text = FormatUtils.stripLeadingEmoji(item.title)
            binding.continueAllMeta.text = metaProvider(item)
            if (item.durationSec > 0) {
                binding.continueAllDuration.visibility = View.VISIBLE
                binding.continueAllDuration.text =
                    FormatUtils.formatDuration(item.durationSec)
            } else {
                binding.continueAllDuration.visibility = View.GONE
            }
            binding.continueAllProgress.progress =
                (item.progressFraction * 100).toInt()
            onThumbNeeded(item, binding.continueAllThumb)
            // v9.1 #1: shared-element transition into the player.
            binding.continueAllThumb.transitionName = "video_thumb_${item.messageId}"
            binding.root.setOnClickListener { onClick(item, binding.continueAllThumb) }
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
