package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionSnapshot(
    val uploadTotal: Long = 0,
    val downloadTotal: Long = 0,
    val connections: List<Connection>? = emptyList()
)

@Serializable
data class Connection(
    val id: String = "",
    val metadata: Metadata = Metadata(),
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = "",
    val chains: List<String> = emptyList(),
    val providerChains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = "",
    val dnsServer: String? = null
)

@Serializable
data class Metadata(
    val network: String = "",
    val type: String = "",
    val sourceIP: String = "",
    val destinationIP: String = "",
    val sourcePort: String = "",
    val destinationPort: String = "",
    val host: String = "",
    val uid: Int? = null,
    val process: String? = null,
    val processPath: String? = null,
    val sourceGeoIP: List<String>? = null,
    val destinationGeoIP: List<String>? = null,
    val sourceIPASN: String? = null,
    val destinationIPASN: String? = null
)
