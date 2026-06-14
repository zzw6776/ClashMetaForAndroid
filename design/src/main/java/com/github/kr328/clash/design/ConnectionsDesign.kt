package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class ConnectionsDesign(context: Context) : Design<ConnectionsDesign.Request>(context) {
    enum class Request {
        Close,
        FilterChanged
    }

    enum class SortType {
        DEFAULT, NAME, SPEED_DOWN, SPEED_UP, TRAFFIC_DOWN, TRAFFIC_UP
    }

    val binding = DesignConnectionsBinding.inflate(context.layoutInflater, context.root, false)

    var sortType: SortType = SortType.DEFAULT
        private set

    val filterActive: Boolean
        get() = binding.chipActive.isChecked

    val filterClosed: Boolean
        get() = binding.chipClosed.isChecked

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.toolbar.setNavigationOnClickListener {
            requests.trySend(Request.Close)
        }
        binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)

        binding.chipActive.setOnCheckedChangeListener { _, _ ->
            requests.trySend(Request.FilterChanged)
        }
        binding.chipClosed.setOnCheckedChangeListener { _, _ ->
            requests.trySend(Request.FilterChanged)
        }

        binding.chipSort.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(context, view)
            popup.menu.add(0, 0, 0, "Default (Time)")
            popup.menu.add(0, 1, 1, "Name")
            popup.menu.add(0, 2, 2, "Download Speed")
            popup.menu.add(0, 3, 3, "Upload Speed")
            popup.menu.add(0, 4, 4, "Total Download")
            popup.menu.add(0, 5, 5, "Total Upload")
            popup.setOnMenuItemClickListener { item ->
                sortType = when (item.itemId) {
                    1 -> SortType.NAME
                    2 -> SortType.SPEED_DOWN
                    3 -> SortType.SPEED_UP
                    4 -> SortType.TRAFFIC_DOWN
                    5 -> SortType.TRAFFIC_UP
                    else -> SortType.DEFAULT
                }
                binding.chipSort.text = "Sort: ${item.title}"
                requests.trySend(Request.FilterChanged)
                true
            }
            popup.show()
        }
    }

    fun setAdapter(adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>) {
        binding.recyclerView.adapter = adapter
    }
}
