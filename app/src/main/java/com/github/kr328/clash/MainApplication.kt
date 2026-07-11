package com.github.kr328.clash

import android.app.Application
import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.clashDir
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

@Suppress("unused")
class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName
        extractGeoFiles()

        Log.d("Process $processName started")

        if (processName == packageName) {
            Remote.launch()
        } else {
            sendServiceRecreated()
        }
    }

    private fun extractGeoFiles() {
        check(clashDir.isDirectory || clashDir.mkdirs()) {
            "Unable to create core directory $clashDir"
        }

        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        if (GEO_ASSETS.all { isCurrentGeoAsset(it, updateDate) }) return

        RandomAccessFile(File(clashDir, GEO_ASSET_LOCK_FILE), "rw").channel.use { channel ->
            channel.lock().use {
                removeStaleGeoAssetStagingFiles()
                GEO_ASSETS.forEach { assetName ->
                    extractGeoAsset(assetName, updateDate)
                }
            }
        }
    }

    private fun extractGeoAsset(assetName: String, updateDate: Long) {
        val target = File(clashDir, assetName)
        if (isCurrentGeoAsset(assetName, updateDate)) return

        val staging = File.createTempFile(".$assetName.", ".tmp", clashDir)
        try {
            assets.open(assetName).use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(staging.length() > 0L) { "Bundled geo asset $assetName is empty" }

            Os.rename(staging.absolutePath, target.absolutePath)
            if (!target.setLastModified(updateDate)) {
                Log.w("Unable to update timestamp for geo asset $assetName")
            }
            syncDirectory(clashDir)
        } finally {
            if (staging.exists() && !staging.delete()) {
                Log.w("Unable to remove temporary geo asset $staging")
            }
        }
    }

    private fun isCurrentGeoAsset(assetName: String, updateDate: Long): Boolean {
        val target = File(clashDir, assetName)
        return target.isFile && target.length() > 0L && target.lastModified() >= updateDate
    }

    private fun removeStaleGeoAssetStagingFiles() {
        clashDir.listFiles()
            ?.filter { file ->
                file.isFile && GEO_ASSETS.any { assetName ->
                    file.name.startsWith(".$assetName.") && file.name.endsWith(".tmp")
                }
            }
            ?.forEach { file ->
                if (!file.delete()) Log.w("Unable to remove stale geo asset staging file $file")
            }
    }

    private fun syncDirectory(directory: File) {
        runCatching {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }.onFailure {
            Log.w("Unable to sync geo asset directory", it)
        }
    }

    fun finalize() {
        Global.destroy()
    }

    companion object {
        private const val GEO_ASSET_LOCK_FILE = ".geo-assets.lock"
        private val GEO_ASSETS = listOf(
            "geoip.metadb",
            "geosite.dat",
            "ASN.mmdb",
            "BundleMRS.7z"
        )
    }
}
