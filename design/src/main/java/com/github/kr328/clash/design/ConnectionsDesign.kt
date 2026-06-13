package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class ConnectionsDesign(context: Context) : Design<ConnectionsDesign.Request>(context) {
    enum class Request {
        Close
    }

    val binding = DesignConnectionsBinding.inflate(context.layoutInflater, context.root, false)

    val filterActive: Boolean
        get() = binding.chipActive.isChecked

    val filterClosed: Boolean
        get() = binding.chipClosed.isChecked

    override val root: View
        get() = binding.root

    init {
        binding.toolbar.setNavigationOnClickListener {
            requests.trySend(Request.Close)
        }
        binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
    }

    fun setAdapter(adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>) {
        binding.recyclerView.adapter = adapter
    }
}
