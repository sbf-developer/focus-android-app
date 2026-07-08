package com.focus.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.focus.android.data.AppStatus
import com.focus.android.data.DailyStats
import com.focus.android.util.formatDuration
import com.focus.android.util.sortedEntries
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(
    stats: DailyStats,
    status: AppStatus,
    modifier: Modifier = Modifier,
) {
    val appTotal = stats.apps.values.sum()
    val domainTotal = stats.domains.values.sum()
    val topApp = sortedEntries(stats.apps).firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Today",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (status.isIdle) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ),
                )
                Column {
                    Text(
                        text = if (status.isIdle) "Idle" else "Active now",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when {
                            status.isIdle -> "Away from keyboard"
                            status.currentApp != null -> buildString {
                                append(status.currentApp)
                                status.currentDomain?.let { append(" · $it") }
                            }
                            else -> "—"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(modifier = Modifier.weight(1f)) {
                StatCard("App time", formatDuration(appTotal))
            }
            Card(modifier = Modifier.weight(1f)) {
                StatCard("Web time", formatDuration(domainTotal))
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            StatCard("Top app", topApp?.first ?: "—")
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                CardContainer(title = "Apps") {
                    ActivityList(
                        items = sortedEntries(stats.apps).take(8),
                        emptyMessage = "Start using apps — time will appear here.",
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                CardContainer(title = "Websites") {
                    ActivityList(
                        items = sortedEntries(stats.domains).take(8),
                        emptyMessage = "Browse the web — domains will appear here.",
                        useDisplayDomain = true,
                    )
                }
            }
        }
    }
}
