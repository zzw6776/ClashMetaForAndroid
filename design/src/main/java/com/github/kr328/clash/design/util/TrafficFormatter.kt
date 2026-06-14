package com.github.kr328.clash.design.util

import java.util.Locale

fun formatBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes < 1024) return "$safeBytes B"
    val kb = safeBytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatTraffic(bytesPerSecond: Long): String {
    return "${formatBytes(bytesPerSecond)}/s"
}
