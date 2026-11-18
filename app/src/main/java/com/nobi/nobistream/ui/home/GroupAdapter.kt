package com.nobi.nobistream.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nobi.nobistream.R
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.databinding.ItemGroupBinding
import com.nobi.nobistream.ui.components.PressFeedback
import java.io.File

class GroupAdapter(
    private val onClick: (GroupItem) -> Unit,
    private val onLongClick: (GroupItem, android.view.View) -> Unit,
    /**
     * v9.1 #19: NEW dot — true when the group has videos the user
     * has not seen yet (last-seen count exists AND is smaller than
     * the current count). The host supplies it from SettingsStore;
     * null = no dots.
     */
    private val isNewProvider: ((GroupItem) -> Boolean)? = null
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

        init {
            PressFeedback.apply(binding.root)
        }

        fun bind(item: GroupItem) {
            binding.groupTitle.text = item.title
            val count = itemView.context.getString(R.string.home_videos_count, item.videoCount)
            val date = FormatUtils.formatDate(item.lastUpdatedEpochSec)
            binding.groupSubtitle.text = if (date.isEmpty()) {
                count
            } else {
                "$count • ${itemView.context.getString(R.string.home_group_updated, date)}"
            }
            // v9.1 #19: letter avatar — the first letter/digit of the
            // title, on a tonal circle. The photo covers it when one
            // loads; when there is no photo (or the load fails) the
            // letter stays visible so the row never shows a grey box.
            val letter = item.title.firstOrNull { it.isLetterOrDigit() }
                ?.uppercaseChar()?.toString() ?: "?"
            binding.groupLetter.text = letter
            val photoPath = item.photoLocalPath
            if (!photoPath.isNullOrBlank() && File(photoPath).exists()) {
                binding.groupPhoto.load(File(photoPath)) {
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                    crossfade(200)
                    listener(
                        onSuccess = { _, _ -> binding.groupLetter.visibility = View.GONE },
                        onError = { _, _ ->
                            binding.groupPhoto.setImageDrawable(null)
                            binding.groupLetter.visibility = View.VISIBLE
                        }
                    )
                }
            } else {
                binding.groupPhoto.setImageDrawable(null)
                binding.groupLetter.visibility = View.VISIBLE
            }
            // v9.1 #19: NEW dot — top-right of the avatar.
            binding.groupNewDot.visibility =
                if (isNewProvider?.invoke(item) == true) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item, binding.root); true }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GroupItem>() {
            override fun areItemsTheSame(a: GroupItem, b: GroupItem) = a.chatId == b.chatId
            override fun areContentsTheSame(a: GroupItem, b: GroupItem) = a == b
        }
    }
}
