package com.nobi.nobistream.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nobi.nobistream.databinding.ItemRecentSearchBinding

/** v4 recent-search rows: one query per row, tap runs it again. */
class RecentSearchAdapter(
    private val onClick: (String) -> Unit
) : ListAdapter<String, RecentSearchAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentSearchBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRecentSearchBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(query: String) {
            binding.recentQuery.text = query
            binding.root.setOnClickListener { onClick(query) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}
