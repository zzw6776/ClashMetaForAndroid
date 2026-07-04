package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionTraffic(
    val id: String = "",
    val upload: Long = 0,
    val download: Long = 0
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ConnectionTraffic> {
        override fun createFromParcel(parcel: Parcel): ConnectionTraffic {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ConnectionTraffic?> {
            return arrayOfNulls(size)
        }
    }
}

@Serializable
data class ConnectionDiff(
    val timestamp: Long = 0,
    val totalUpload: Long = 0,
    val totalDownload: Long = 0,
    val processTraffic: Map<String, ProcessTraffic> = emptyMap(),
    val historyOverview: ConnectionHistoryOverview? = null,
    val newConnections: List<Connection> = emptyList(),
    val newFailedConnections: List<FailedConnection> = emptyList(),
    val removedConnections: List<String> = emptyList(),
    val removedConnectionDetails: List<Connection> = emptyList(),
    val removedConnectionClosedAt: Map<String, Long> = emptyMap(),
    val updatedTraffics: List<ConnectionTraffic> = emptyList()
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ConnectionDiff> {
        override fun createFromParcel(parcel: Parcel): ConnectionDiff {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ConnectionDiff?> {
            return arrayOfNulls(size)
        }
    }
}

@Serializable
data class ConnectionHistoryPage(
    val closedConnections: List<Connection> = emptyList(),
    val failedConnections: List<FailedConnection> = emptyList(),
    val closedAt: Map<String, Long> = emptyMap(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val totalCount: Int = 0
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ConnectionHistoryPage> {
        override fun createFromParcel(parcel: Parcel): ConnectionHistoryPage {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ConnectionHistoryPage?> = arrayOfNulls(size)
    }
}

@Serializable
data class ConnectionHistoryGroup(
    val process: String = "",
    val status: String = "",
    val proxy: String = "",
    val totalCount: Int = 0,
    val totalUpload: Long = 0,
    val totalDownload: Long = 0,
    val oldestUpdatedAt: Long = 0
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ConnectionHistoryGroup> {
        override fun createFromParcel(parcel: Parcel): ConnectionHistoryGroup {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ConnectionHistoryGroup?> = arrayOfNulls(size)
    }
}

@Serializable
data class ConnectionHistoryOverview(
    val groups: List<ConnectionHistoryGroup> = emptyList()
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ConnectionHistoryOverview> {
        override fun createFromParcel(parcel: Parcel): ConnectionHistoryOverview {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ConnectionHistoryOverview?> = arrayOfNulls(size)
    }
}
