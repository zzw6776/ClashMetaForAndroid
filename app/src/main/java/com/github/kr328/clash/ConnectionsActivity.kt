package com.github.kr328.clash

import android.content.Intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.ConnectionsDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ConnectionsActivity : BaseActivity<ConnectionsDesign>() {
    override suspend fun main() {
        val design = ConnectionsDesign(this)

        setContentDesign(design)

        val trackedConnections = mutableMapOf<String, com.github.kr328.clash.core.model.Connection>()
        val connectionSpeeds = mutableMapOf<String, Long>() // id -> download speed
        val packageNames = mutableMapOf<String, String>()
        val packageIcons = mutableMapOf<String, android.graphics.drawable.Drawable?>()
        val collapsedGroups = mutableSetOf<String>()

        fun formatTraffic(bytes: Long): String {
            if (bytes < 1024) return "$bytes B/s"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB/s", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.1f MB/s", mb)
            val gb = mb / 1024.0
            return String.format("%.2f GB/s", gb)
        }

        val adapter = com.github.kr328.clash.design.adapter.ConnectionAdapter(
            context = this,
            onGroupClick = { packageName ->
                if (collapsedGroups.contains(packageName)) {
                    collapsedGroups.remove(packageName)
                } else {
                    collapsedGroups.add(packageName)
                }
                // Will update on next tick
            },
            onClick = { conn ->
                val binding = com.github.kr328.clash.design.databinding.DesignConnectionDetailsBinding.inflate(layoutInflater)
                
                // Populate 12 fields
                val meta = conn.metadata
                binding.tvDest.text = meta.host.ifEmpty { meta.destinationIP }
                binding.tvPort.text = meta.destinationPort
                binding.tvNetwork.text = meta.network.uppercase()
                binding.tvProcess.text = packageNames[meta.process] ?: meta.process ?: "Unknown"
                binding.tvDns.text = conn.dnsServer ?: "System/Local"
                binding.tvIp.text = meta.destinationIP
                
                val countryCode = meta.destinationGeoIP?.firstOrNull() ?: ""
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
                
                binding.tvUp.text = formatTraffic(conn.upload)
                binding.tvDown.text = formatTraffic(conn.download)

                val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                dialog.setContentView(binding.root)
                
                binding.toolbar.setNavigationOnClickListener { dialog.dismiss() }
                binding.btnCloseConnection.setOnClickListener {
                    // Logic to close connection can be added here
                    dialog.dismiss() 
                }
                
                dialog.show()
            }
        )
        design.setAdapter(adapter)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    // Handle events if needed
                }
                design.requests.onReceive {
                    when (it) {
                        ConnectionsDesign.Request.Close -> finish()
                    }
                }
                ticker.onReceive {
                    val filterActive = design.filterActive
                    val filterClosed = design.filterClosed

                    val snapshot = com.github.kr328.clash.util.withClash {
                        val json = queryConnections() ?: return@withClash null
                        com.github.kr328.clash.core.Clash.parseConnectionSnapshot(json)
                    }

                    if (snapshot != null) {
                        try {
                            val activeConns = snapshot.connections ?: emptyList()
                            val activeIds = activeConns.map { it.id }.toSet()

                            // Update tracked connections and calculate speed
                            for (conn in activeConns) {
                                val prevConn = trackedConnections[conn.id]
                                if (prevConn != null) {
                                    connectionSpeeds[conn.id] = conn.download - prevConn.download
                                } else {
                                    connectionSpeeds[conn.id] = conn.download
                                }
                                trackedConnections[conn.id] = conn
                            }

                            // Filter to build the items list
                            val items = mutableListOf<com.github.kr328.clash.design.adapter.ConnectionItem>()
                            
                            val allDisplayConns = trackedConnections.values.filter { conn ->
                                val isActiveConn = activeIds.contains(conn.id)
                                if (isActiveConn && !filterActive) return@filter false
                                if (!isActiveConn && !filterClosed) return@filter false
                                true
                            }

                            val grouped = allDisplayConns.groupBy { it.metadata.process ?: "Unknown" }

                            for ((process, conns) in grouped) {
                                val activeCount = conns.count { activeIds.contains(it.id) }
                                
                                // Resolve app info
                                val appName = packageNames.getOrPut(process) {
                                    try {
                                        val pm = packageManager
                                        val info = pm.getApplicationInfo(process, 0)
                                        pm.getApplicationLabel(info).toString()
                                    } catch (e: Exception) {
                                        process
                                    }
                                }
                                val appIcon = packageIcons.getOrPut(process) {
                                    try {
                                        packageManager.getApplicationIcon(process)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }

                                val totalSpeedBytes = conns.filter { activeIds.contains(it.id) }.sumOf { connectionSpeeds[it.id] ?: 0L }
                                val totalSpeed = "↓ ${formatTraffic(totalSpeedBytes)}"
                                
                                items.add(com.github.kr328.clash.design.adapter.ConnectionItem.Group(process, appName, appIcon, activeCount, totalSpeed))
                                
                                if (!collapsedGroups.contains(process)) {
                                    for (conn in conns) {
                                        val speed = "↓ ${formatTraffic(connectionSpeeds[conn.id] ?: 0L)}"
                                        val isActiveConn = activeIds.contains(conn.id)
                                        items.add(com.github.kr328.clash.design.adapter.ConnectionItem.Child(conn, speed, isActiveConn))
                                    }
                                }
                            }
                            
                            // Let the design update its own adapter since we passed it in
                            adapter.items = items
                            adapter.notifyDataSetChanged()
                        } catch (e: Exception) {
                            com.github.kr328.clash.common.log.Log.w("Failed to update connections UI", e)
                        }
                    }
                }
            }
        }
    }
}
