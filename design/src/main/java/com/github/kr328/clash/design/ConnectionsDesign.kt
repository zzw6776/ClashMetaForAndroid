package com.github.kr328.clash.design

import android.content.Context
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class ConnectionsDesign(context: Context, private val uiStore: UiStore) : Design<ConnectionsDesign.Request>(context) {
    enum class Request {
        Close,
        ClearConnections,
        FilterChanged,
        ProcessFilterClicked,
        RefreshIntervalChanged
    }

    enum class SortType {
        TIME, NAME, SPEED_DOWN, SPEED_UP, TRAFFIC_DOWN, TRAFFIC_UP
    }

    val binding = DesignConnectionsBinding.inflate(context.layoutInflater, context.root, false)

    var sortType: SortType = sortTypeFromStore(uiStore.connectionSortType)
        private set

    val filterActive: Boolean
        get() = binding.chipActive.isChecked

    val filterClosed: Boolean
        get() = binding.chipClosed.isChecked

    val refreshIntervalMillis: Long
        get() = uiStore.connectionRefreshIntervalMillis.toLong()

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.toolbar.setNavigationOnClickListener {
            requests.trySend(Request.Close)
        }
        val refreshItem = binding.toolbar.menu.add(0, TOOLBAR_REFRESH_ID, 0, refreshIntervalShortLabel(uiStore.connectionRefreshIntervalMillis))
        refreshItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)

        val clearItem = binding.toolbar.menu.add(0, TOOLBAR_CLEAR_ID, 1, context.getString(R.string.connections_clear))
        clearItem.setIcon(R.drawable.ic_baseline_clear_all)
        clearItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                TOOLBAR_REFRESH_ID -> {
                    showRefreshMenu(binding.toolbar, refreshItem)
                    true
                }
                TOOLBAR_CLEAR_ID -> {
                    requests.trySend(Request.ClearConnections)
                    true
                }
                else -> false
            }
        }
        binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)

        binding.chipActive.isChecked = uiStore.connectionFilterActive
        binding.chipClosed.isChecked = uiStore.connectionFilterClosed
        binding.chipSort.text = sortTypeLabel(sortType)

        binding.chipActive.setOnCheckedChangeListener { _, _ ->
            uiStore.connectionFilterActive = binding.chipActive.isChecked
            requests.trySend(Request.FilterChanged)
        }
        binding.chipClosed.setOnCheckedChangeListener { _, _ ->
            uiStore.connectionFilterClosed = binding.chipClosed.isChecked
            requests.trySend(Request.FilterChanged)
        }
        binding.chipProcess.setOnClickListener {
            requests.trySend(Request.ProcessFilterClicked)
        }

        binding.chipSort.setOnClickListener { view ->
            val popup = PopupMenu(context, view)
            popup.menu.add(0, 0, 0, context.getString(R.string.connections_sort_time))
            popup.menu.add(0, 1, 1, context.getString(R.string.connections_sort_name))
            popup.menu.add(0, 2, 2, context.getString(R.string.connections_sort_download_speed))
            popup.menu.add(0, 3, 3, context.getString(R.string.connections_sort_upload_speed))
            popup.menu.add(0, 4, 4, context.getString(R.string.connections_sort_total_download))
            popup.menu.add(0, 5, 5, context.getString(R.string.connections_sort_total_upload))
            popup.setOnMenuItemClickListener { item ->
                sortType = when (item.itemId) {
                    1 -> SortType.NAME
                    2 -> SortType.SPEED_DOWN
                    3 -> SortType.SPEED_UP
                    4 -> SortType.TRAFFIC_DOWN
                    5 -> SortType.TRAFFIC_UP
                    else -> SortType.TIME
                }
                uiStore.connectionSortType = sortType.ordinal
                binding.chipSort.text = sortTypeLabel(sortType)
                requests.trySend(Request.FilterChanged)
                true
            }
            popup.show()
        }
    }

    fun setProcessFilterLabel(label: String?) {
        binding.chipProcess.text = label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.connections_process_all)
    }

    fun setAdapter(adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>) {
        binding.recyclerView.adapter = adapter
    }

    private fun showRefreshMenu(anchor: View, refreshItem: MenuItem) {
        val popup = PopupMenu(context, anchor)
        REFRESH_INTERVALS.forEachIndexed { index, interval ->
            popup.menu.add(0, interval, index, refreshIntervalLabel(interval))
        }
        popup.setOnMenuItemClickListener { item ->
            uiStore.connectionRefreshIntervalMillis = item.itemId
            refreshItem.title = refreshIntervalShortLabel(item.itemId)
            requests.trySend(Request.RefreshIntervalChanged)
            true
        }
        popup.show()
    }

    private fun refreshIntervalLabel(intervalMillis: Int): String {
        return when (intervalMillis) {
            500 -> context.getString(R.string.connections_refresh_500ms)
            2000 -> context.getString(R.string.connections_refresh_2s)
            5000 -> context.getString(R.string.connections_refresh_5s)
            else -> context.getString(R.string.connections_refresh_1s)
        }
    }

    private fun refreshIntervalShortLabel(intervalMillis: Int): String {
        return when (intervalMillis) {
            500 -> "0.5s"
            2000 -> "2s"
            5000 -> "5s"
            else -> "1s"
        }
    }

    private fun sortTypeLabel(type: SortType): String {
        return when (type) {
            SortType.TIME -> context.getString(R.string.connections_sort_time)
            SortType.NAME -> context.getString(R.string.connections_sort_name)
            SortType.SPEED_DOWN -> context.getString(R.string.connections_sort_download_speed)
            SortType.SPEED_UP -> context.getString(R.string.connections_sort_upload_speed)
            SortType.TRAFFIC_DOWN -> context.getString(R.string.connections_sort_total_download)
            SortType.TRAFFIC_UP -> context.getString(R.string.connections_sort_total_upload)
        }
    }

    companion object {
        private const val TOOLBAR_REFRESH_ID = 1
        private const val TOOLBAR_CLEAR_ID = 2
        private val REFRESH_INTERVALS = intArrayOf(500, 1000, 2000, 5000)

        private fun sortTypeFromStore(value: Int): SortType {
            return SortType.values().getOrElse(value) { SortType.TIME }
        }
    }
}
