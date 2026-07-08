package com.focus.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focus.android.util.normalizeDomain
import com.focus.android.util.todayDateString
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "focus_settings")

class FocusRepository(private val context: Context) {

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsMutex = Mutex()

    private val dataDir: File
        get() = File(context.filesDir, "data").also { it.mkdirs() }

    private val _stats = MutableStateFlow(loadStatsForToday())
    val stats: StateFlow<DailyStats> = _stats.asStateFlow()

    private val _blocklist = MutableStateFlow<List<String>>(emptyList())
    val blocklist: StateFlow<List<String>> = _blocklist.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _currentDomain = MutableStateFlow<String?>(null)
    val currentDomain: StateFlow<String?> = _currentDomain.asStateFlow()

    companion object {
        val DEFAULT_BLOCKLIST = listOf(
            "youtube.com",
            "twitter.com",
            "x.com",
            "reddit.com",
            "facebook.com",
            "instagram.com",
            "tiktok.com",
        )

        private val KEY_BLOCKING = booleanPreferencesKey("blocking_enabled")
        private val KEY_LAUNCH_AT_STARTUP = booleanPreferencesKey("launch_at_startup")
        private val KEY_BLOCKLIST_JSON = stringPreferencesKey("blocklist_json")
    }

    init {
        scope.launch {
            loadPersistedState()
        }
    }

    private suspend fun loadPersistedState() {
        val prefs = context.dataStore.data.first()
        val blocklistJson = prefs[KEY_BLOCKLIST_JSON]
        val list = if (blocklistJson != null) {
            try {
                gson.fromJson<List<String>>(
                    blocklistJson,
                    object : TypeToken<List<String>>() {}.type,
                ) ?: DEFAULT_BLOCKLIST.toList()
            } catch (_: Exception) {
                DEFAULT_BLOCKLIST.toList()
            }
        } else {
            DEFAULT_BLOCKLIST.toList()
        }
        _blocklist.value = list
        if (blocklistJson == null) {
            saveBlocklistToStore(list)
        }

        _settings.value = AppSettings(
            blockingEnabled = prefs[KEY_BLOCKING] ?: false,
            launchAtStartup = prefs[KEY_LAUNCH_AT_STARTUP] ?: true,
        )
        writeJsonFile("settings.json", _settings.value)

        refreshStatsForToday()
    }

    suspend fun getSettings(): AppSettings = _settings.value

    suspend fun setBlockingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BLOCKING] = enabled
        }
        _settings.value = _settings.value.copy(blockingEnabled = enabled)
        writeJsonFile("settings.json", _settings.value)
    }

    suspend fun setLaunchAtStartup(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAUNCH_AT_STARTUP] = enabled
        }
        _settings.value = _settings.value.copy(launchAtStartup = enabled)
        writeJsonFile("settings.json", _settings.value)
    }

    fun getBlocklistSet(): Set<String> = _blocklist.value.map { normalizeDomain(it) }.toSet()

    suspend fun addDomain(domain: String): List<String> {
        val normalized = normalizeDomain(domain)
        if (normalized.isEmpty()) return _blocklist.value
        val updated = (_blocklist.value + normalized).distinct()
        _blocklist.value = updated
        saveBlocklistToStore(updated)
        return updated
    }

    suspend fun removeDomain(domain: String): List<String> {
        val normalized = normalizeDomain(domain)
        val updated = _blocklist.value.filter { normalizeDomain(it) != normalized }
        _blocklist.value = updated
        saveBlocklistToStore(updated)
        return updated
    }

    private suspend fun saveBlocklistToStore(list: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BLOCKLIST_JSON] = gson.toJson(list)
        }
        writeJsonFile("blocklist.json", list)
    }

    fun refreshStatsForToday() {
        _stats.value = loadStatsForToday()
    }

    private fun statsFile(date: String = todayDateString()): File {
        return File(dataDir, "stats-$date.json")
    }

    private fun loadStatsForToday(): DailyStats {
        val today = todayDateString()
        val file = statsFile(today)
        if (file.exists()) {
            try {
                val loaded = gson.fromJson(file.readText(), DailyStats::class.java)
                if (loaded != null && loaded.date == today) {
                    return loaded.copy(
                        apps = loaded.apps.toMutableMap(),
                        domains = loaded.domains.toMutableMap(),
                    )
                }
            } catch (_: Exception) {
                /* fall through */
            }
        }
        return DailyStats(date = today)
    }

    suspend fun incrementAppTime(appName: String, seconds: Int = 1) {
        if (seconds <= 0 || appName.isBlank()) return
        statsMutex.withLock {
            val today = todayDateString()
            var current = _stats.value
            if (current.date != today) {
                current = loadStatsForToday()
            }
            current.apps[appName] = (current.apps[appName] ?: 0) + seconds
            _stats.value = current
            persistStats(current)
        }
    }

    suspend fun incrementDomainTime(domain: String, seconds: Int = 1) {
        val normalized = normalizeDomain(domain)
        if (seconds <= 0 || normalized.isEmpty()) return
        statsMutex.withLock {
            val today = todayDateString()
            var current = _stats.value
            if (current.date != today) {
                current = loadStatsForToday()
            }
            current.domains[normalized] = (current.domains[normalized] ?: 0) + seconds
            _stats.value = current
            persistStats(current)
            _currentDomain.value = normalized
        }
    }

    fun setCurrentDomain(domain: String?) {
        _currentDomain.value = domain?.let { normalizeDomain(it) }
    }

    private fun persistStats(stats: DailyStats) {
        writeJsonFile("stats-${stats.date}.json", stats)
    }

    private fun writeJsonFile(name: String, data: Any) {
        try {
            File(dataDir, name).writeText(gson.toJson(data))
        } catch (_: Exception) {
            /* best effort */
        }
    }

    suspend fun backfillFromEvents(appDeltas: Map<String, Int>) {
        if (appDeltas.isEmpty()) return
        statsMutex.withLock {
            val today = todayDateString()
            var current = _stats.value
            if (current.date != today) {
                current = loadStatsForToday()
            }
            for ((app, secs) in appDeltas) {
                if (secs > 0) {
                    current.apps[app] = (current.apps[app] ?: 0) + secs
                }
            }
            _stats.value = current
            persistStats(current)
        }
    }
}
