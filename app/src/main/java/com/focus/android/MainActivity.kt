package com.focus.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focus.android.ui.ActivityScreen
import com.focus.android.ui.BlockScreen
import com.focus.android.ui.DashboardScreen
import com.focus.android.ui.FocusTheme
import com.focus.android.ui.FocusViewModel

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel?.onVpnPermissionResult(result.resultCode == RESULT_OK)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional */ }

    private var viewModel: FocusViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val vm: FocusViewModel = viewModel()
            viewModel = vm

            val stats by vm.stats.collectAsStateWithLifecycle()
            val blocklist by vm.blocklist.collectAsStateWithLifecycle()
            val status by vm.status.collectAsStateWithLifecycle()
            val vpnError by vm.vpnError.collectAsStateWithLifecycle()
            val vpnLaunchIntent by vm.vpnLaunchIntent.collectAsStateWithLifecycle()

            LaunchedEffect(vpnLaunchIntent) {
                vpnLaunchIntent?.let { intent ->
                    vpnPermissionLauncher.launch(intent)
                    vm.clearVpnLaunchIntent()
                }
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        vm.resumeBlockingIfNeeded()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            FocusTheme {
                FocusAppShell(
                    stats = stats,
                    blocklist = blocklist,
                    status = status,
                    vpnError = vpnError,
                    onToggleBlocking = vm::onToggleBlocking,
                    onToggleStartup = vm::setLaunchAtStartup,
                    onAddDomain = vm::addDomain,
                    onRemoveDomain = vm::removeDomain,
                    onRequestUsageAccess = { vm.openUsageAccessSettings(this@MainActivity) },
                    onRequestBatteryExemption = { vm.requestBatteryExemption(this@MainActivity) },
                )
            }
        }
    }
}

@Composable
private fun FocusAppShell(
    stats: com.focus.android.data.DailyStats,
    blocklist: List<String>,
    status: com.focus.android.data.AppStatus,
    vpnError: String?,
    onToggleBlocking: (Boolean) -> Unit,
    onToggleStartup: (Boolean) -> Unit,
    onAddDomain: (String) -> Unit,
    onRemoveDomain: (String) -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Block, contentDescription = "Block") },
                    label = { Text("Block") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Activity") },
                    label = { Text("Activity") },
                )
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> DashboardScreen(stats = stats, status = status, modifier = Modifier.padding(padding))
            1 -> BlockScreen(
                blocklist = blocklist,
                status = status,
                vpnError = vpnError,
                onToggleBlocking = onToggleBlocking,
                onToggleStartup = onToggleStartup,
                onAddDomain = onAddDomain,
                onRemoveDomain = onRemoveDomain,
                onRequestUsageAccess = onRequestUsageAccess,
                onRequestBatteryExemption = onRequestBatteryExemption,
                modifier = Modifier.padding(padding),
            )
            else -> ActivityScreen(stats = stats, modifier = Modifier.padding(padding))
        }
    }
}
