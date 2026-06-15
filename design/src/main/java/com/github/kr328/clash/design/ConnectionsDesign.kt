package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.switchmaterial.SwitchMaterial

class ConnectionsDesign(context: Context, private val uiStore: UiStore) : Design<ConnectionsDesign.Request>(context) {
    enum class Request {
        Close,
        ClearConnections,
        FilterChanged,
        ProcessFilterClicked,
        ProxyFilterClicked,
        RefreshIntervalChanged,
        TrackingChanged,
        ToggleExpandCollapse
    }

    enum class SortType {
        TIME, NAME, SPEED_DOWN, SPEED_UP, TRAFFIC_DOWN, TRAFFIC_UP
    }

    val binding = DesignConnectionsBinding.inflate(context.layoutInflater, context.root, false)
    private var statusFilterPopup: PopupWindow? = null
    private val trackingSwitch = SwitchMaterial(context).apply {
        text = null
        contentDescription = context.getString(R.string.connections_tracking)
    }

    var sortType: SortType = sortTypeFromStore(uiStore.connectionSortType)
        private set

    val filterActive: Boolean
        get() = uiStore.connectionFilterActive

    val filterClosed: Boolean
        get() = uiStore.connectionFilterClosed

    val filterFailed: Boolean
        get() = uiStore.connectionFilterFailed

    val refreshIntervalMillis: Long
        get() = uiStore.connectionRefreshIntervalMillis.toLong()

    val trackingEnabled: Boolean
        get() = trackingSwitch.isChecked

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.toolbar.setNavigationOnClickListener {
            requests.trySend(Request.Close)
        }
        val trackingItem = binding.toolbar.menu.add(0, TOOLBAR_TRACKING_ID, 0, context.getString(R.string.connections_tracking))
        trackingItem.actionView = trackingSwitch
        trackingItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        val refreshItem = binding.toolbar.menu.add(0, TOOLBAR_REFRESH_ID, 1, refreshIntervalShortLabel(uiStore.connectionRefreshIntervalMillis))
        refreshItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)

        val clearItem = binding.toolbar.menu.add(0, TOOLBAR_CLEAR_ID, 2, context.getString(R.string.connections_clear))
        clearItem.setIcon(R.drawable.ic_baseline_clear_all)
        clearItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        val expandCollapseItem = binding.toolbar.menu.add(0, TOOLBAR_EXPAND_COLLAPSE_ID, 3, "展开/收起")
        expandCollapseItem.setIcon(R.drawable.ic_baseline_unfold_more)
        expandCollapseItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

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
                TOOLBAR_EXPAND_COLLAPSE_ID -> {
                    requests.trySend(Request.ToggleExpandCollapse)
                    true
                }
                else -> false
            }
        }
        binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)

        trackingSwitch.isChecked = uiStore.connectionTrackingEnabled
        updateStatusFilterLabel()
        binding.chipSort.text = sortTypeLabel(sortType)
        setTrackingControlsEnabled(trackingSwitch.isChecked)

        trackingSwitch.setOnCheckedChangeListener { _, checked ->
            uiStore.connectionTrackingEnabled = checked
            setTrackingControlsEnabled(checked)
            requests.trySend(Request.TrackingChanged)
        }

        binding.chipStatus.setOnClickListener {
            showStatusFilterWindow(binding.chipStatus)
        }
        binding.chipProcess.setOnClickListener {
            requests.trySend(Request.ProcessFilterClicked)
        }
        binding.chipProxy.setOnClickListener {
            requests.trySend(Request.ProxyFilterClicked)
        }

        binding.chipSort.setOnClickListener { view ->
            val popup = PopupMenu(context, view)
            popup.menu.add(0, 0, 0, context.getString(R.string.connections_sort_time))
            popup.menu.add(0, 1, 1, context.getString(R.string.connections_sort_name))
            popup.menu.add(0, 3, 2, context.getString(R.string.connections_sort_upload_speed))
            popup.menu.add(0, 2, 3, context.getString(R.string.connections_sort_download_speed))
            popup.menu.add(0, 5, 4, context.getString(R.string.connections_sort_total_upload))
            popup.menu.add(0, 4, 5, context.getString(R.string.connections_sort_total_download))
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
                binding.recyclerView.scrollToPosition(0)
                true
            }
            popup.show()
        }
    }

    fun setProcessFilterLabel(label: String?) {
        binding.chipProcess.text = label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.connections_process_all)
    }

    fun setProxyFilterLabel(label: String?) {
        binding.chipProxy.text = label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.connections_proxy_all)
    }

    fun setAdapter(adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>) {
        binding.recyclerView.adapter = adapter
    }

    private fun setTrackingControlsEnabled(enabled: Boolean) {
        binding.chipStatus.isEnabled = enabled
        binding.chipProcess.isEnabled = enabled
        binding.chipProxy.isEnabled = enabled
        binding.chipSort.isEnabled = enabled
    }

    private fun showStatusFilterWindow(anchor: View) {
        statusFilterPopup?.dismiss()
        val density = context.resources.displayMetrics.density
        // 将 LinearLayout 的水平 padding 设为 0，使 CheckBox 的点击波纹（Ripple）能平滑铺满至边缘，与系统菜单一致
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }

        fun addOption(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
            // 使用 MaterialCheckBox 确保与 Material Design 风格一致
            val checkBox = com.google.android.material.checkbox.MaterialCheckBox(context).apply {
                text = label
                isChecked = checked
                // 调整最小宽度为 200dp，高度为 48dp（系统菜单默认高度），使其宽度与“代理节点”基本一致
                minWidth = (200 * density).toInt()
                minHeight = (48 * density).toInt()
                // 设置左右内边距为 16dp，文本和图标之间的距离为 12dp
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                compoundDrawablePadding = (12 * density).toInt()
                // 提升字体大小到 16sp，保持与 PopupMenu 一致
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnCheckedChangeListener { _, isChecked ->
                    onChanged(isChecked)
                    updateStatusFilterLabel()
                    requests.trySend(Request.FilterChanged)
                }
            }
            content.addView(checkBox)
        }

        addOption(context.getString(R.string.connections_filter_active_short), uiStore.connectionFilterActive) {
            uiStore.connectionFilterActive = it
        }
        addOption(context.getString(R.string.connections_filter_closed_short), uiStore.connectionFilterClosed) {
            uiStore.connectionFilterClosed = it
        }
        addOption(context.getString(R.string.connections_filter_failed_short), uiStore.connectionFilterFailed) {
            uiStore.connectionFilterFailed = it
        }

        val popup = PopupWindow(context).apply {
            contentView = content
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isFocusable = true
            isOutsideTouchable = true
            // 显式在 PopupWindow 级别设置 elevation，在 Android 5.0+ 下会绘制物理轮廓阴影，与系统的 PopupMenu 一致
            elevation = 8 * density

            val shapeDrawable = com.google.android.material.shape.MaterialShapeDrawable(
                com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCornerSizes(8 * density)
                    .build()
            ).apply {
                fillColor = android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurface)
                )
                elevation = 8 * density
            }
            setBackgroundDrawable(shapeDrawable)

            val attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    dismiss()
                }
            }
            binding.root.addOnAttachStateChangeListener(attachListener)
            setOnDismissListener {
                binding.root.removeOnAttachStateChangeListener(attachListener)
                if (statusFilterPopup == this) {
                    statusFilterPopup = null
                }
            }

            showAsDropDown(anchor)
        }
        statusFilterPopup = popup
    }

    private fun updateStatusFilterLabel() {
        val selected = listOfNotNull(
            context.getString(R.string.connections_filter_active_short).takeIf { uiStore.connectionFilterActive },
            context.getString(R.string.connections_filter_closed_short).takeIf { uiStore.connectionFilterClosed },
            context.getString(R.string.connections_filter_failed_short).takeIf { uiStore.connectionFilterFailed }
        )
        binding.chipStatus.text = when (selected.size) {
            3 -> context.getString(R.string.connections_status_all)
            1, 2 -> selected.joinToString(", ")
            else -> context.getString(R.string.connections_status_count, 0)
        }
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
        private const val TOOLBAR_TRACKING_ID = 1
        private const val TOOLBAR_REFRESH_ID = 2
        private const val TOOLBAR_CLEAR_ID = 3
        private const val TOOLBAR_EXPAND_COLLAPSE_ID = 4
        private val REFRESH_INTERVALS = intArrayOf(500, 1000, 2000, 5000)

        private fun sortTypeFromStore(value: Int): SortType {
            return SortType.values().getOrElse(value) { SortType.TIME }
        }
    }
}
