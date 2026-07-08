package com.focus.android.util

fun normalizeDomain(raw: String): String {
    return raw
        .trim()
        .lowercase()
        .removeSuffix(".")
        .removePrefix("www.")
}

fun isDomainBlocked(domain: String, blocklist: Set<String>): Boolean {
    val normalized = normalizeDomain(domain)
    if (normalized.isEmpty()) return false
    for (blocked in blocklist) {
        val b = normalizeDomain(blocked)
        if (normalized == b || normalized.endsWith(".$b")) {
            return true
        }
    }
    return false
}

fun formatDuration(seconds: Int): String {
    if (seconds < 60) return "${seconds}s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

fun sortedEntries(map: Map<String, Int>): List<Pair<String, Int>> {
    return map.entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }
}

fun todayDateString(): String {
    return java.time.LocalDate.now().toString()
}

fun displayDomain(raw: String): String {
    val trimmed = raw.trim().lowercase().removePrefix("www.")
    val withoutProtocol = trimmed.removePrefix("https://").removePrefix("http://")
    return withoutProtocol.split('/', '?', '#', ':').firstOrNull()?.ifEmpty { raw } ?: raw
}
