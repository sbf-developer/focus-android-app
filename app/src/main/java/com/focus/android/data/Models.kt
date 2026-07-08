package com.focus.android.data

data class DailyStats(
    val date: String,
    val apps: MutableMap<String, Int> = mutableMapOf(),
    val domains: MutableMap<String, Int> = mutableMapOf(),
)

data class AppSettings(
    val blockingEnabled: Boolean = false,
    val launchAtStartup: Boolean = true,
)

data class AppStatus(
    val currentApp: String? = null,
    val currentDomain: String? = null,
    val isIdle: Boolean = true,
    val blockingEnabled: Boolean = false,
    val vpnRunning: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val blocklistCount: Int = 0,
    val launchAtStartup: Boolean = true,
)
