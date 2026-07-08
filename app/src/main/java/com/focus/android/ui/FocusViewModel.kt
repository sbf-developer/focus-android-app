package com.focus.android.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focus.android.FocusApp
import com.focus.android.data.AppSettings
import com.focus.android.data.AppStatus
import com.focus.android.data.DailyStats
import com.focus.android.data.FocusRepository
import com.focus.android.tracking.UsageTracker
import com.focus.android.vpn.FocusVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FocusViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FocusApp
    private val repository: FocusRepository = app.repository
    private val usageTracker: UsageTracker = app.usageTracker

    val stats: StateFlow<DailyStats> = repository.stats
    val blocklist = repository.blocklist
    val settings: StateFlow<AppSettings> = repository.settings

    private val _vpnError = MutableStateFlow<String?>(null)
    val vpnError: StateFlow<String?> = _vpnError.asStateFlow()

    private val trackerStatus = MutableStateFlow(TrackerSnapshot())

    val status: StateFlow<AppStatus> = combine(
        settings,
        blocklist,
        repository.currentDomain,
        trackerStatus,
    ) { settingsValue, blocklistValue, currentDomain, tracker ->
        AppStatus(
            currentApp = tracker.currentApp,
            currentDomain = currentDomain,
            isIdle = tracker.isIdle,
            blockingEnabled = settingsValue.blockingEnabled,
            vpnRunning = FocusVpnService.instanceRunning,
            usageAccessGranted = tracker.usageAccessGranted,
            blocklistCount = blocklistValue.size,
            launchAtStartup = settingsValue.launchAtStartup,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppStatus())

    init {
        viewModelScope.launch {
            while (true) {
                trackerStatus.value = TrackerSnapshot(
                    currentApp = usageTracker.currentApp,
                    isIdle = usageTracker.isIdle,
                    usageAccessGranted = usageTracker.hasUsageAccess(),
                )
                kotlinx.coroutines.delay(500)
            }
        }
    }

    fun refreshPermissions() {
        trackerStatus.value = trackerStatus.value.copy(
            usageAccessGranted = usageTracker.hasUsageAccess(),
        )
    }

    fun onToggleBlocking(enabled: Boolean) {
        viewModelScope.launch {
            _vpnError.value = null
            if (enabled) {
                val intent = VpnService.prepare(getApplication())
                if (intent != null) {
                    pendingVpnEnable = true
                    _vpnLaunchIntent.value = intent
                    return@launch
                }
                startVpn()
                repository.setBlockingEnabled(true)
            } else {
                repository.setBlockingEnabled(false)
                stopVpn()
            }
        }
    }

    fun clearVpnLaunchIntent() {
        _vpnLaunchIntent.value = null
    }

    fun onVpnPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            pendingVpnEnable = false
            _vpnLaunchIntent.value = null
            if (granted) {
                startVpn()
                repository.setBlockingEnabled(true)
            } else {
                _vpnError.value = "VPN permission denied. Blocking requires approval."
                repository.setBlockingEnabled(false)
            }
        }
    }

    private val _vpnLaunchIntent = MutableStateFlow<Intent?>(null)
    val vpnLaunchIntent: StateFlow<Intent?> = _vpnLaunchIntent.asStateFlow()

    private var pendingVpnEnable = false

    fun consumeVpnLaunchIntent(): Intent? {
        val intent = _vpnLaunchIntent.value
        _vpnLaunchIntent.value = null
        return intent
    }

    private fun startVpn() {
        try {
            FocusVpnService.start(getApplication())
        } catch (e: Exception) {
            _vpnError.value = "Failed to start VPN: ${e.message}"
        }
    }

    private fun stopVpn() {
        FocusVpnService.stop(getApplication())
    }

    fun setLaunchAtStartup(enabled: Boolean) {
        viewModelScope.launch {
            repository.setLaunchAtStartup(enabled)
        }
    }

    fun addDomain(domain: String) {
        viewModelScope.launch {
            repository.addDomain(domain)
        }
    }

    fun removeDomain(domain: String) {
        viewModelScope.launch {
            repository.removeDomain(domain)
        }
    }

    fun openUsageAccessSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    fun requestBatteryExemption(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun resumeBlockingIfNeeded() {
        viewModelScope.launch {
            val settingsValue = repository.getSettings()
            if (settingsValue.blockingEnabled && !FocusVpnService.instanceRunning) {
                if (VpnService.prepare(getApplication()) == null) {
                    startVpn()
                }
            }
            repository.refreshStatsForToday()
            usageTracker.backfillToday()
            refreshPermissions()
        }
    }

    private data class TrackerSnapshot(
        val currentApp: String? = null,
        val isIdle: Boolean = true,
        val usageAccessGranted: Boolean = false,
    )
}
