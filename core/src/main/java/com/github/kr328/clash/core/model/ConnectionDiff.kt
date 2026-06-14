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
    val newConnections: List<Connection> = emptyList(),
    val removedConnections: List<String> = emptyList(),
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
