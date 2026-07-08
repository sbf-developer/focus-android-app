package com.focus.android.tracking

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.focus.android.data.FocusRepository
import com.focus.android.util.todayDateString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class UsageTracker(
    private val context: Context,
    private val repository: FocusRepository,
    private val scope: CoroutineScope,
) {
    private var pollJob: Job? = null

    @Volatile
    var currentApp: String? = null
        private set

    @Volatile
    var isIdle: Boolean = true
        private set

    private var lastEventTime: Long = 0L
    private var lastForegroundPackage: String? = null
    private var lastTickAt: Long = 0L

    fun start() {
        if (pollJob?.isActive == true) return
        lastTickAt = System.currentTimeMillis()
        pollJob = scope.launch {
            backfillToday()
            while (isActive) {
                tick()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun tick() {
        if (!hasUsageAccess()) {
            currentApp = null
            isIdle = true
            return
        }

        val now = System.currentTimeMillis()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usageStatsManager.queryEvents(now - POLL_INTERVAL_MS * 2, now)

        val event = UsageEvents.Event()
        var latestPackage: String? = lastForegroundPackage
        var latestTime = lastEventTime

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                if (event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    latestPackage = event.packageName
                }
            }
        }

        if (latestPackage != null && latestTime > lastEventTime) {
            lastEventTime = latestTime
            lastForegroundPackage = latestPackage
        }

        val elapsedSec = ((now - lastTickAt) / 1000).toInt().coerceAtLeast(1)
        lastTickAt = now

        val idle = lastEventTime == 0L || (now - lastEventTime) >= IDLE_THRESHOLD_MS
        isIdle = idle

        if (!idle && lastForegroundPackage != null) {
            val label = resolveAppLabel(lastForegroundPackage!!)
            currentApp = label
            repository.incrementAppTime(label, elapsedSec)
        } else {
            currentApp = null
        }
    }

    suspend fun backfillToday() {
        if (!hasUsageAccess()) return

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val now = System.currentTimeMillis()

        val events = usageStatsManager.queryEvents(startOfDay, now)
        val event = UsageEvents.Event()

        var foreground: String? = null
        var foregroundSince: Long = startOfDay
        val accumulated = mutableMapOf<String, Int>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (foreground != null) {
                        val secs = ((event.timeStamp - foregroundSince) / 1000).toInt()
                        if (secs > 0) {
                            val label = resolveAppLabel(foreground!!)
                            accumulated[label] = (accumulated[label] ?: 0) + secs
                        }
                    }
                    foreground = event.packageName
                    foregroundSince = event.timeStamp
                    lastEventTime = event.timeStamp
                    lastForegroundPackage = event.packageName
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (foreground != null && event.packageName == foreground) {
                        val secs = ((event.timeStamp - foregroundSince) / 1000).toInt()
                        if (secs > 0) {
                            val label = resolveAppLabel(foreground!!)
                            accumulated[label] = (accumulated[label] ?: 0) + secs
                        }
                        foreground = null
                    }
                }
            }
        }

        // Merge with existing stats without double-counting aggressively:
        // only add if today's stored total is less than backfill for that app
        val existing = repository.stats.value
        if (existing.date == todayDateString()) {
            val deltas = mutableMapOf<String, Int>()
            for ((app, secs) in accumulated) {
                val stored = existing.apps[app] ?: 0
                if (secs > stored) {
                    deltas[app] = secs - stored
                }
            }
            repository.backfillFromEvents(deltas)
        }
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        if (mode == AppOpsManager.MODE_ALLOWED) return true

        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - TimeUnit.HOURS.toMillis(1),
                now,
            )
            stats != null && stats.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        KNOWN_PACKAGES[packageName]?.let { return it }
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
        private const val IDLE_THRESHOLD_MS = 2 * 60 * 1000L

        private val KNOWN_PACKAGES = mapOf(
            "com.android.chrome" to "Chrome",
            "com.google.android.youtube" to "YouTube",
            "com.twitter.android" to "Twitter",
            "com.instagram.android" to "Instagram",
            "com.facebook.katana" to "Facebook",
            "com.reddit.frontpage" to "Reddit",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
        )
    }
}
