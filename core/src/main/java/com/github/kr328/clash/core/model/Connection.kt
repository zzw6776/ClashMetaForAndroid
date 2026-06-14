package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionSnapshot(
    val uploadTotal: Long = 0,
    val downloadTotal: Long = 0,
    val connections: List<Connection>? = emptyList()
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ConnectionSnapshot> {
        override fun createFromParcel(parcel: Parcel): ConnectionSnapshot {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ConnectionSnapshot?> {
            return arrayOfNulls(size)
        }
    }
}

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
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Connection> {
        override fun createFromParcel(parcel: Parcel): Connection {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<Connection?> {
            return arrayOfNulls(size)
        }
    }
}

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
    val destinationIPASN: String? = null,
    val dnsMode: String = ""
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Metadata> {
        override fun createFromParcel(parcel: Parcel): Metadata {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<Metadata?> {
            return arrayOfNulls(size)
        }
    }
}
