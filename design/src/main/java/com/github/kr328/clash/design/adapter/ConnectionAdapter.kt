package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.databinding.AdapterConnectionGroupBinding
import com.github.kr328.clash.design.databinding.AdapterConnectionItemBinding
import com.github.kr328.clash.design.util.layoutInflater

sealed class ConnectionItem {
    data class Group(val packageName: String, val appName: String, val appIcon: android.graphics.drawable.Drawable?, val activeCount: Int, val totalSpeed: String) : ConnectionItem()
    data class Child(val connection: Connection, val speed: String, val isActive: Boolean) : ConnectionItem()
}

class ConnectionAdapter(
    private val context: Context,
    private val onGroupClick: (String) -> Unit,
    private val onClick: (Connection) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<ConnectionItem> = emptyList()

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
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
        when (val item = items[position]) {
            is ConnectionItem.Group -> (holder as GroupHolder).bind(item)
            is ConnectionItem.Child -> (holder as ChildHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class GroupHolder(private val binding: AdapterConnectionGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val item = items[adapterPosition] as? ConnectionItem.Group
                if (item != null) onGroupClick(item.packageName)
            }
        }

        fun bind(item: ConnectionItem.Group) {
            binding.appName.text = item.appName
            binding.connectionCount.text = "${item.activeCount} Active"
            binding.speed.text = item.totalSpeed
            if (item.appIcon != null) {
                binding.appIcon.setImageDrawable(item.appIcon)
            } else {
                binding.appIcon.setImageDrawable(null)
            }
        }
    }

    inner class ChildHolder(private val binding: AdapterConnectionItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val item = items[adapterPosition] as? ConnectionItem.Child
                if (item != null) onClick(item.connection)
            }
        }

        fun bind(item: ConnectionItem.Child) {
            val metadata = item.connection.metadata
            binding.host.text = if (metadata.host.isNotEmpty()) "${metadata.host}:${metadata.destinationPort}" else "${metadata.destinationIP}:${metadata.destinationPort}"
            binding.info.text = "Rule: ${item.connection.rule} | Chain: ${item.connection.chains.joinToString(" -> ")}"
            binding.speedOrTotal.text = item.speed
            binding.badge.text = if (item.isActive) "ACTIVE" else "CLOSED"
            binding.badge.setBackgroundColor(if (item.isActive) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt())
        }
    }
}
