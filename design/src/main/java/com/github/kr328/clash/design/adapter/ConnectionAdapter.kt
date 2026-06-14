package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.databinding.AdapterConnectionGroupBinding
import com.github.kr328.clash.design.databinding.AdapterConnectionItemBinding
import com.github.kr328.clash.design.util.layoutInflater

sealed class ConnectionItem {
    data class Group(
        val packageName: String,
        val appName: String,
        val appIcon: android.graphics.drawable.Drawable?,
        val activeCount: Int,
        val totalCount: Int,
        val totalSpeed: String,
        val totalUpload: Long,
        val totalDownload: Long,
        val isExpanded: Boolean
    ) : ConnectionItem()
    data class Child(val connection: Connection, val speed: String, val isActive: Boolean) : ConnectionItem()
}

class ConnectionAdapter(
    private val context: Context,
    private val onGroupClick: (String) -> Unit,
    private val onClick: (Connection) -> Unit
) : ListAdapter<ConnectionItem, RecyclerView.ViewHolder>(ConnectionDiffCallback()) {

    class ConnectionDiffCallback : DiffUtil.ItemCallback<ConnectionItem>() {
        override fun areItemsTheSame(oldItem: ConnectionItem, newItem: ConnectionItem): Boolean {
            if (oldItem is ConnectionItem.Group && newItem is ConnectionItem.Group) {
                return oldItem.packageName == newItem.packageName
            }
            if (oldItem is ConnectionItem.Child && newItem is ConnectionItem.Child) {
                return oldItem.connection.id == newItem.connection.id
            }
            return false
        }

        override fun areContentsTheSame(oldItem: ConnectionItem, newItem: ConnectionItem): Boolean {
            return oldItem == newItem
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ConnectionItem.Group -> 0
            is ConnectionItem.Child -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> GroupHolder(AdapterConnectionGroupBinding.inflate(context.layoutInflater, parent, false))
            else -> ChildHolder(AdapterConnectionItemBinding.inflate(context.layoutInflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ConnectionItem.Group -> (holder as GroupHolder).bind(item)
            is ConnectionItem.Child -> (holder as ChildHolder).bind(item)
        }
    }

    inner class GroupHolder(private val binding: AdapterConnectionGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val item = currentList[adapterPosition] as? ConnectionItem.Group
                if (item != null) onGroupClick(item.packageName)
            }
        }

        fun bind(item: ConnectionItem.Group) {
            binding.appName.text = item.appName
            val countStr = if (item.activeCount == item.totalCount) {
                "${item.activeCount} Active"
            } else {
                "${item.activeCount} Active / ${item.totalCount} Total"
            }
            binding.connectionCount.text = "$countStr  |  U: ${formatBytes(item.totalUpload)}  D: ${formatBytes(item.totalDownload)}"
            binding.speed.text = item.totalSpeed
            if (item.appIcon != null) {
                binding.appIcon.setImageDrawable(item.appIcon)
            } else {
                binding.appIcon.setImageDrawable(null)
            }
            binding.ivExpandIcon.rotation = if (item.isExpanded) 180f else 0f
        }
    }

    inner class ChildHolder(private val binding: AdapterConnectionItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val item = currentList[adapterPosition] as? ConnectionItem.Child
                if (item != null) onClick(item.connection)
            }
        }

        fun bind(item: ConnectionItem.Child) {
            val metadata = item.connection.metadata
            binding.host.text = if (metadata.host.isNotEmpty()) "${metadata.host}:${metadata.destinationPort}" else "${metadata.destinationIP}:${metadata.destinationPort}"
            val uploadText = formatBytes(item.connection.upload)
            val downloadText = formatBytes(item.connection.download)
            if (item.isActive) {
                binding.info.text = "Rule: ${item.connection.rule} | U: $uploadText  D: $downloadText | Chain: ${item.connection.chains.joinToString(" -> ")}"
                binding.speedOrTotal.text = item.speed
            } else {
                binding.info.text = "Rule: ${item.connection.rule} | U: $uploadText  D: $downloadText"
                binding.speedOrTotal.text = "0 B/s"
            }
            binding.badge.text = if (item.isActive) "ACTIVE" else "CLOSED"
            binding.badge.setBackgroundColor(if (item.isActive) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt())
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.2f GB", gb)
}
