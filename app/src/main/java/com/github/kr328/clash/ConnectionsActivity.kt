package com.github.kr328.clash

import android.content.Intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.ConnectionsDesign
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import com.github.kr328.clash.service.remote.IConnectionObserver
import com.github.kr328.clash.core.model.ConnectionDiff

class ConnectionsActivity : BaseActivity<ConnectionsDesign>() {
    private var activeObserver: Any? = null

    override suspend fun main() {
        val design = ConnectionsDesign(this)

        setContentDesign(design)

        val trackedConnections = mutableMapOf<String, com.github.kr328.clash.core.model.Connection>()
        val connectionSpeeds = mutableMapOf<String, Long>() // id -> download speed
        val connectionUploadSpeeds = mutableMapOf<String, Long>() // id -> upload speed
        val packageNames = mutableMapOf<String, String>()
        val packageIcons = mutableMapOf<String, android.graphics.drawable.Drawable?>()
        val collapsedGroups = mutableSetOf<String>()
        val closedConnectionIds = mutableSetOf<String>()

        fun formatTraffic(bytes: Long): String {
            if (bytes < 1024) return "$bytes B/s"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB/s", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.1f MB/s", mb)
            val gb = mb / 1024.0
            return String.format("%.2f GB/s", gb)
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format("%.2f GB", gb)
        }

        val diffChannel = kotlinx.coroutines.channels.Channel<com.github.kr328.clash.core.model.ConnectionDiff>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        val adapter = com.github.kr328.clash.design.adapter.ConnectionAdapter(
            context = this,
            onGroupClick = { packageName ->
                if (collapsedGroups.contains(packageName)) {
                    collapsedGroups.remove(packageName)
                } else {
                    collapsedGroups.add(packageName)
                }
                diffChannel.trySend(com.github.kr328.clash.core.model.ConnectionDiff())
            },
            onClick = { conn ->
                val binding = com.github.kr328.clash.design.databinding.DesignConnectionDetailsBinding.inflate(layoutInflater)
                
                // Populate fields
                val meta = conn.metadata
                binding.tvDest.text = meta.host.ifEmpty { meta.destinationIP }
                binding.tvPort.text = meta.destinationPort
                binding.tvNetwork.text = meta.network.uppercase()
                binding.tvProcess.text = packageNames[meta.process] ?: meta.process ?: "Unknown"
                binding.tvDns.text = conn.dnsServer.takeUnless { it.isNullOrEmpty() } ?: "N/A"
                binding.tvDnsMode.text = meta.dnsMode.takeUnless { it.isNullOrEmpty() } ?: "N/A"
                binding.tvIp.text = meta.destinationIP
                
                val countryCode = meta.destinationGeoIP?.firstOrNull()?.uppercase() ?: ""
                val flag = if (countryCode.length == 2) {
                    val flagOffset = 0x1F1E6
                    val asciiOffset = 0x41
                    val firstChar = Character.codePointAt(countryCode, 0) - asciiOffset + flagOffset
                    val secondChar = Character.codePointAt(countryCode, 1) - asciiOffset + flagOffset
                    String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
                } else ""
                binding.tvCountry.text = if (countryCode.isNotEmpty()) "$countryCode $flag" else "Unknown"
                
                binding.tvRule.text = if (conn.rulePayload.isNotEmpty()) "${conn.rule} (${conn.rulePayload})" else conn.rule
                binding.tvChain.text = conn.chains.joinToString(" -> ")
                
                // Duration calculation
                try {
                    val startMillis = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { 
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.parse(conn.start)?.time ?: System.currentTimeMillis()
                    
                    val durationSeconds = (System.currentTimeMillis() - startMillis) / 1000
                    val mm = String.format("%02d", (durationSeconds % 3600) / 60)
                    val ss = String.format("%02d", durationSeconds % 60)
                    val hh = if (durationSeconds >= 3600) String.format("%02d:", durationSeconds / 3600) else ""
                    binding.tvDuration.text = "$hh$mm:$ss"
                } catch (e: Exception) {
                    binding.tvDuration.text = conn.start
                }
                
                binding.tvUp.text = formatBytes(conn.upload)
                binding.tvDown.text = formatBytes(conn.download)

                val dialog = com.github.kr328.clash.design.dialog.FullScreenDialog(this@ConnectionsActivity)
                binding.self = dialog
                dialog.setContentView(binding.root)
                
                binding.toolbar.setNavigationOnClickListener { dialog.dismiss() }
                binding.btnCloseConnection.setOnClickListener {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                        com.github.kr328.clash.util.withClash {
                            closeConnection(conn.id)
                        }
                    }
                    dialog.dismiss() 
                }
                
                dialog.show()
            }
        )
        design.setAdapter(adapter)

        val observer = object : IConnectionObserver {
            override fun onConnectionDiff(diff: ConnectionDiff) {
                diffChannel.trySend(diff)
            }
        }
        val observerBinder = com.github.kr328.clash.service.remote.IConnectionObserverDelegate(observer)
        this.activeObserver = observerBinder

        withContext(Dispatchers.IO) {
            com.github.kr328.clash.util.withClash {
                setConnectionObserver(observerBinder)
            }
        }

        try {
            while (isActive) {
                select<Unit> {
                    events.onReceive {
                        when (it) {
                            Event.ActivityStart, Event.ServiceRecreated -> {
                                withContext(Dispatchers.IO) {
                                    com.github.kr328.clash.util.withClash {
                                        setConnectionObserver(observerBinder)
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                    design.requests.onReceive {
                        when (it) {
                            ConnectionsDesign.Request.Close -> finish()
                            ConnectionsDesign.Request.FilterChanged -> {
                                diffChannel.trySend(com.github.kr328.clash.core.model.ConnectionDiff())
                            }
                        }
                    }
                    diffChannel.onReceive { diff ->
                        val filterActive = design.filterActive
                        val filterClosed = design.filterClosed
                        val sortType = design.sortType

                        try {
                            // Update local tracked connections map
                            for (conn in diff.newConnections) {
                                if (!trackedConnections.containsKey(conn.id)) {
                                    connectionSpeeds[conn.id] = 0L
                                    connectionUploadSpeeds[conn.id] = 0L
                                }
                                trackedConnections[conn.id] = conn
                            }
                            
                            for (id in diff.removedConnections) {
                                closedConnectionIds.add(id)
                                connectionSpeeds[id] = 0L
                                connectionUploadSpeeds[id] = 0L
                            }
                            
                            for (traffic in diff.updatedTraffics) {
                                val prevConn = trackedConnections[traffic.id]
                                if (prevConn != null) {
                                    connectionSpeeds[traffic.id] = traffic.download - prevConn.download
                                    connectionUploadSpeeds[traffic.id] = traffic.upload - prevConn.upload
                                    trackedConnections[traffic.id] = prevConn.copy(download = traffic.download, upload = traffic.upload)
                                }
                            }
                            
                            val activeIds = diff.newConnections.map { it.id }.toSet() + diff.updatedTraffics.map { it.id }.toSet() // Approximating active

                            // Filter to build the items list
                            val items = mutableListOf<com.github.kr328.clash.design.adapter.ConnectionItem>()
                            
                            val allDisplayConns = trackedConnections.values.filter { conn ->
                                // For diff architecture, assume removed = closed. If in trackedConnections, it is active
                                val isActiveConn = !closedConnectionIds.contains(conn.id)
                                if (isActiveConn && !filterActive) return@filter false
                                if (!isActiveConn && !filterClosed) return@filter false
                                true
                            }

                            val grouped = allDisplayConns.groupBy { 
                                it.metadata.process?.substringBefore(":")?.takeIf { p -> p.isNotEmpty() } ?: "Unknown" 
                            }

                            val sortedGrouped = grouped.entries.sortedWith { a, b ->
                                when (sortType) {
                                    com.github.kr328.clash.design.ConnectionsDesign.SortType.NAME -> {
                                        a.key.compareTo(b.key, ignoreCase = true)
                                    }
                                    com.github.kr328.clash.design.ConnectionsDesign.SortType.SPEED_DOWN -> {
                                        val sumA = a.value.sumOf { connectionSpeeds[it.id] ?: 0L }
                                        val sumB = b.value.sumOf { connectionSpeeds[it.id] ?: 0L }
                                        sumB.compareTo(sumA)
                                    }
                                    com.github.kr328.clash.design.ConnectionsDesign.SortType.SPEED_UP -> {
                                        val sumA = a.value.sumOf { connectionUploadSpeeds[it.id] ?: 0L }
                                        val sumB = b.value.sumOf { connectionUploadSpeeds[it.id] ?: 0L }
                                        sumB.compareTo(sumA)
                                    }
                                    com.github.kr328.clash.design.ConnectionsDesign.SortType.TRAFFIC_DOWN -> {
                                        val sumA = a.value.sumOf { it.download }
                                        val sumB = b.value.sumOf { it.download }
                                        sumB.compareTo(sumA)
                                    }
                                    com.github.kr328.clash.design.ConnectionsDesign.SortType.TRAFFIC_UP -> {
                                        val sumA = a.value.sumOf { it.upload }
                                        val sumB = b.value.sumOf { it.upload }
                                        sumB.compareTo(sumA)
                                    }
                                    else -> {
                                        // Default: approximate time sort or leave as is (preserves map order which is based on diff insertion)
                                        0
                                    }
                                }
                            }

                            for ((basePackage, connsUnsorted) in sortedGrouped) {
                                val conns = connsUnsorted.sortedWith { a, b ->
                                    when (sortType) {
                                        com.github.kr328.clash.design.ConnectionsDesign.SortType.NAME -> {
                                            val nameA = a.metadata.host.ifEmpty { a.metadata.destinationIP }
                                            val nameB = b.metadata.host.ifEmpty { b.metadata.destinationIP }
                                            nameA.compareTo(nameB, ignoreCase = true)
                                        }
                                        com.github.kr328.clash.design.ConnectionsDesign.SortType.SPEED_DOWN -> {
                                            val speedA = connectionSpeeds[a.id] ?: 0L
                                            val speedB = connectionSpeeds[b.id] ?: 0L
                                            speedB.compareTo(speedA)
                                        }
                                        com.github.kr328.clash.design.ConnectionsDesign.SortType.SPEED_UP -> {
                                            val speedA = connectionUploadSpeeds[a.id] ?: 0L
                                            val speedB = connectionUploadSpeeds[b.id] ?: 0L
                                            speedB.compareTo(speedA)
                                        }
                                        com.github.kr328.clash.design.ConnectionsDesign.SortType.TRAFFIC_DOWN -> b.download.compareTo(a.download)
                                        com.github.kr328.clash.design.ConnectionsDesign.SortType.TRAFFIC_UP -> b.upload.compareTo(a.upload)
                                        else -> 0
                                    }
                                }

                                val activeCount = conns.count { !closedConnectionIds.contains(it.id) } // All in tracked are active, until removed
                                
                                // Resolve app info
                                val appName = packageNames.getOrPut("name_$basePackage") {
                                    try {
                                        val pm = packageManager
                                        val info = pm.getApplicationInfo(basePackage, 0)
                                        pm.getApplicationLabel(info).toString()
                                    } catch (e: Exception) {
                                        if (basePackage == "Unknown" || basePackage.isBlank()) "System / External" else basePackage
                                    }
                                }
                                val appIcon = packageIcons.getOrPut("icon_$basePackage") {
                                    try {
                                        packageManager.getApplicationIcon(basePackage)
                                    } catch (e: Exception) {
                                        androidx.core.content.ContextCompat.getDrawable(this@ConnectionsActivity, android.R.mipmap.sym_def_app_icon) ?: androidx.core.content.ContextCompat.getDrawable(this@ConnectionsActivity, android.R.drawable.sym_def_app_icon)
                                    }
                                }

                                val totalSpeedBytes = conns.sumOf { connectionSpeeds[it.id] ?: 0L }
                                val totalUploadSpeedBytes = conns.sumOf { connectionUploadSpeeds[it.id] ?: 0L }
                                val totalSpeed = "↓ ${formatTraffic(totalSpeedBytes)}  ↑ ${formatTraffic(totalUploadSpeedBytes)}"
                                val totalUploadBytes = conns.sumOf { it.upload }
                                val totalDownloadBytes = conns.sumOf { it.download }
                                
                                val isExpanded = !collapsedGroups.contains(basePackage)
                                items.add(com.github.kr328.clash.design.adapter.ConnectionItem.Group(
                                    packageName = basePackage,
                                    appName = appName,
                                    appIcon = appIcon,
                                    activeCount = activeCount,
                                    totalCount = conns.size,
                                    totalSpeed = totalSpeed,
                                    totalUpload = totalUploadBytes,
                                    totalDownload = totalDownloadBytes,
                                    isExpanded = isExpanded
                                ))
                                
                                if (isExpanded) {
                                    for (conn in conns) {
                                        val isActiveConn = !closedConnectionIds.contains(conn.id)
                                        val speedBytes = connectionSpeeds[conn.id] ?: 0L
                                        val uploadSpeedBytes = connectionUploadSpeeds[conn.id] ?: 0L
                                        val speed = "↓ ${formatTraffic(speedBytes)}  ↑ ${formatTraffic(uploadSpeedBytes)}"
                                        items.add(com.github.kr328.clash.design.adapter.ConnectionItem.Child(conn, speed, isActiveConn))
                                    }
                                }
                            }
                            
                            // Let the design update its own adapter since we passed it in
                            adapter.submitList(items)
                        } catch (e: Exception) {
                            com.github.kr328.clash.common.log.Log.w("Failed to update connections UI", e)
                        }
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO) {
                com.github.kr328.clash.util.withClash {
                    setConnectionObserver(null)
                }
            }
            this.activeObserver = null
        }
    }
}
