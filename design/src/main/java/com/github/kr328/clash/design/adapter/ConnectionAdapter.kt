package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterConnectionGroupBinding
import com.github.kr328.clash.design.databinding.AdapterConnectionItemBinding
import com.github.kr328.clash.design.util.formatBytes
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.color.MaterialColors
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.graphics.Color

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

private fun formatTotalTraffic(downloadText: String, uploadText: String): CharSequence {
    val downStr = "Dn $downloadText"
    val upStr = "Up $uploadText"
    val builder = SpannableStringBuilder()
    builder.append(downStr)
    builder.setSpan(ForegroundColorSpan(Color.parseColor("#2196F3")), 0, downStr.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    builder.append("  ")
    val start = builder.length
    builder.append(upStr)
    builder.setSpan(ForegroundColorSpan(Color.parseColor("#4CAF50")), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return builder
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
            return when {
                oldItem is ConnectionItem.Group && newItem is ConnectionItem.Group -> {
                    oldItem.packageName == newItem.packageName &&
                        oldItem.appName == newItem.appName &&
                        oldItem.activeCount == newItem.activeCount &&
                        oldItem.totalCount == newItem.totalCount &&
                        oldItem.totalSpeed == newItem.totalSpeed &&
                        oldItem.totalUpload == newItem.totalUpload &&
                        oldItem.totalDownload == newItem.totalDownload &&
                        oldItem.isExpanded == newItem.isExpanded
                }
                oldItem is ConnectionItem.Child && newItem is ConnectionItem.Child -> oldItem == newItem
                else -> false
            }
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
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position >= currentList.size) return@setOnClickListener

                val item = currentList[position] as? ConnectionItem.Group
                if (item != null) onGroupClick(item.packageName)
            }
        }

        fun bind(item: ConnectionItem.Group) {
            val groupAlpha = if (item.activeCount == 0) 0.55f else 1f
            binding.appName.text = "${item.appName} · ${item.activeCount}/${item.totalCount}"
            binding.speed.text = item.totalSpeed
            binding.connectionCount.text = formatTotalTraffic(
                formatBytes(item.totalDownload),
                formatBytes(item.totalUpload)
            )
            binding.appName.alpha = groupAlpha
            binding.speed.alpha = groupAlpha
            binding.connectionCount.alpha = groupAlpha
            binding.appIcon.alpha = groupAlpha
            binding.ivExpandIcon.alpha = groupAlpha
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
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position >= currentList.size) return@setOnClickListener

                val item = currentList[position] as? ConnectionItem.Child
                if (item != null) onClick(item.connection)
            }
        }

        fun bind(item: ConnectionItem.Child) {
            val metadata = item.connection.metadata
            binding.host.text = if (metadata.host.isNotEmpty()) "${metadata.host}:${metadata.destinationPort}" else "${metadata.destinationIP}:${metadata.destinationPort}"
            val uploadText = formatBytes(item.connection.upload)
            val downloadText = formatBytes(item.connection.download)
            val totalText = formatTotalTraffic(downloadText, uploadText)
            val ruleText = formatRuleText(item.connection)
            val chainText = item.connection.chains.joinToString(" -> ")
            val infoText = listOf(ruleText, chainText)
                .filter { it.isNotBlank() }
                .joinToString("  ")
                .ifBlank { "N/A" }
            if (item.isActive) {
                binding.info.text = infoText
                binding.speedOrTotal.text = item.speed
            } else {
                binding.info.text = infoText
                binding.speedOrTotal.text = "↓ 0 B/s  ↑ 0 B/s"
            }
            binding.total.text = totalText
            binding.badge.text = if (item.isActive) "ACTIVE" else "CLOSED"
            if (item.isActive) {
                binding.badge.setBackgroundColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
                )
                binding.badge.setTextColor(
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary)
                )
            } else {
                val onSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
                binding.badge.setBackgroundColor(ColorUtils.setAlphaComponent(onSurface, 0x1F))
                binding.badge.setTextColor(onSurface)
            }
        }
    }

    private fun formatRuleText(connection: Connection): String {
        return when {
            connection.rule.isBlank() -> ""
            connection.rulePayload.isNotEmpty() -> "${connection.rule} (${connection.rulePayload})"
            else -> connection.rule
        }
    }
}
