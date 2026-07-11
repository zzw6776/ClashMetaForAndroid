package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.core.model.ConnectionDiff
import com.github.kr328.clash.core.model.ConnectionHistoryGroup
import com.github.kr328.clash.core.model.FailedConnection
import com.github.kr328.clash.core.model.ProcessTraffic
import com.github.kr328.clash.design.ConnectionsDesign
import com.github.kr328.clash.design.adapter.ConnectionStatus
import com.github.kr328.clash.design.databinding.DesignConnectionDetailsBinding
import com.github.kr328.clash.design.databinding.DesignConnectionDetailsPageBinding
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
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionsActivity : BaseActivity<ConnectionsDesign>() {
    private var activeObserver: Any? = null

    override suspend fun main() {
        val design = ConnectionsDesign(this, uiStore)

        setContentDesign(design)

        val connectionRecords = mutableMapOf<String, ConnectionRecord>()
        val failedConnectionRecords = mutableMapOf<String, FailedConnectionRecord>()
        val mergedConnectionRecords = mutableMapOf<String, ConnectionRecord>()
        val mergedConnectionMemberIds = mutableMapOf<String, List<String>>()
        val connectionSpeeds = mutableMapOf<String, Long>()
        val connectionUploadSpeeds = mutableMapOf<String, Long>()
        val connectionTrafficSampleMillis = mutableMapOf<String, Long>()
        val packageNames = mutableMapOf<String, String>()
        val packageIcons = mutableMapOf<String, android.graphics.drawable.Drawable?>()
        val collapsedGroups = mutableSetOf<String>()
        design.updateExpandCollapseIconState(collapsedGroups.isNotEmpty())
        var processTrafficTotals = emptyMap<String, ProcessTraffic>()
        var observerRegistered = false
        var awaitingSnapshotReconcile = false
        var historyPreferenceSynchronized = false
        var selectedProcessKey: String? = uiStore.connectionProcessFilter.takeIf { it.isNotBlank() }
        var selectedProxyKey: String? = uiStore.connectionProxyFilter.takeIf { it.isNotBlank() }
        var detailsBinding: DesignConnectionDetailsBinding? = null
        var detailsConnectionId: String? = null
        var detailsRefresh: (() -> Unit)? = null
        val clockTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        lateinit var refreshConnectionList: (scrollToTop: Boolean) -> Unit
        lateinit var reloadConnectionHistory: suspend () -> Unit
        lateinit var loadNextConnectionHistoryPage: suspend () -> Unit
        lateinit var loadNextProcessHistoryPage: suspend (String) -> Unit
        var historyOverviewGroups = emptyList<ConnectionHistoryGroup>()
        var historyNextOffset = 0
        var historyTotalCount = 0
        var historyHasMore = false
        var historyPageLoading = false
        var historyLoadGeneration = 0
        val historyProcessOffsets = mutableMapOf<String, Int>()
        val historyProcessTotals = mutableMapOf<String, Int>()
        val historyProcessHasMore = mutableSetOf<String>()
        val historyProcessLoading = mutableSetOf<String>()

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

        fun formatFailedConnectionTimeRange(record: FailedConnectionRecord?): String {
            val failedAt = record?.failedAtMillis?.let { formatClockTime(it) }
                ?: record?.failedConnection?.failedAt?.ifBlank { null }
                ?: "N/A"
            return "$failedAt - N/A"
        }

        fun speedBytesPerSecond(delta: Long, elapsedMillis: Long): Long {
            if (delta < 0L || elapsedMillis <= 0L) return 0L
            val speed = (delta.toDouble() * 1_000.0 / elapsedMillis.toDouble()).toLong()
            return if (speed in 0..MAX_REASONABLE_SPEED_BYTES_PER_SECOND) speed else 0L
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

        fun historyStatusVisible(status: String): Boolean {
            return when (status) {
                "CLOSED", "INTERRUPTED" -> design.filterClosed
                "FAILED" -> design.filterFailed
                else -> false
            }
        }

        fun matchingHistoryGroups(
            process: String? = selectedProcessKey,
            proxy: String? = selectedProxyKey
        ): List<ConnectionHistoryGroup> {
            return historyOverviewGroups.filter { group ->
                historyStatusVisible(group.status) &&
                    (process == null || group.process == process) &&
                    if (proxy == null) group.proxy.isEmpty() else group.proxy == proxy
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

        fun markConnectionClosed(
            id: String,
            connection: Connection? = null,
            closedAtMillis: Long = System.currentTimeMillis(),
            authoritativeClosedAt: Boolean = false
        ) {
            val record = connectionRecords[id]
            if (record != null) {
                if (connection != null) {
                    record.connection = connection
                    val parsedStartMillis = parseConnectionStartMillis(connection.start)
                    if (parsedStartMillis != null || record.startMillis == null) {
                        record.startMillis = parsedStartMillis
                    }
                }
                record.close(closedAtMillis, authoritativeClosedAt)
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

            connectionSpeeds[id] = 0L
            connectionUploadSpeeds[id] = 0L
            connectionTrafficSampleMillis.remove(id)
        }

        fun updateConnectionDetails(binding: DesignConnectionDetailsPageBinding, id: String) {
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
                formatFailedConnectionTimeRange(failedRecord)
            }
            binding.tvMetadataType.text = meta.type.ifBlank { "N/A" }
            binding.tvSpecialProxy.text = failedRecord?.failedConnection?.proxy?.takeIf { it.isNotBlank() }
                ?: meta.specialProxy.ifBlank { "N/A" }
            binding.tvSpecialRules.text = failedRecord?.failedConnection?.error?.takeIf { it.isNotBlank() }
                ?: meta.specialRules.ifBlank { "N/A" }
        }

        fun detailStatusFor(id: String): ConnectionStatus {
            failedConnectionRecords[id]?.let { return ConnectionStatus.FAILED }
            val record = mergedConnectionRecords[id] ?: connectionRecords[id]
            return if (record?.closed == true) {
                ConnectionStatus.CLOSED
            } else {
                ConnectionStatus.ACTIVE
            }
        }

        fun updateDetailAction(binding: DesignConnectionDetailsBinding, id: String) {
            binding.toolbar.menu.findItem(MENU_CLOSE_CONNECTION)?.isVisible =
                detailStatusFor(id) == ConnectionStatus.ACTIVE
        }

        val diffChannel = Channel<ConnectionDiff>(Channel.BUFFERED)
        val diffChannelOverflowed = AtomicBoolean(false)
        val deferredConnectionDiffs = mutableListOf<ConnectionDiff>()
        var historyReloading = false

        val adapter = com.github.kr328.clash.design.adapter.ConnectionAdapter(
            context = this,
            onGroupClick = { packageName ->
                val expanding = collapsedGroups.contains(packageName)
                if (expanding) {
                    collapsedGroups.remove(packageName)
                } else {
                    collapsedGroups.add(packageName)
                }
                refreshConnectionList(false)
                if (expanding) {
                    launch { loadNextProcessHistoryPage(packageName) }
                }
            },
            onLoadMore = { process ->
                launch {
                    if (process == null) {
                        loadNextConnectionHistoryPage()
                    } else {
                        loadNextProcessHistoryPage(process)
                    }
                }
            },
            onClick = { conn ->
                val binding = DesignConnectionDetailsBinding.inflate(layoutInflater)
                val dialog = com.github.kr328.clash.design.dialog.FullScreenDialog(this@ConnectionsActivity)
                binding.self = dialog
                dialog.setContentView(binding.root)

                binding.toolbar.menu.add(0, MENU_CLOSE_CONNECTION, 0, "Close Connection").apply {
                    setIcon(com.github.kr328.clash.design.R.drawable.ic_baseline_stop)
                    setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                }

                val detailIds = mergedConnectionMemberIds[conn.id]?.takeIf { it.size > 1 } ?: listOf(conn.id)
                var selectedDetailIndex = 0
                fun selectedDetailId(): String = detailIds[selectedDetailIndex]
                fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

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
                fun setupPageCopy(pageBinding: DesignConnectionDetailsPageBinding) {
                    setupCopy(pageBinding.tvDest, "Destination")
                    setupCopy(pageBinding.tvPort, "Port")
                    setupCopy(pageBinding.tvProcess, "Process")
                    setupCopy(pageBinding.tvNetwork, "Network")
                    setupCopy(pageBinding.tvRule, "Rule")
                    setupCopy(pageBinding.tvChain, "Chain")
                    setupCopy(pageBinding.tvIp, "IP")
                    setupCopy(pageBinding.tvCountry, "Country")
                    setupCopy(pageBinding.tvDns, "DNS")
                    setupCopy(pageBinding.tvDnsMode, "DNS Mode")
                    setupCopy(pageBinding.tvMetadataType, "Type")
                    setupCopy(pageBinding.tvSpecialProxy, "Special Proxy")
                    setupCopy(pageBinding.tvSpecialRules, "Special Rules")
                }

                val visibleDetailPages = mutableMapOf<Int, DesignConnectionDetailsPageBinding>()
                class DetailPageHolder(val pageBinding: DesignConnectionDetailsPageBinding) :
                    androidx.recyclerview.widget.RecyclerView.ViewHolder(pageBinding.root)

                val detailPagerAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<DetailPageHolder>() {
                    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DetailPageHolder {
                        val pageBinding = DesignConnectionDetailsPageBinding.inflate(layoutInflater, parent, false)
                        setupPageCopy(pageBinding)
                        return DetailPageHolder(pageBinding)
                    }

                    override fun onBindViewHolder(holder: DetailPageHolder, position: Int) {
                        visibleDetailPages[position] = holder.pageBinding
                        updateConnectionDetails(holder.pageBinding, detailIds[position])
                    }

                    override fun onViewRecycled(holder: DetailPageHolder) {
                        visibleDetailPages.entries.removeAll { it.value === holder.pageBinding }
                    }

                    override fun getItemCount(): Int = detailIds.size

                    fun refreshVisible() {
                        visibleDetailPages.forEach { (index, pageBinding) ->
                            detailIds.getOrNull(index)?.let { id ->
                                updateConnectionDetails(pageBinding, id)
                            }
                        }
                        updateDetailAction(binding, selectedDetailId())
                    }
                }

                fun makeTabBackground(selected: Boolean): android.graphics.drawable.Drawable {
                    return android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 18.dp().toFloat()
                        setColor(if (selected) 0xFFE3F2FD.toInt() else 0xFFF1F3F4.toInt())
                        if (selected) {
                            setStroke(1.dp(), android.graphics.Color.parseColor("#1E88E5"))
                        }
                    }
                }
                fun tabTextColor(id: String, selected: Boolean): Int {
                    return when (detailStatusFor(id)) {
                        ConnectionStatus.FAILED -> android.graphics.Color.parseColor("#B3261E")
                        ConnectionStatus.CLOSED -> android.graphics.Color.parseColor("#6F6F6F")
                        ConnectionStatus.ACTIVE -> if (selected) {
                            android.graphics.Color.parseColor("#0D47A1")
                        } else {
                            android.graphics.Color.parseColor("#333333")
                        }
                    }
                }
                lateinit var showDetailAt: (Int) -> Unit
                fun renderDetailTabs() {
                    binding.detailTabScroll.visibility = if (detailIds.size > 1) {
                        android.view.View.VISIBLE
                    } else {
                        android.view.View.GONE
                    }
                    binding.detailTabContainer.removeAllViews()
                    if (detailIds.size <= 1) return

                    detailIds.forEachIndexed { index, id ->
                        val selected = index == selectedDetailIndex
                        val tab = android.widget.TextView(this@ConnectionsActivity).apply {
                            text = (index + 1).toString()
                            gravity = android.view.Gravity.CENTER
                            minWidth = 44.dp()
                            height = 34.dp()
                            textSize = 14f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(tabTextColor(id, selected))
                            background = makeTabBackground(selected)
                            setOnClickListener {
                                showDetailAt(index)
                                binding.detailTabScroll.post {
                                    binding.detailTabScroll.smoothScrollTo((left - 24.dp()).coerceAtLeast(0), 0)
                                }
                            }
                        }
                        tab.layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = 8.dp()
                        }
                        binding.detailTabContainer.addView(tab)
                    }
                }
                fun selectDetailAt(index: Int) {
                    val nextIndex = index.coerceIn(0, detailIds.lastIndex)
                    if (nextIndex == selectedDetailIndex && detailsConnectionId == selectedDetailId()) return
                    selectedDetailIndex = nextIndex
                    detailsConnectionId = selectedDetailId()
                    visibleDetailPages[nextIndex]?.let { updateConnectionDetails(it, selectedDetailId()) }
                    updateDetailAction(binding, selectedDetailId())
                    renderDetailTabs()
                }
                showDetailAt = { index ->
                    val nextIndex = index.coerceIn(0, detailIds.lastIndex)
                    if (binding.detailsPager.currentItem != nextIndex) {
                        binding.detailsPager.setCurrentItem(nextIndex, true)
                    }
                    selectDetailAt(nextIndex)
                }

                binding.detailsPager.apply {
                    adapter = detailPagerAdapter
                    isUserInputEnabled = detailIds.size > 1
                    offscreenPageLimit = 1
                    registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            selectDetailAt(position)
                        }
                    })
                }

                detailsBinding = binding
                detailsConnectionId = selectedDetailId()
                detailsRefresh = { detailPagerAdapter.refreshVisible() }
                updateDetailAction(binding, selectedDetailId())
                renderDetailTabs()

                val detailTicker = this@ConnectionsActivity.launch {
                    while (isActive && detailsBinding === binding) {
                        kotlinx.coroutines.delay(1000L)
                        detailPagerAdapter.refreshVisible()
                    }
                }

                binding.toolbar.setNavigationOnClickListener { dialog.dismiss() }
                binding.toolbar.setOnMenuItemClickListener { item ->
                    if (item.itemId != MENU_CLOSE_CONNECTION) return@setOnMenuItemClickListener false

                    val closeId = selectedDetailId()
                    if (detailStatusFor(closeId) != ConnectionStatus.ACTIVE) {
                        updateDetailAction(binding, closeId)
                        return@setOnMenuItemClickListener true
                    }
                    val connectionIds = if (closeId.startsWith("merged|")) {
                        val parts = closeId.split("|")
                        if (parts.size >= 4) {
                            val basePkg = parts[1]
                            val hostK = parts[2]
                            connectionRecords.values
                                .asSequence()
                                .filterNot { it.closed }
                                .filter { record ->
                                    val pkg = normalizeProcessName(record.connection.metadata.process)
                                    val hk = record.connection.metadata.host
                                        .ifEmpty { record.connection.metadata.destinationIP }
                                    pkg == basePkg && hk == hostK
                                }
                                .map { it.connection.id }
                                .toList()
                        } else {
                            emptyList()
                        }
                    } else {
                        listOf(closeId)
                    }
                    this@ConnectionsActivity.launch(Dispatchers.IO) {
                        withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                            com.github.kr328.clash.util.withClash {
                                connectionIds.forEach(::closeConnection)
                            }
                        }
                    }
                    dialog.dismiss()
                    true
                }
                dialog.setOnDismissListener {
                    detailTicker.cancel()
                    binding.detailsPager.adapter = null
                    if (detailsBinding === binding) {
                        detailsBinding = null
                        detailsConnectionId = null
                        detailsRefresh = null
                    }
                }

                dialog.show()
            }
        )
        design.setAdapter(adapter)

        refreshConnectionList = { scrollToTop ->
            try {
                mergedConnectionRecords.clear()
                mergedConnectionMemberIds.clear()

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
                val historyGroups = matchingHistoryGroups(processFilter, proxyFilter)
                val historyGroupsByProcess = historyGroups.groupBy { it.process }
                data class ProcessGroup(
                    val process: String,
                    val records: List<DisplayRecord>
                )
                fun processTrafficForGroup(
                    process: String,
                    records: List<DisplayRecord>
                ): ProcessTraffic {
                    if (proxyFilter == null) {
                        processTrafficTotals[process]?.let { return it }
                    }
                    val activeRecords = records.filter { it.status == ConnectionStatus.ACTIVE }
                    val historical = historyGroupsByProcess[process].orEmpty()
                    return ProcessTraffic(
                        upload = activeRecords.sumOf { it.connection.upload } + historical.sumOf { it.totalUpload },
                        download = activeRecords.sumOf { it.connection.download } + historical.sumOf { it.totalDownload }
                    )
                }

                val processGroups = buildList {
                    grouped.forEach { (process, records) ->
                        add(ProcessGroup(process, records))
                    }
                    historyGroupsByProcess.keys.forEach { process ->
                        if (processFilter != null && process != processFilter) return@forEach
                        if (process !in grouped) add(ProcessGroup(process, emptyList()))
                    }
                }

                val sortedGrouped = processGroups.sortedWith { a, b ->
                    when (sortType) {
                        ConnectionsDesign.SortType.TIME -> {
                            val timeA = a.records.minOfOrNull { it.startMillis ?: Long.MAX_VALUE }
                                ?: historyGroupsByProcess[a.process].orEmpty().minOfOrNull { it.oldestUpdatedAt }
                                ?: Long.MAX_VALUE
                            val timeB = b.records.minOfOrNull { it.startMillis ?: Long.MAX_VALUE }
                                ?: historyGroupsByProcess[b.process].orEmpty().minOfOrNull { it.oldestUpdatedAt }
                                ?: Long.MAX_VALUE
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
                                mergedConnectionMemberIds[mergedId] = recordList.map { it.connection.id }
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
                    val historicalTotalCount = historyGroupsByProcess[basePackage].orEmpty().sumOf { it.totalCount }
                    val loadedHistoricalCount = conns.count { it.status != ConnectionStatus.ACTIVE }
                    val processLoadedOffset = historyProcessOffsets[basePackage] ?: 0
                    val processTotal = historyProcessTotals[basePackage] ?: historicalTotalCount
                    val processHasMore = selectedProcessKey == null && (
                        basePackage in historyProcessHasMore ||
                            (processLoadedOffset == 0 && loadedHistoricalCount < historicalTotalCount)
                        )

                    val isExpanded = !collapsedGroups.contains(basePackage)
                    items.add(
                        com.github.kr328.clash.design.adapter.ConnectionItem.Group(
                            packageName = basePackage,
                            appName = appName,
                            appIcon = appIcon,
                            activeCount = activeCount,
                            totalCount = activeCount + maxOf(historicalTotalCount, loadedHistoricalCount),
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
                        if (processHasMore) {
                            items.add(
                                com.github.kr328.clash.design.adapter.ConnectionItem.LoadMore(
                                    process = basePackage,
                                    loadedCount = processLoadedOffset.coerceAtLeast(loadedHistoricalCount),
                                    totalCount = processTotal
                                )
                            )
                        }
                    }
                }
                if (historyHasMore) {
                    items.add(
                        com.github.kr328.clash.design.adapter.ConnectionItem.LoadMore(
                            loadedCount = historyNextOffset,
                            totalCount = historyTotalCount
                        )
                    )
                }

                adapter.submitList(items) {
                    if (scrollToTop) {
                        design.binding.recyclerView.scrollToPosition(0)
                    }
                }
                design.updateExpandCollapseIconState(collapsedGroups.isNotEmpty())
                detailsRefresh?.invoke()
            } catch (e: Exception) {
                Log.w("Failed to update connections UI", e)
            }
        }

        fun clearConnectionList(
            resetProcessFilter: Boolean = true,
            updateAdapter: Boolean = true,
            invalidateHistoryLoad: Boolean = true
        ) {
            if (invalidateHistoryLoad) historyLoadGeneration++
            historyNextOffset = 0
            historyTotalCount = 0
            historyHasMore = false
            historyPageLoading = false
            historyOverviewGroups = emptyList()
            historyProcessOffsets.clear()
            historyProcessTotals.clear()
            historyProcessHasMore.clear()
            historyProcessLoading.clear()
            connectionRecords.clear()
            mergedConnectionRecords.clear()
            failedConnectionRecords.clear()
            connectionSpeeds.clear()
            connectionUploadSpeeds.clear()
            connectionTrafficSampleMillis.clear()
            if (resetProcessFilter) {
                selectedProcessKey = null
                selectedProxyKey = null
                uiStore.connectionProcessFilter = ""
                uiStore.connectionProxyFilter = ""
                design.setProcessFilterLabel(null)
                design.setProxyFilterLabel(null)
            }
            if (updateAdapter) adapter.submitList(emptyList())
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
            matchingHistoryGroups(process = null, proxy = selectedProxyKey)
                .mapTo(menuProcessKeys) { it.process }
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
                    historyOverviewGroups
                        .asSequence()
                        .filter { historyStatusVisible(it.status) }
                        .filter { it.process == selectedProcessKey && it.proxy.isNotEmpty() }
                        .mapTo(availableProxies) { it.proxy }
                    if (!availableProxies.contains(selectedProxyKey)) {
                        selectedProxyKey = null
                        uiStore.connectionProxyFilter = ""
                        design.setProxyFilterLabel(null)
                    }
                }
                
                launch { reloadConnectionHistory() }
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
            historyOverviewGroups
                .asSequence()
                .filter { historyStatusVisible(it.status) }
                .filter { selectedProcessKey == null || it.process == selectedProcessKey }
                .filter { it.proxy.isNotEmpty() }
                .mapTo(proxyNames) { it.proxy }
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
                launch { reloadConnectionHistory() }
                true
            }
            popup.show()
        }

        fun applyConnectionDiff(diff: ConnectionDiff, refresh: Boolean = true) {
            try {
                val batchMillis = System.currentTimeMillis()
                val sampleMillis = diff.timestamp.takeIf { it > 0L } ?: batchMillis
                processTrafficTotals = diff.processTraffic
                diff.historyOverview?.let { historyOverviewGroups = it.groups }
                val reconcileSnapshot = awaitingSnapshotReconcile && diff.timestamp > 0L
                if (diff.timestamp > 0L) {
                    awaitingSnapshotReconcile = false
                }
                val newConnectionIds = diff.newConnections.mapTo(mutableSetOf()) { it.id }

                for (conn in diff.newConnections) {
                    val previous = connectionRecords[conn.id]
                    val previousSampleMillis = connectionTrafficSampleMillis[conn.id]
                    if (previous == null || previous.closed || previousSampleMillis == null) {
                        connectionSpeeds[conn.id] = 0L
                        connectionUploadSpeeds[conn.id] = 0L
                    } else {
                        val elapsedMillis = sampleMillis - previousSampleMillis
                        connectionSpeeds[conn.id] = speedBytesPerSecond(
                            conn.download - previous.connection.download,
                            elapsedMillis
                        )
                        connectionUploadSpeeds[conn.id] = speedBytesPerSecond(
                            conn.upload - previous.connection.upload,
                            elapsedMillis
                        )
                    }
                    connectionTrafficSampleMillis[conn.id] = sampleMillis
                    connectionRecords[conn.id] = ConnectionRecord(
                        connection = conn,
                        startMillis = parseConnectionStartMillis(conn.start)
                    )
                }

                for (failed in diff.newFailedConnections) {
                    if (failed.id.isBlank()) continue
                    failedConnectionRecords[failed.id] = FailedConnectionRecord(
                        failedConnection = failed,
                        failedAtMillis = parseConnectionStartMillis(failed.failedAt)
                    )
                }

                val removedDetailIds = diff.removedConnectionDetails.mapTo(mutableSetOf()) { it.id }
                for (conn in diff.removedConnectionDetails) {
                    markConnectionClosed(
                        conn.id,
                        conn,
                        diff.removedConnectionClosedAt[conn.id] ?: batchMillis,
                        conn.id in diff.removedConnectionClosedAt
                    )
                }

                for (id in diff.removedConnections) {
                    if (id !in removedDetailIds) {
                        markConnectionClosed(
                            id,
                            closedAtMillis = diff.removedConnectionClosedAt[id] ?: batchMillis,
                            authoritativeClosedAt = id in diff.removedConnectionClosedAt
                        )
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
                val updatedTrafficIds = mutableSetOf<String>()
                for (traffic in diff.updatedTraffics) {
                    if (traffic.id in newConnectionIds) continue

                    val record = connectionRecords[traffic.id]
                    val prevConn = record?.connection
                    if (record != null && prevConn != null && !record.closed) {
                        updatedTrafficIds.add(traffic.id)
                        val elapsedMillis = connectionTrafficSampleMillis[traffic.id]
                            ?.let { sampleMillis - it }
                            ?: 0L
                        connectionSpeeds[traffic.id] = speedBytesPerSecond(
                            traffic.download - prevConn.download,
                            elapsedMillis
                        )
                        connectionUploadSpeeds[traffic.id] = speedBytesPerSecond(
                            traffic.upload - prevConn.upload,
                            elapsedMillis
                        )
                        connectionTrafficSampleMillis[traffic.id] = sampleMillis
                        record.connection = prevConn.copy(download = traffic.download, upload = traffic.upload)
                    }
                }

                for ((id, record) in connectionRecords) {
                    if (!record.closed && id !in newConnectionIds && id !in updatedTrafficIds) {
                        connectionSpeeds[id] = 0L
                        connectionUploadSpeeds[id] = 0L
                    }
                }

                if (refresh) {
                    refreshConnectionList(false)
                }
            } catch (e: Exception) {
                Log.w("Failed to update connections UI", e)
            }
        }

        val observer = object : IConnectionObserver {
            override fun onConnectionDiff(diff: ConnectionDiff) {
                if (diffChannel.trySend(diff).isFailure) {
                    diffChannelOverflowed.set(true)
                }
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

        suspend fun ensureConnectionHistoryEnabled(): Boolean {
            return withContext(Dispatchers.IO) {
                withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                    com.github.kr328.clash.util.withClash {
                        if (!isConnectionHistoryEnabled()) {
                            setConnectionHistoryEnabled(true)
                        }
                    }
                    true
                } ?: false
            }
        }

        loadNextConnectionHistoryPage = loadNext@{
            if (historyPageLoading || !historyHasMore) return@loadNext

            val generation = historyLoadGeneration
            val offset = historyNextOffset
            historyPageLoading = true
            try {
                val page = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                        com.github.kr328.clash.util.withClash {
                            queryConnectionHistoryPage(
                                offset,
                                HISTORY_PAGE_SIZE,
                                selectedProcessKey.orEmpty(),
                                selectedProxyKey.orEmpty(),
                                design.filterClosed,
                                design.filterFailed
                            )
                        }
                    }
                }
                if (generation != historyLoadGeneration) return@loadNext
                if (page == null) {
                    Log.w("Failed to load connection history page at offset $offset")
                    return@loadNext
                }

                applyConnectionDiff(
                    ConnectionDiff(
                        timestamp = System.currentTimeMillis(),
                        processTraffic = processTrafficTotals,
                        newFailedConnections = page.failedConnections,
                        removedConnections = page.closedConnections.map { it.id },
                        removedConnectionDetails = page.closedConnections,
                        removedConnectionClosedAt = page.closedAt
                    ),
                    refresh = false
                )
                historyNextOffset = page.nextOffset
                historyTotalCount = page.totalCount
                historyHasMore = page.hasMore && page.nextOffset > offset
                refreshConnectionList(false)
            } finally {
                if (generation == historyLoadGeneration) historyPageLoading = false
            }
        }

        loadNextProcessHistoryPage = loadProcess@{ process ->
            if (process in historyProcessLoading) return@loadProcess

            val summaryTotal = matchingHistoryGroups(process, selectedProxyKey).sumOf { it.totalCount }
            val offset = historyProcessOffsets[process] ?: 0
            if (offset > 0 && process !in historyProcessHasMore) return@loadProcess
            if (summaryTotal == 0) return@loadProcess

            val generation = historyLoadGeneration
            historyProcessLoading.add(process)
            try {
                val page = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                        com.github.kr328.clash.util.withClash {
                            queryConnectionHistoryPage(
                                offset,
                                HISTORY_PAGE_SIZE,
                                process,
                                selectedProxyKey.orEmpty(),
                                design.filterClosed,
                                design.filterFailed
                            )
                        }
                    }
                }
                if (generation != historyLoadGeneration) return@loadProcess
                if (page == null) {
                    Log.w("Failed to load history page for process $process at offset $offset")
                    refreshConnectionList(false)
                    return@loadProcess
                }

                applyConnectionDiff(
                    ConnectionDiff(
                        timestamp = System.currentTimeMillis(),
                        processTraffic = processTrafficTotals,
                        newFailedConnections = page.failedConnections,
                        removedConnections = page.closedConnections.map { it.id },
                        removedConnectionDetails = page.closedConnections,
                        removedConnectionClosedAt = page.closedAt
                    ),
                    refresh = false
                )
                historyProcessOffsets[process] = page.nextOffset
                historyProcessTotals[process] = page.totalCount
                if (page.hasMore && page.nextOffset > offset) {
                    historyProcessHasMore.add(process)
                } else {
                    historyProcessHasMore.remove(process)
                }
                refreshConnectionList(false)
            } finally {
                if (generation == historyLoadGeneration) historyProcessLoading.remove(process)
            }
        }

        suspend fun loadConnectionHistory() {
            historyLoadGeneration++
            awaitingSnapshotReconcile = false
            val generation = historyLoadGeneration
            historyReloading = true

            try {
                val loaded = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(REMOTE_CALL_TIMEOUT_MILLIS) {
                        com.github.kr328.clash.util.withClash {
                            Triple(
                                queryConnectionHistory(),
                                queryConnectionHistoryOverview(),
                                queryConnectionHistoryPage(
                                    0,
                                    HISTORY_PAGE_SIZE,
                                    selectedProcessKey.orEmpty(),
                                    selectedProxyKey.orEmpty(),
                                    design.filterClosed,
                                    design.filterFailed
                                )
                            )
                        }
                    }
                }
                if (loaded == null) {
                    Log.w("Failed to load connection history")
                    return
                }
                if (generation != historyLoadGeneration) return
                val (summary, overview, firstPage) = loaded
                clearConnectionList(
                    resetProcessFilter = false,
                    updateAdapter = false,
                    invalidateHistoryLoad = false
                )
                historyOverviewGroups = overview.groups
                applyConnectionDiff(summary, refresh = false)
                applyConnectionDiff(
                    ConnectionDiff(
                        processTraffic = processTrafficTotals,
                        newFailedConnections = firstPage.failedConnections,
                        removedConnections = firstPage.closedConnections.map { it.id },
                        removedConnectionDetails = firstPage.closedConnections,
                        removedConnectionClosedAt = firstPage.closedAt
                    ),
                    refresh = false
                )
                historyNextOffset = firstPage.nextOffset
                historyTotalCount = firstPage.totalCount
                historyHasMore = firstPage.hasMore && firstPage.nextOffset > 0
                deferredConnectionDiffs.forEach { applyConnectionDiff(it, refresh = false) }
                deferredConnectionDiffs.clear()
                refreshConnectionList(false)
            } finally {
                if (generation == historyLoadGeneration) {
                    historyReloading = false
                    if (deferredConnectionDiffs.isNotEmpty()) {
                        deferredConnectionDiffs.forEach { applyConnectionDiff(it, refresh = false) }
                        deferredConnectionDiffs.clear()
                        refreshConnectionList(false)
                    }
                }
            }
        }

        reloadConnectionHistory = { loadConnectionHistory() }

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
                if (!historyPreferenceSynchronized) {
                    historyPreferenceSynchronized = setConnectionHistoryEnabled(false)
                    if (!historyPreferenceSynchronized) {
                        Log.w("Failed to disable connection history")
                    }
                }
                return
            }

            if (!ensureConnectionHistoryEnabled()) {
                Log.w("Failed to enable connection history")
                return
            }
            historyPreferenceSynchronized = true

            if (!force && observerRegistered) {
                return
            }

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
            loadConnectionHistory()
        }

        registerObserver(force = true)

        try {
            while (isActive) {
                select<Unit> {
                    events.onReceive {
                        when (it) {
                            Event.ActivityStart -> registerObserver(force = true)
                            Event.ActivityStop -> unregisterObserver()
                            Event.ServiceRecreated -> {
                                historyPreferenceSynchronized = false
                                registerObserver(force = true)
                            }
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
                            ConnectionsDesign.Request.FilterChanged -> loadConnectionHistory()
                            ConnectionsDesign.Request.ProcessFilterClicked -> showProcessFilterMenu()
                            ConnectionsDesign.Request.ProxyFilterClicked -> showProxyFilterMenu()
                            ConnectionsDesign.Request.RefreshIntervalChanged -> registerObserver(force = true)
                            ConnectionsDesign.Request.TrackingChanged -> {
                                if (design.trackingEnabled) {
                                    registerObserver(force = true)
                                } else {
                                    unregisterObserver()
                                    historyPreferenceSynchronized = setConnectionHistoryEnabled(false)
                                    if (!historyPreferenceSynchronized) {
                                        Log.w("Failed to disable connection history")
                                    }
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
                                val historyGroups = matchingHistoryGroups().map { it.process }
                                val allGroups = (recordGroups + failedGroups + historyGroups).toSet()
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
                            if (diffChannelOverflowed.getAndSet(false)) {
                                while (diffChannel.tryReceive().isSuccess) Unit
                                loadConnectionHistory()
                                return@onReceive
                            }
                            if (historyReloading) {
                                deferredConnectionDiffs.add(diff)
                            } else {
                                applyConnectionDiff(diff)
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

        fun close(closedAtMillis: Long, authoritative: Boolean = false) {
            if (closedMillis != null && !authoritative) return

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
        private const val MAX_REASONABLE_SPEED_BYTES_PER_SECOND = 10L * 1024L * 1024L * 1024L
        private const val REMOTE_CALL_TIMEOUT_MILLIS = 3_000L
        private const val HISTORY_PAGE_SIZE = 100
        private const val MENU_CLOSE_CONNECTION = 1

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
