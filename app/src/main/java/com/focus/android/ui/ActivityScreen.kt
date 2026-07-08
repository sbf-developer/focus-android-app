package com.focus.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.focus.android.data.DailyStats
import com.focus.android.util.formatDuration
import com.focus.android.util.sortedEntries

@Composable
fun ActivityScreen(
    stats: DailyStats,
    modifier: Modifier = Modifier,
) {
    val appTotal = stats.apps.values.sum()
    val domainTotal = stats.domains.values.sum()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Activity",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Full breakdown for ${stats.date}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                CardContainer(title = "Apps · ${formatDuration(appTotal)}") {
                    ActivityList(
                        items = sortedEntries(stats.apps),
                        emptyMessage = "No app usage recorded today.",
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                CardContainer(title = "Websites · ${formatDuration(domainTotal)}") {
                    ActivityList(
                        items = sortedEntries(stats.domains),
                        emptyMessage = "No domains logged today. Domain stats appear while blocking is active.",
                        useDisplayDomain = true,
                    )
                }
            }
        }
    }
}
