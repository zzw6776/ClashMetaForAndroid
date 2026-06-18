package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.core.model.ConnectionDiff
import com.github.kr328.clash.core.model.FailedConnection
import com.github.kr328.clash.core.model.ProcessTraffic
import com.github.kr328.clash.design.ConnectionsDesign
import com.github.kr328.clash.design.adapter.ConnectionStatus
import com.github.kr328.clash.design.databinding.DesignConnectionDetailsBinding
import com.github.kr328.clash.design.util.formatBytes
import com.github.kr328.clash.design.util.formatTraffic
import com.github.kr328.clash.service.remote.IConnectionObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.widget.PopupMenu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ConnectionsActivity : BaseActivity<ConnectionsDesign>() {
    private var activeObserver: Any? = null

    override suspend fun main() {
        val design = ConnectionsDesign(this, uiStore)

        setContentDesign(design)

        val connectionRecords = mutableMapOf<String, ConnectionRecord>()
        val failedConnectionRecords = mutableMapOf<String, FailedConnectionRecord>()
        val mergedConnectionRecords = mutableMapOf<String, ConnectionRecord>()
        val connectionSpeeds = mutableMapOf<String, Long>()
        val connectionUploadSpeeds = mutableMapOf<String, Long>()
        val packageNames = mutableMapOf<String, String>()
        val packageIcons = mutableMapOf<String, android.graphics.drawable.Drawable?>()
        val collapsedGroups = mutableSetOf<String>()
        design.updateExpandCollapseIconState(collapsedGroups.isNotEmpty())
        val closedConnectionIds = mutableSetOf<String>()
        val closedConnectionOrder = java.util.ArrayDeque<String>()
        val failedConnectionOrder = java.util.ArrayDeque<String>()
        var processTrafficTotals = emptyMap<String, ProcessTraffic>()
        var observerRegistered = false
        var awaitingSnapshotReconcile = false
        var selectedProcessKey: String? = uiStore.connectionProcessFilter.takeIf { it.isNotBlank() }
        var selectedProxyKey: String? = uiStore.connectionProxyFilter.takeIf { it.isNotBlank() }
        var detailsBinding: DesignConnectionDetailsBinding? = null
        var detailsConnectionId: String? = null
        val clockTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        lateinit var refreshConnectionList: (scrollToTop: Boolean) -> Unit

        fun normalizeProcessName(process: String?): String {
            return process?.substringBefore(":")?.takeIf { it.isNotBlank() } ?: UNKNOWN_PACKAGE
        }

        fun resolveAppName(basePackage: String): String {
            return packageNames.getOrPut("name_$basePackage") {
                try {
                    val info = packageManager.getApplicationInfo(basePackage, 0)
                    packageManager.getApplicationLabel(info).toString()
                } catch (e: Exception) {
                    if (basePackage == UNKNOWN_PACKAGE || basePackage.isBlank()) {
                        "System / External"
                    } else {
                        basePackage
                    }
                }
            }
        }

        selectedProcessKey?.let {
            design.setProcessFilterLabel(resolveAppName(it))
        }
        selectedProxyKey?.let {
            design.setProxyFilterLabel(it)
        }

        fun parseConnectionStartMillis(start: String): Long? {
            if (start.isBlank()) return null

            val normalized = normalizeConnectionStart(start)
            for (pattern in CONNECTION_START_PATTERNS) {
                val parsed = try {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                        isLenient = false
                    }.parse(normalized)
                } catch (e: Exception) {
                    null
                }

                if (parsed != null) return parsed.time
            }

            return null
        }

        fun connectionStartSortKey(record: ConnectionRecord): Long {
            return record.startMillis ?: Long.MAX_VALUE
        }

        fun formatDuration(start: String, record: ConnectionRecord, nowMillis: Long): String {
            val durationMillis = record.durationMillis
                ?: record.startMillis?.let { (nowMillis - it).coerceAtLeast(0) }
                ?: return start
            val durationSeconds = durationMillis / 1000
            val mm = String.format(Locale.US, "%02d", (durationSeconds % 3600) / 60)
            val ss = String.format(Locale.US, "%02d", durationSeconds % 60)
            val hh = if (durationSeconds >= 3600) {
                String.format(Locale.US, "%02d:", durationSeconds / 3600)
            } else {
                ""
            }
            return "$hh$mm:$ss"
        }

        fun formatClockTime(timeMillis: Long): String {
            return clockTimeFormat.format(Date(timeMillis))
        }

        fun formatConnectionTimeRange(record: ConnectionRecord, nowMillis: Long): String {
            val start = record.startMillis?.let { formatClockTime(it) } ?: record.connection.start.ifBlank { "N/A" }
            val endMillis = record.closedMillis
            val end = if (endMillis != null) {
                formatClockTime(endMillis)
            } else {
                formatClockTime(nowMillis)
            }
            return "$start - $end"
        }

        fun boundedTrafficDelta(delta: Long): Long {
            return if (delta in 0..MAX_REASONABLE_SPEED_BYTES_PER_SECOND) delta else 0L
        }

        fun formatRuleText(connection: Connection): String {
            return when {
                connection.rule.isBlank() && connection.rulePayload.isBlank() -> "N/A"
                connection.rulePayload.isNotEmpty() -> "${connection.rule} (${connection.rulePayload})"
                else -> connection.rule
            }
        }

        fun proxyNamesFor(connection: Connection): Set<String> {
            return buildSet {
                addAll(connection.chains.orEmpty().filter { it.isNotBlank() })
                connection.metadata.specialProxy.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }

        fun proxyNamesFor(failed: FailedConnection): Set<String> {
            return buildSet {
                addAll(failed.chains.orEmpty().filter { it.isNotBlank() })
                failed.proxy.takeIf { it.isNotBlank() }?.let { add(it) }
                failed.metadata.specialProxy.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }

        fun FailedConnection.toDisplayConnection(): Connection {
            val displayChains = chains.orEmpty().ifEmpty {
                listOfNotNull(proxy.takeIf { it.isNotBlank() })
            }
            return Connection(
                id = id,
                metadata = metadata,
                start = failedAt,
                chains = displayChains,
                providerChains = providerChains,
                rule = rule,
                rulePayload = rulePayload
            )
        }

        fun pruneClosedConnections() {
            while (closedConnectionIds.size > MAX_CLOSED_CONNECTIONS && closedConnectionOrder.isNotEmpty()) {
                val expiredId = closedConnectionOrder.removeFirst()
                if (closedConnectionIds.remove(expiredId)) {
                    connectionRecords.remove(expiredId)
                    connectionSpeeds.remove(expiredId)
                    connectionUploadSpeeds.remove(expiredId)
                }
            }
            while (failedConnectionRecords.size > MAX_FAILED_CONNECTIONS && failedConnectionOrder.isNotEmpty()) {
                val expiredId = failedConnectionOrder.removeFirst()
                failedConnectionRecords.remove(expiredId)
            }
        }

        fun markConnectionClosed(id: String, connection: Connection? = null, closedAtMillis: Long = System.currentTimeMillis()) {
            val record = connectionRecords[id]
            if (record != null) {
                if (connection != null) {
                    record.connection = connection
                    val parsedStartMillis = parseConnectionStartMillis(connection.start)
                    if (parsedStartMillis != null || record.startMillis == null) {
                        record.startMillis = parsedStartMillis
                    }
                }
                record.close(closedAtMillis)
            } else if (connection != null) {
                val startMillis = parseConnectionStartMillis(connection.start)
                connectionRecords[id] = ConnectionRecord(
                    connection = connection,
                    startMillis = startMillis
                ).apply {
                    close(closedAtMillis)
                }
            } else {
                return
            }

            if (closedConnectionIds.add(id)) {
                closedConnectionOrder.addLast(id)
            }
            connectionSpeeds[id] = 0L
            connectionUploadSpeeds[id] = 0L
        }

        fun updateConnectionDetails(binding: DesignConnectionDetailsBinding, id: String) {
            val record = mergedConnectionRecords[id] ?: connectionRecords[id]
            val failedRecord = failedConnectionRecords[id]
            if (record == null && failedRecord == null) return

            val conn = record?.connection ?: failedRecord!!.failedConnection.toDisplayConnection()
            val meta = conn.metadata
            val isFailed = failedRecord != null && record == null
            val isClosed = record?.closed == true
            val nowMillis = System.currentTimeMillis()

            binding.tvDest.text = meta.host.ifEmpty { meta.destinationIP }
            binding.tvPort.text = meta.destinationPort
            binding.tvNetwork.text = meta.network.uppercase()
            binding.tvProcess.text = resolveAppName(normalizeProcessName(meta.process))
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
            } else {
                ""
            }
            binding.tvCountry.text = if (countryCode.isNotEmpty()) "$countryCode $flag" else "Unknown"

            binding.tvRule.text = formatRuleText(conn)
            binding.tvChain.text = conn.chains.orEmpty().filter { it.isNotBlank() }.reversed().joinToString(" -> ")
            binding.tvDuration.text = if (record != null) {
                formatDuration(conn.start, record, nowMillis)
            } else {
                "N/A"
            }
            binding.tvSpeed.text = if (isClosed || isFailed) {
                "↑ 0 B/s  ↓ 0 B/s"
            } else {
                "↑ ${formatTraffic(connectionUploadSpeeds[id] ?: 0L)}  ↓ ${formatTraffic(connectionSpeeds[id] ?: 0L)}"
            }
            binding.tvUp.text = formatBytes(conn.upload)
            binding.tvDown.text = formatBytes(conn.download)
            binding.tvStatus.text = getString(
                if (isFailed) {
                    com.github.kr328.clash.design.R.string.failed
                } else if (isClosed) {
                    com.github.kr328.clash.design.R.string.closed
                } else {
                    com.github.kr328.clash.design.R.string.active
                }
            )
            binding.tvSnapshotTime.text = if (record != null) {
                formatConnectionTimeRange(record, nowMillis)
            } else {
                failedRecord?.failedConnection?.failedAt?.ifBlank { "N/A" } ?: "N/A"
            }
            binding.tvMetadataType.text = meta.type.ifBlank { "N/A" }
            binding.tvSpecialProxy.text = failedRecord?.failedConnection?.proxy?.takeIf { it.isNotBlank() }
                ?: meta.specialProxy.ifBlank { "N/A" }
            binding.tvSpecialRules.text = failedRecord?.failedConnection?.error?.takeIf { it.isNotBlank() }
                ?: meta.specialRules.ifBlank { "N/A" }
            binding.btnCloseConnection.isEnabled = !isClosed && !isFailed
        }

        val diffChannel = Channel<ConnectionDiff>(Channel.UNLIMITED)

        val adapter = com.github.kr328.clash.design.adapter.ConnectionAdapter(
            context = this,
            onGroupClick = { packageName ->
                if (collapsedGroups.contains(packageName)) {
                    collapsedGroups.remove(packageName)
                } else {
                    collapsedGroups.add(packageName)
                }
                refreshConnectionList(false)
            },
            onClick = { conn ->
                val binding = DesignConnectionDetailsBinding.inflate(layoutInflater)
                val dialog = com.github.kr328.clash.design.dialog.FullScreenDialog(this@ConnectionsActivity)
                binding.self = dialog
                dialog.setContentView(binding.root)

                detailsBinding = binding
                detailsConnectionId = conn.id
                updateConnectionDetails(binding, conn.id)

                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val setupCopy = { view: android.widget.TextView, label: String ->
                    view.setOnLongClickListener {
                        val textToCopy = view.text?.toString() ?: ""
                        if (textToCopy.isNotBlank() && textToCopy != "N/A" && textToCopy != "Unknown") {
                            val clip = android.content.ClipData.newPlainText(label, textToCopy)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(this@ConnectionsActivity, "已复制: $textToCopy", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                }
                setupCopy(binding.tvDest, "Destination")
                setupCopy(binding.tvPort, "Port")
                setupCopy(binding.tvProcess, "Process")
                setupCopy(binding.tvNetwork, "Network")
                setupCopy(binding.tvRule, "Rule")
                setupCopy(binding.tvChain, "Chain")
                setupCopy(binding.tvIp, "IP")
                setupCopy(binding.tvCountry, "Country")
                setupCopy(binding.tvDns, "DNS")
                setupCopy(binding.tvDnsMode, "DNS Mode")
                setupCopy(binding.tvMetadataType, "Type")
                setupCopy(binding.tvSpecialProxy, "Special Proxy")
                setupCopy(binding.tvSpecialRules, "Special Rules")

                binding.toolbar.setNavigationOnClickListener { dialog.dismiss() }
                binding.btnCloseConnection.setOnClickListener {
                    this@ConnectionsActivity.launch(Dispatchers.IO) {
                        withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                            com.github.kr328.clash.util.withClash {
                                if (conn.id.startsWith("merged|")) {
                                    val parts = conn.id.split("|")
                                    if (parts.size >= 4) {
                                        val basePkg = parts[1]
                                        val hostK = parts[2]
                                        connectionRecords.values.forEach { record ->
                                            if (!record.closed) {
                                                val pkg = normalizeProcessName(record.connection.metadata.process)
                                                val hk = record.connection.metadata.host.ifEmpty { record.connection.metadata.destinationIP }
                                                if (pkg == basePkg && hk == hostK) {
                                                    closeConnection(record.connection.id)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    closeConnection(conn.id)
                                }
                            }
                        }
                    }
                    dialog.dismiss()
                }
                dialog.setOnDismissListener {
                    if (detailsBinding === binding) {
                        detailsBinding = null
                        detailsConnectionId = null
                    }
                }

                dialog.show()
            }
        )
        design.setAdapter(adapter)

        refreshConnectionList = { scrollToTop ->
            try {
                mergedConnectionRecords.clear()

                val filterActive = design.filterActive
                val filterClosed = design.filterClosed
                val filterFailed = design.filterFailed
                val sortType = design.sortType
                val processFilter = selectedProcessKey
                val proxyFilter = selectedProxyKey

                data class DisplayRecord(
                    val connection: Connection,
                    val status: ConnectionStatus,
                    val startMillis: Long?,
                    val error: String? = null,
                    val count: Int = 1
                )

                val allDisplayRecords = mutableListOf<DisplayRecord>()
                connectionRecords.values.forEach { record ->
                    val status = if (record.closed) ConnectionStatus.CLOSED else ConnectionStatus.ACTIVE
                    if (status == ConnectionStatus.ACTIVE && !filterActive) return@forEach
                    if (status == ConnectionStatus.CLOSED && !filterClosed) return@forEach
                    if (processFilter != null && normalizeProcessName(record.connection.metadata.process) != processFilter) return@forEach
                    if (proxyFilter != null && proxyFilter !in proxyNamesFor(record.connection)) return@forEach
                    allDisplayRecords.add(DisplayRecord(record.connection, status, record.startMillis))
                }
                if (filterFailed) {
                    failedConnectionRecords.values.forEach { record ->
                        val failed = record.failedConnection
                        if (processFilter != null && normalizeProcessName(failed.metadata.process) != processFilter) return@forEach
                        if (proxyFilter != null && proxyFilter !in proxyNamesFor(failed)) return@forEach
                        allDisplayRecords.add(
                            DisplayRecord(
                                failed.toDisplayConnection(),
                                ConnectionStatus.FAILED,
                                record.failedAtMillis,
                                failed.error
                            )
                        )
                    }
                }

                val grouped = allDisplayRecords.groupBy {
                    normalizeProcessName(it.connection.metadata.process)
                }
                data class ProcessGroup(
                    val process: String,
                    val records: List<DisplayRecord>
                )
                fun processTrafficForGroup(
                    process: String,
                    records: List<DisplayRecord>
                ): ProcessTraffic {
                    return processTrafficTotals[process] ?: ProcessTraffic(
                        upload = records.sumOf { it.connection.upload },
                        download = records.sumOf { it.connection.download }
                    )
                }

                val processGroups = buildList {
                    grouped.forEach { (process, records) ->
                        add(ProcessGroup(process, records))
                    }
                    if (filterClosed && proxyFilter == null) {
                        processTrafficTotals.forEach { (process, traffic) ->
                            if (processFilter != null && process != processFilter) return@forEach
                            if (process in grouped) return@forEach
                            if (traffic.upload <= 0L && traffic.download <= 0L) return@forEach
                            add(ProcessGroup(process, emptyList()))
                        }
                    }
                }

                val sortedGrouped = processGroups.sortedWith { a, b ->
                    when (sortType) {
                        ConnectionsDesign.SortType.TIME -> {
                            val timeA = a.records.minOfOrNull { it.startMillis ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
                            val timeB = b.records.minOfOrNull { it.startMillis ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
                            timeA.compareTo(timeB).takeIf { it != 0 } ?: a.process.compareTo(b.process, ignoreCase = true)
                        }
                        ConnectionsDesign.SortType.NAME -> a.process.compareTo(b.process, ignoreCase = true)
                        ConnectionsDesign.SortType.SPEED_DOWN -> {
                            val sumA = a.records.sumOf { connectionSpeeds[it.connection.id] ?: 0L }
                            val sumB = b.records.sumOf { connectionSpeeds[it.connection.id] ?: 0L }
                            sumB.compareTo(sumA)
                        }
                        ConnectionsDesign.SortType.SPEED_UP -> {
                            val sumA = a.records.sumOf { connectionUploadSpeeds[it.connection.id] ?: 0L }
                            val sumB = b.records.sumOf { connectionUploadSpeeds[it.connection.id] ?: 0L }
                            sumB.compareTo(sumA)
                        }
                        ConnectionsDesign.SortType.TRAFFIC_DOWN -> {
                            val sumA = processTrafficForGroup(a.process, a.records).download
                            val sumB = processTrafficForGroup(b.process, b.records).download
                            sumB.compareTo(sumA)
                        }
                        ConnectionsDesign.SortType.TRAFFIC_UP -> {
                            val sumA = processTrafficForGroup(a.process, a.records).upload
                            val sumB = processTrafficForGroup(b.process, b.records).upload
                            sumB.compareTo(sumA)
                        }
                    }
                }

                val items = mutableListOf<com.github.kr328.clash.design.adapter.ConnectionItem>()
                for ((basePackage, connsUnsorted) in sortedGrouped.map { it.process to it.records }) {
                    val processedRecords = if (uiStore.connectionMergeDomains) {
                        val mergedMap = mutableMapOf<String, MutableList<DisplayRecord>>()
                        for (rec in connsUnsorted) {
                            val hostKey = rec.connection.metadata.host.ifEmpty { rec.connection.metadata.destinationIP }
                            val key = "${hostKey}|${rec.status.name}"
                            mergedMap.getOrPut(key) { mutableListOf() }.add(rec)
                        }
                        mergedMap.map { (key, recordList) ->
                            if (recordList.size == 1) {
                                recordList[0]
                            } else {
                                val firstRecord = recordList[0]
                                val totalUpload = recordList.sumOf { it.connection.upload }
                                val totalDownload = recordList.sumOf { it.connection.download }
                                val hostKey = firstRecord.connection.metadata.host.ifEmpty { firstRecord.connection.metadata.destinationIP }
                                val mergedId = "merged|${basePackage}|${hostKey}|${firstRecord.status.name}"
                                val totalSpeedBytes = recordList.sumOf { connectionSpeeds[it.connection.id] ?: 0L }
                                val totalUploadSpeedBytes = recordList.sumOf { connectionUploadSpeeds[it.connection.id] ?: 0L }
                                connectionSpeeds[mergedId] = totalSpeedBytes
                                connectionUploadSpeeds[mergedId] = totalUploadSpeedBytes
                                val mergedConnection = firstRecord.connection.copy(
                                    id = mergedId,
                                    upload = totalUpload,
                                    download = totalDownload
                                )
                                val earliestStartMillis = recordList.mapNotNull { it.startMillis }.minOrNull()
                                val latestClosedMillis = recordList.mapNotNull { r ->
                                    if (r.status == ConnectionStatus.CLOSED) {
                                        connectionRecords[r.connection.id]?.closedMillis
                                    } else {
                                        null
                                    }
                                }.maxOrNull()
                                val virtualRecord = ConnectionRecord(
                                    connection = mergedConnection,
                                    startMillis = earliestStartMillis,
                                    closedMillis = latestClosedMillis
                                )
                                mergedConnectionRecords[mergedId] = virtualRecord
                                DisplayRecord(
                                    connection = mergedConnection,
                                    status = firstRecord.status,
                                    startMillis = earliestStartMillis,
                                    error = firstRecord.error,
                                    count = recordList.size
                                )
                            }
                        }
                    } else {
                        connsUnsorted
                    }

                    val conns = processedRecords.sortedWith { a, b ->
                        val connA = a.connection
                        val connB = b.connection
                        when (sortType) {
                            ConnectionsDesign.SortType.TIME -> {
                                val timeCompare = (a.startMillis ?: Long.MAX_VALUE).compareTo(b.startMillis ?: Long.MAX_VALUE)
                                if (timeCompare != 0) {
                                    timeCompare
                                } else {
                                    val nameA = connA.metadata.host.ifEmpty { connA.metadata.destinationIP }
                                    val nameB = connB.metadata.host.ifEmpty { connB.metadata.destinationIP }
                                    nameA.compareTo(nameB, ignoreCase = true)
                                }
                            }
                            ConnectionsDesign.SortType.NAME -> {
                                val nameA = connA.metadata.host.ifEmpty { connA.metadata.destinationIP }
                                val nameB = connB.metadata.host.ifEmpty { connB.metadata.destinationIP }
                                nameA.compareTo(nameB, ignoreCase = true)
                            }
                            ConnectionsDesign.SortType.SPEED_DOWN -> {
                                val speedA = connectionSpeeds[connA.id] ?: 0L
                                val speedB = connectionSpeeds[connB.id] ?: 0L
                                speedB.compareTo(speedA)
                            }
                            ConnectionsDesign.SortType.SPEED_UP -> {
                                val speedA = connectionUploadSpeeds[connA.id] ?: 0L
                                val speedB = connectionUploadSpeeds[connB.id] ?: 0L
                                speedB.compareTo(speedA)
                            }
                            ConnectionsDesign.SortType.TRAFFIC_DOWN -> connB.download.compareTo(connA.download)
                            ConnectionsDesign.SortType.TRAFFIC_UP -> connB.upload.compareTo(connA.upload)
                        }
                    }

                    val activeCount = conns.count { it.status == ConnectionStatus.ACTIVE }
                    val appName = resolveAppName(basePackage)
                    val appIcon = packageIcons.getOrPut("icon_$basePackage") {
                        try {
                            packageManager.getApplicationIcon(basePackage)
                        } catch (e: Exception) {
                            androidx.core.content.ContextCompat.getDrawable(
                                this@ConnectionsActivity,
                                android.R.mipmap.sym_def_app_icon
                            ) ?: androidx.core.content.ContextCompat.getDrawable(
                                this@ConnectionsActivity,
                                android.R.drawable.sym_def_app_icon
                            )
                        }
                    }

                    val totalSpeedBytes = conns.sumOf { connectionSpeeds[it.connection.id] ?: 0L }
                    val totalUploadSpeedBytes = conns.sumOf { connectionUploadSpeeds[it.connection.id] ?: 0L }
                    val totalSpeed = "↑ ${formatTraffic(totalUploadSpeedBytes)}  ↓ ${formatTraffic(totalSpeedBytes)}"
                    val processTraffic = processTrafficForGroup(basePackage, conns.map { DisplayRecord(it.connection, it.status, it.startMillis, it.error) })

                    val isExpanded = !collapsedGroups.contains(basePackage)
                    items.add(
                        com.github.kr328.clash.design.adapter.ConnectionItem.Group(
                            packageName = basePackage,
                            appName = appName,
                            appIcon = appIcon,
                            activeCount = activeCount,
                            totalCount = conns.size,
                            totalSpeed = totalSpeed,
                            totalUpload = processTraffic.upload,
                            totalDownload = processTraffic.download,
                            isExpanded = isExpanded
                        )
                    )

                    if (isExpanded) {
                        for (record in conns) {
                            val conn = record.connection
                            val speedBytes = connectionSpeeds[conn.id] ?: 0L
                            val uploadSpeedBytes = connectionUploadSpeeds[conn.id] ?: 0L
                            val speed = "↑ ${formatTraffic(uploadSpeedBytes)}  ↓ ${formatTraffic(speedBytes)}"
                            items.add(
                                com.github.kr328.clash.design.adapter.ConnectionItem.Child(
                                    connection = conn,
                                    speed = speed,
                                    status = record.status,
                                    error = record.error,
                                    count = record.count
                                )
                            )
                        }
                    }
                }

                adapter.submitList(items) {
                    if (scrollToTop) {
                        design.binding.recyclerView.scrollToPosition(0)
                    }
                }
                design.updateExpandCollapseIconState(collapsedGroups.isNotEmpty())
                detailsBinding?.let { binding ->
                    detailsConnectionId?.let { id ->
                        updateConnectionDetails(binding, id)
                    }
                }
            } catch (e: Exception) {
                Log.w("Failed to update connections UI", e)
            }
        }

        fun clearConnectionList(resetProcessFilter: Boolean = true) {
            connectionRecords.clear()
            mergedConnectionRecords.clear()
            failedConnectionRecords.clear()
            failedConnectionOrder.clear()
            connectionSpeeds.clear()
            connectionUploadSpeeds.clear()
            closedConnectionIds.clear()
            closedConnectionOrder.clear()
            if (resetProcessFilter) {
                selectedProcessKey = null
                selectedProxyKey = null
                uiStore.connectionProcessFilter = ""
                uiStore.connectionProxyFilter = ""
                design.setProcessFilterLabel(null)
                design.setProxyFilterLabel(null)
            }
            adapter.submitList(emptyList())
        }

        fun showProcessFilterMenu() {
            val options = mutableListOf<Pair<String?, String>>()
            options.add(null to getString(com.github.kr328.clash.design.R.string.connections_process_all))
            val menuProcessKeys = mutableSetOf<String>()
            connectionRecords.values.forEach { record ->
                if (!record.closed && !design.filterActive) return@forEach
                if (record.closed && !design.filterClosed) return@forEach
                if (selectedProxyKey != null && selectedProxyKey !in proxyNamesFor(record.connection)) return@forEach
                menuProcessKeys.add(normalizeProcessName(record.connection.metadata.process))
            }
            if (design.filterFailed) {
                failedConnectionRecords.values.forEach { record ->
                    val failed = record.failedConnection
                    if (selectedProxyKey != null && selectedProxyKey !in proxyNamesFor(failed)) return@forEach
                    menuProcessKeys.add(normalizeProcessName(failed.metadata.process))
                }
            }
            if (design.filterClosed && selectedProxyKey == null) {
                processTrafficTotals.forEach { (processKey, traffic) ->
                    if (traffic.upload > 0L || traffic.download > 0L) {
                        menuProcessKeys.add(processKey)
                    }
                }
            }
            menuProcessKeys
                .sortedBy { resolveAppName(it).lowercase(Locale.getDefault()) }
                .forEach { processKey ->
                    if (options.none { it.first == processKey }) {
                        options.add(processKey to resolveAppName(processKey))
                    }
                }

            val popup = PopupMenu(this@ConnectionsActivity, design.binding.chipProcess)
            options.forEachIndexed { index, option ->
                popup.menu.add(0, index, index, option.second)
            }
            popup.setOnMenuItemClickListener { item ->
                val option = options.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener true
                selectedProcessKey = option.first
                uiStore.connectionProcessFilter = option.first.orEmpty()
                design.setProcessFilterLabel(option.second)
                
                if (selectedProcessKey != null && selectedProxyKey != null) {
                    val availableProxies = mutableSetOf<String>()
                    connectionRecords.values.forEach { record ->
                        val status = if (record.closed) ConnectionStatus.CLOSED else ConnectionStatus.ACTIVE
                        if (status == ConnectionStatus.ACTIVE && !design.filterActive) return@forEach
                        if (status == ConnectionStatus.CLOSED && !design.filterClosed) return@forEach
                        val proc = normalizeProcessName(record.connection.metadata.process)
                        if (proc == selectedProcessKey) {
                            availableProxies.addAll(proxyNamesFor(record.connection))
                        }
                    }
                    if (design.filterFailed) {
                        failedConnectionRecords.values.forEach { record ->
                            val failed = record.failedConnection
                            val proc = normalizeProcessName(failed.metadata.process)
                            if (proc == selectedProcessKey) {
                                availableProxies.addAll(proxyNamesFor(failed))
                            }
                        }
                    }
                    if (!availableProxies.contains(selectedProxyKey)) {
                        selectedProxyKey = null
                        uiStore.connectionProxyFilter = ""
                        design.setProxyFilterLabel(null)
                    }
                }
                
                refreshConnectionList(true)
                true
            }
            popup.show()
        }

        fun showProxyFilterMenu() {
            val options = mutableListOf<Pair<String?, String>>()
            options.add(null to getString(com.github.kr328.clash.design.R.string.connections_proxy_all))
            val proxyNames = mutableSetOf<String>()
            connectionRecords.values.forEach { record ->
                if (!record.closed && !design.filterActive) return@forEach
                if (record.closed && !design.filterClosed) return@forEach
                if (selectedProcessKey != null && normalizeProcessName(record.connection.metadata.process) != selectedProcessKey) return@forEach
                proxyNames.addAll(proxyNamesFor(record.connection))
            }
            if (design.filterFailed) {
                failedConnectionRecords.values.forEach { record ->
                    val failed = record.failedConnection
                    if (selectedProcessKey != null && normalizeProcessName(failed.metadata.process) != selectedProcessKey) return@forEach
                    proxyNames.addAll(proxyNamesFor(failed))
                }
            }
            proxyNames
                .filter { it.isNotBlank() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .forEach { proxyName ->
                    options.add(proxyName to proxyName)
                }

            val popup = PopupMenu(this@ConnectionsActivity, design.binding.chipProxy)
            options.forEachIndexed { index, option ->
                popup.menu.add(0, index, index, option.second)
            }
            popup.setOnMenuItemClickListener { item ->
                val option = options.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener true
                selectedProxyKey = option.first
                uiStore.connectionProxyFilter = option.first.orEmpty()
                design.setProxyFilterLabel(option.second)
                refreshConnectionList(true)
                true
            }
            popup.show()
        }

        fun applyConnectionDiff(diff: ConnectionDiff) {
            try {
                val batchMillis = System.currentTimeMillis()
                processTrafficTotals = diff.processTraffic
                val reconcileSnapshot = awaitingSnapshotReconcile && diff.timestamp > 0L
                if (diff.timestamp > 0L) {
                    awaitingSnapshotReconcile = false
                }
                val newConnectionIds = diff.newConnections.mapTo(mutableSetOf()) { it.id }

                for (conn in diff.newConnections) {
                    closedConnectionIds.remove(conn.id)
                    closedConnectionOrder.remove(conn.id)
                    if (!connectionRecords.containsKey(conn.id)) {
                        connectionSpeeds[conn.id] = 0L
                        connectionUploadSpeeds[conn.id] = 0L
                    }
                    connectionRecords[conn.id] = ConnectionRecord(
                        connection = conn,
                        startMillis = parseConnectionStartMillis(conn.start)
                    )
                }

                for (failed in diff.newFailedConnections) {
                    if (failed.id.isBlank()) continue
                    if (!failedConnectionRecords.containsKey(failed.id)) {
                        failedConnectionOrder.addLast(failed.id)
                    }
                    failedConnectionRecords[failed.id] = FailedConnectionRecord(
                        failedConnection = failed,
                        failedAtMillis = parseConnectionStartMillis(failed.failedAt)
                    )
                }

                val removedDetailIds = diff.removedConnectionDetails.mapTo(mutableSetOf()) { it.id }
                for (conn in diff.removedConnectionDetails) {
                    markConnectionClosed(conn.id, conn, batchMillis)
                }

                for (id in diff.removedConnections) {
                    if (id !in removedDetailIds) {
                        markConnectionClosed(id, closedAtMillis = batchMillis)
                    }
                }

                if (reconcileSnapshot) {
                    val activeIds = newConnectionIds
                    connectionRecords
                        .filter { (id, record) -> !record.closed && id !in activeIds }
                        .keys
                        .toList()
                        .forEach { id -> markConnectionClosed(id, closedAtMillis = batchMillis) }
                }
                pruneClosedConnections()

                val updatedTrafficIds = mutableSetOf<String>()
                for (traffic in diff.updatedTraffics) {
                    if (traffic.id in newConnectionIds) continue

                    val record = connectionRecords[traffic.id]
                    val prevConn = record?.connection
                    if (record != null && prevConn != null && !record.closed) {
                        updatedTrafficIds.add(traffic.id)
                        connectionSpeeds[traffic.id] = boundedTrafficDelta(traffic.download - prevConn.download)
                        connectionUploadSpeeds[traffic.id] = boundedTrafficDelta(traffic.upload - prevConn.upload)
                        record.connection = prevConn.copy(download = traffic.download, upload = traffic.upload)
                    }
                }

                for ((id, record) in connectionRecords) {
                    if (!record.closed && id !in newConnectionIds && id !in updatedTrafficIds) {
                        connectionSpeeds[id] = 0L
                        connectionUploadSpeeds[id] = 0L
                    }
                }

                refreshConnectionList(false)
            } catch (e: Exception) {
                Log.w("Failed to update connections UI", e)
            }
        }

        val observer = object : IConnectionObserver {
            override fun onConnectionDiff(diff: ConnectionDiff) {
                diffChannel.trySend(diff)
            }
        }
        val observerBinder = com.github.kr328.clash.service.remote.IConnectionObserverDelegate(observer)
        this.activeObserver = observerBinder

        suspend fun stopObserver() {
            if (!observerRegistered) {
                awaitingSnapshotReconcile = false
                return
            }
            withContext(NonCancellable + Dispatchers.IO) {
                withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                    com.github.kr328.clash.util.withClash {
                        setConnectionObserver(null, design.refreshIntervalMillis)
                    }
                }
            }
            observerRegistered = false
            awaitingSnapshotReconcile = false
        }

        suspend fun unregisterObserver() {
            stopObserver()
        }

        suspend fun setConnectionHistoryEnabled(enabled: Boolean): Boolean {
            return withContext(Dispatchers.IO) {
                withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                    com.github.kr328.clash.util.withClash {
                        this.setConnectionHistoryEnabled(enabled)
                    }
                    true
                } ?: false
            }
        }

        suspend fun loadConnectionHistory() {
            val history = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                    com.github.kr328.clash.util.withClash {
                        this.queryConnectionHistory()
                    }
                }
            }
            if (history != null) {
                clearConnectionList(resetProcessFilter = false)
                awaitingSnapshotReconcile = false
                applyConnectionDiff(history)
            } else {
                Log.w("Failed to load connection history")
            }
        }

        suspend fun resetConnectionHistory() {
            unregisterObserver()
            setConnectionHistoryEnabled(false)
            if (design.trackingEnabled) {
                setConnectionHistoryEnabled(true)
            }
        }

        suspend fun registerObserver(force: Boolean = false) {
            if (!design.trackingEnabled) {
                stopObserver()
                return
            }

            if (!setConnectionHistoryEnabled(true)) {
                Log.w("Failed to enable connection history")
                return
            }

            if (!force && observerRegistered) {
                return
            }

            loadConnectionHistory()

            val registered = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                    com.github.kr328.clash.util.withClash {
                        setConnectionObserver(observerBinder, design.refreshIntervalMillis)
                    }
                    true
                } ?: false
            }

            observerRegistered = registered
            awaitingSnapshotReconcile = registered
            if (!registered) {
                Log.w("Failed to register connection observer")
            }
        }

        registerObserver(force = true)

        try {
            while (isActive) {
                select<Unit> {
                    events.onReceive {
                        when (it) {
                            Event.ActivityStart -> registerObserver(force = true)
                            Event.ActivityStop -> unregisterObserver()
                            Event.ServiceRecreated -> registerObserver(force = true)
                            else -> {}
                        }
                    }
                    design.requests.onReceive {
                        when (it) {
                            ConnectionsDesign.Request.Close -> finish()
                            ConnectionsDesign.Request.ClearConnections -> {
                                clearConnectionList(resetProcessFilter = false)
                                if (design.trackingEnabled) {
                                    resetConnectionHistory()
                                    registerObserver(force = true)
                                }
                            }
                            ConnectionsDesign.Request.FilterChanged -> refreshConnectionList(true)
                            ConnectionsDesign.Request.ProcessFilterClicked -> showProcessFilterMenu()
                            ConnectionsDesign.Request.ProxyFilterClicked -> showProxyFilterMenu()
                            ConnectionsDesign.Request.RefreshIntervalChanged -> registerObserver(force = true)
                            ConnectionsDesign.Request.TrackingChanged -> {
                                if (design.trackingEnabled) {
                                    registerObserver(force = true)
                                } else {
                                    unregisterObserver()
                                    setConnectionHistoryEnabled(false)
                                    clearConnectionList(resetProcessFilter = false)
                                }
                            }
                            ConnectionsDesign.Request.ToggleExpandCollapse -> {
                                val recordGroups = connectionRecords.values
                                    .filter { record ->
                                        if (!record.closed && !design.filterActive) return@filter false
                                        if (record.closed && !design.filterClosed) return@filter false
                                        val process = normalizeProcessName(record.connection.metadata.process)
                                        (selectedProcessKey == null || process == selectedProcessKey) &&
                                            (selectedProxyKey == null || selectedProxyKey in proxyNamesFor(record.connection))
                                    }
                                    .map { normalizeProcessName(it.connection.metadata.process) }
                                val failedGroups = if (design.filterFailed) {
                                    failedConnectionRecords.values
                                        .filter { record ->
                                            val failed = record.failedConnection
                                            val process = normalizeProcessName(failed.metadata.process)
                                            (selectedProcessKey == null || process == selectedProcessKey) &&
                                                (selectedProxyKey == null || selectedProxyKey in proxyNamesFor(failed))
                                        }
                                        .map { normalizeProcessName(it.failedConnection.metadata.process) }
                                } else {
                                    emptyList()
                                }
                                val trafficGroups = if (design.filterClosed && selectedProxyKey == null) {
                                    processTrafficTotals
                                        .filter { (process, traffic) ->
                                            (selectedProcessKey == null || process == selectedProcessKey) &&
                                                (traffic.upload > 0L || traffic.download > 0L)
                                        }
                                        .keys
                                } else {
                                    emptySet()
                                }
                                val allGroups = (recordGroups + failedGroups + trafficGroups).toSet()
                                collapsedGroups.retainAll(allGroups)
                                if (collapsedGroups.isEmpty()) {
                                    collapsedGroups.addAll(allGroups)
                                } else {
                                    collapsedGroups.clear()
                                }
                                refreshConnectionList(false)
                            }
                        }
                    }
                        diffChannel.onReceive { diff ->
                            if (!design.trackingEnabled) return@onReceive
                            applyConnectionDiff(diff)
                        }
                }
            }
        } finally {
            unregisterObserver()
            this.activeObserver = null
        }
    }

    private data class ConnectionRecord(
        var connection: Connection,
        var startMillis: Long?,
        var closedMillis: Long? = null,
        var durationMillis: Long? = null
    ) {
        val closed: Boolean
            get() = closedMillis != null

        fun close(closedAtMillis: Long) {
            if (closedMillis != null) return

            closedMillis = closedAtMillis
            durationMillis = startMillis?.let { (closedAtMillis - it).coerceAtLeast(0) }
        }
    }

    private data class FailedConnectionRecord(
        var failedConnection: FailedConnection,
        var failedAtMillis: Long?
    )

    companion object {
        private const val UNKNOWN_PACKAGE = "Unknown"
        private const val MAX_CLOSED_CONNECTIONS = 5000
        private const val MAX_FAILED_CONNECTIONS = 1000
        private const val MAX_REASONABLE_SPEED_BYTES_PER_SECOND = 10L * 1024L * 1024L * 1024L
        private const val REMOTE_CALL_TIMEOUT_MILLIS = 3_000L

        private val CONNECTION_START_FRACTION_REGEX = Regex("""\.(\d{1,9})(?=Z|[+-]\d{2}:?\d{2}$|$)""")
        private val CONNECTION_START_TIMEZONE_COLON_REGEX = Regex("""([+-]\d{2}):(\d{2})$""")
        private val CONNECTION_START_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss"
        )

        private fun normalizeConnectionStart(start: String): String {
            val normalizedFraction = CONNECTION_START_FRACTION_REGEX.replace(start.trim()) { match ->
                ".${match.groupValues[1].take(3).padEnd(3, '0')}"
            }
            val normalizedUtc = if (normalizedFraction.endsWith("Z")) {
                normalizedFraction.dropLast(1) + "+0000"
            } else {
                normalizedFraction
            }
            return CONNECTION_START_TIMEZONE_COLON_REGEX.replace(normalizedUtc) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}"
            }
        }
    }
}
