package com.nobi.nobistream.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ItemContinueBinding
import com.nobi.nobistream.ui.components.PressFeedback

class ContinueAdapter(
    private val onClick: (VideoItem) -> Unit,
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
            binding.continueTitle.text = item.title
            binding.continueMeta.text = metaProvider(item)
            binding.continueProgress.progress = (item.progressFraction * 100).toInt()
            onThumbNeeded(item, binding.continueThumb)
            binding.root.setOnClickListener { onClick(item) }
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
