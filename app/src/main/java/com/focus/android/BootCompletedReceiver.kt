package com.focus.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focus.android.vpn.FocusVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext as FocusApp
        scope.launch {
            val settings = app.repository.getSettings()
            if (settings.blockingEnabled && settings.launchAtStartup) {
                FocusVpnService.start(context.applicationContext)
            }
        }
    }
}
