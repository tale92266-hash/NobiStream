package com.nobi.nobistream.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nobi.nobistream.R
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.databinding.ItemGroupBinding
import java.io.File

class GroupAdapter(
    private val onClick: (GroupItem) -> Unit,
    private val onLongClick: (GroupItem) -> Unit
) : ListAdapter<GroupItem, GroupAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GroupItem) {
            binding.groupTitle.text = item.title
            binding.groupSubtitle.text = itemView.context.getString(
                R.string.home_videos_count, item.videoCount
            )
            val photoPath = item.photoLocalPath
            if (!photoPath.isNullOrBlank() && File(photoPath).exists()) {
                binding.groupPhoto.load(File(photoPath)) {
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                }
            } else {
                binding.groupPhoto.setImageResource(R.drawable.ic_video_placeholder)
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item); true }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GroupItem>() {
            override fun areItemsTheSame(a: GroupItem, b: GroupItem) = a.chatId == b.chatId
            override fun areContentsTheSame(a: GroupItem, b: GroupItem) = a == b
        }
    }
}
