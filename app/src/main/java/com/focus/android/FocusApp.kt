package com.focus.android

import android.app.Application
import com.focus.android.data.FocusRepository
import com.focus.android.tracking.UsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FocusApp : Application() {

    val repository: FocusRepository by lazy { FocusRepository(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val usageTracker: UsageTracker by lazy {
        UsageTracker(this, repository, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        usageTracker.start()
    }
}
