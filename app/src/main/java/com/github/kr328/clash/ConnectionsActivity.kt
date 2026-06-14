package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.core.model.ConnectionDiff
import com.github.kr328.clash.design.ConnectionsDesign
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
        val connectionSpeeds = mutableMapOf<String, Long>()
        val connectionUploadSpeeds = mutableMapOf<String, Long>()
        val packageNames = mutableMapOf<String, String>()
        val packageIcons = mutableMapOf<String, android.graphics.drawable.Drawable?>()
        val collapsedGroups = mutableSetOf<String>()
        val closedConnectionIds = mutableSetOf<String>()
        val closedConnectionOrder = java.util.ArrayDeque<String>()
        var observerRegistered = false
        var awaitingSnapshotReconcile = false
        var selectedProcessKey: String? = uiStore.connectionProcessFilter.takeIf { it.isNotBlank() }
        var detailsBinding: DesignConnectionDetailsBinding? = null
        var detailsConnectionId: String? = null
        val clockTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        lateinit var refreshConnectionList: () -> Unit

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

        fun pruneClosedConnections() {
            while (closedConnectionIds.size > MAX_CLOSED_CONNECTIONS && closedConnectionOrder.isNotEmpty()) {
                val expiredId = closedConnectionOrder.removeFirst()
                if (closedConnectionIds.remove(expiredId)) {
                    connectionRecords.remove(expiredId)
                    connectionSpeeds.remove(expiredId)
                    connectionUploadSpeeds.remove(expiredId)
                }
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
            val record = connectionRecords[id] ?: return
            val conn = record.connection
            val meta = conn.metadata
            val isClosed = record.closed
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
            binding.tvChain.text = conn.chains.joinToString(" -> ")
            binding.tvDuration.text = formatDuration(conn.start, record, nowMillis)
            binding.tvSpeed.text = if (isClosed) {
                "↓ 0 B/s  ↑ 0 B/s"
            } else {
                "↓ ${formatTraffic(connectionSpeeds[id] ?: 0L)}  ↑ ${formatTraffic(connectionUploadSpeeds[id] ?: 0L)}"
            }
            binding.tvUp.text = formatBytes(conn.upload)
            binding.tvDown.text = formatBytes(conn.download)
            binding.tvStatus.text = getString(
                if (isClosed) {
                    com.github.kr328.clash.design.R.string.closed
                } else {
                    com.github.kr328.clash.design.R.string.active
                }
            )
            binding.tvSnapshotTime.text = formatConnectionTimeRange(record, nowMillis)
            binding.tvMetadataType.text = meta.type.ifBlank { "N/A" }
            binding.tvSpecialProxy.text = meta.specialProxy.ifBlank { "N/A" }
            binding.tvSpecialRules.text = meta.specialRules.ifBlank { "N/A" }
            binding.btnCloseConnection.isEnabled = !isClosed
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
                refreshConnectionList()
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
                                closeConnection(conn.id)
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

        refreshConnectionList = {
            try {
                val filterActive = design.filterActive
                val filterClosed = design.filterClosed
                val sortType = design.sortType
                val processFilter = selectedProcessKey

                val allDisplayRecords = connectionRecords.values.filter { record ->
                    if (!record.closed && !filterActive) return@filter false
                    if (record.closed && !filterClosed) return@filter false
                    if (processFilter != null && normalizeProcessName(record.connection.metadata.process) != processFilter) return@filter false
                    true
                }

                val grouped = allDisplayRecords.groupBy {
                    normalizeProcessName(it.connection.metadata.process)
                }

                val sortedGrouped = grouped.entries.sortedWith { a, b ->
                    when (sortType) {
                        ConnectionsDesign.SortType.TIME -> {
                            val timeA = a.value.minOfOrNull { connectionStartSortKey(it) } ?: Long.MAX_VALUE
                            val timeB = b.value.minOfOrNull { connectionStartSortKey(it) } ?: Long.MAX_VALUE
                            timeA.compareTo(timeB).takeIf { it != 0 } ?: a.key.compareTo(b.key, ignoreCase = true)
                        }
                        ConnectionsDesign.SortType.NAME -> {
                            a.key.compareTo(b.key, ignoreCase = true)
                        }
                        ConnectionsDesign.SortType.SPEED_DOWN -> {
                            val sumA = a.value.sumOf { connectionSpeeds[it.connection.id] ?: 0L }
                            val sumB = b.value.sumOf { connectionSpeeds[it.connection.id] ?: 0L }
                            sumB.compareTo(sumA)
                        }
                        ConnectionsDesign.SortType.SPEED_UP -> {
                            val sumA = a.value.sumOf { connectionUploadSpeeds[it.connection.id] ?: 0L }
                            val sumB = b.value.sumOf { connectionUploadSpeeds[it.connection.id] ?: 0L }
                            sumB.compareTo(sumA)
                        }
                        ConnectionsDesign.SortType.TRAFFIC_DOWN -> {
                            val sumA = a.value.sumOf { it.connection.download }
                            val sumB = b.value.sumOf { it.connection.download }
                            sumB.compareTo(sumA)
                        }
                        ConnectionsDesign.SortType.TRAFFIC_UP -> {
                            val sumA = a.value.sumOf { it.connection.upload }
                            val sumB = b.value.sumOf { it.connection.upload }
                            sumB.compareTo(sumA)
                        }
                    }
                }

                val items = mutableListOf<com.github.kr328.clash.design.adapter.ConnectionItem>()

                for ((basePackage, connsUnsorted) in sortedGrouped) {
                    val conns = connsUnsorted.sortedWith { a, b ->
                        val connA = a.connection
                        val connB = b.connection
                        when (sortType) {
                            ConnectionsDesign.SortType.TIME -> {
                                val timeCompare = connectionStartSortKey(a).compareTo(connectionStartSortKey(b))
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

                    val activeCount = conns.count { !it.closed }
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
                    val totalSpeed = "↓ ${formatTraffic(totalSpeedBytes)}  ↑ ${formatTraffic(totalUploadSpeedBytes)}"
                    val totalUploadBytes = conns.sumOf { it.connection.upload }
                    val totalDownloadBytes = conns.sumOf { it.connection.download }

                    val isExpanded = !collapsedGroups.contains(basePackage)
                    items.add(
                        com.github.kr328.clash.design.adapter.ConnectionItem.Group(
                            packageName = basePackage,
                            appName = appName,
                            appIcon = appIcon,
                            activeCount = activeCount,
                            totalCount = conns.size,
                            totalSpeed = totalSpeed,
                            totalUpload = totalUploadBytes,
                            totalDownload = totalDownloadBytes,
                            isExpanded = isExpanded
                        )
                    )

                    if (isExpanded) {
                        for (record in conns) {
                            val conn = record.connection
                            val speedBytes = connectionSpeeds[conn.id] ?: 0L
                            val uploadSpeedBytes = connectionUploadSpeeds[conn.id] ?: 0L
                            val speed = "↓ ${formatTraffic(speedBytes)}  ↑ ${formatTraffic(uploadSpeedBytes)}"
                            items.add(com.github.kr328.clash.design.adapter.ConnectionItem.Child(conn, speed, !record.closed))
                        }
                    }
                }

                adapter.submitList(items)
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
            connectionSpeeds.clear()
            connectionUploadSpeeds.clear()
            collapsedGroups.clear()
            closedConnectionIds.clear()
            closedConnectionOrder.clear()
            if (resetProcessFilter) {
                selectedProcessKey = null
                uiStore.connectionProcessFilter = ""
                design.setProcessFilterLabel(null)
            }
            adapter.submitList(emptyList())
        }

        fun showProcessFilterMenu() {
            val options = mutableListOf<Pair<String?, String>>()
            options.add(null to getString(com.github.kr328.clash.design.R.string.connections_process_all))
            selectedProcessKey?.let { processKey ->
                options.add(processKey to resolveAppName(processKey))
            }
            connectionRecords.values
                .map { normalizeProcessName(it.connection.metadata.process) }
                .distinct()
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
                refreshConnectionList()
                true
            }
            popup.show()
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

        suspend fun registerObserver(force: Boolean = false) {
            if (!design.trackingEnabled) {
                stopObserver()
                return
            }

            if (!force && observerRegistered) return

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

        suspend fun unregisterObserver() {
            stopObserver()
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
                                registerObserver(force = true)
                            }
                            ConnectionsDesign.Request.FilterChanged -> {
                                refreshConnectionList()
                            }
                            ConnectionsDesign.Request.ProcessFilterClicked -> showProcessFilterMenu()
                            ConnectionsDesign.Request.RefreshIntervalChanged -> registerObserver(force = true)
                            ConnectionsDesign.Request.TrackingChanged -> {
                                if (design.trackingEnabled) {
                                    registerObserver(force = true)
                                } else {
                                    unregisterObserver()
                                    clearConnectionList(resetProcessFilter = false)
                                }
                            }
                            ConnectionsDesign.Request.ToggleExpandCollapse -> {
                                if (collapsedGroups.isEmpty()) {
                                    val allGroups = connectionRecords.values.map { 
                                        normalizeProcessName(it.connection.metadata.process) 
                                    }.toSet()
                                    collapsedGroups.addAll(allGroups)
                                } else {
                                    collapsedGroups.clear()
                                }
                                refreshConnectionList()
                            }
                        }
                    }
                    diffChannel.onReceive { diff ->
                        if (!design.trackingEnabled) return@onReceive

                        try {
                            val batchMillis = System.currentTimeMillis()
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

                            refreshConnectionList()
                        } catch (e: Exception) {
                            Log.w("Failed to update connections UI", e)
                        }
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

    companion object {
        private const val UNKNOWN_PACKAGE = "Unknown"
        private const val MAX_CLOSED_CONNECTIONS = 1000
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
