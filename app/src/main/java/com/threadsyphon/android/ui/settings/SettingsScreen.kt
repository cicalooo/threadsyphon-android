package com.threadsyphon.android.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.data.model.AppSettings
import com.threadsyphon.android.data.model.Constants
import com.threadsyphon.android.data.model.DownloadLocation
import com.threadsyphon.android.util.BatteryHelper
import com.threadsyphon.android.util.StorageHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: WatchRepository) {
    val settings by repository.settings().collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var ignoringBattery by remember { mutableStateOf(BatteryHelper.isIgnoringOptimizations(context)) }
    var intervalText by remember(settings.defaultInterval) { mutableStateOf(settings.defaultInterval.toString()) }
    var maxMbText by remember(settings.maxFileMb) { mutableStateOf(settings.maxFileMb.toString()) }

    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch { repository.updateSettings(block) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Watching", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            LabeledSwitch("Event notifications", settings.notifications) { v -> update { it.copy(notifications = v) } }
            OutlinedTextField(
                intervalText, { intervalText = it.filter(Char::isDigit).take(5) },
                label = { Text("Default check interval (seconds)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        intervalText.toIntOrNull()?.let { v -> update { it.copy(defaultInterval = v.coerceIn(15, 3600)) } }
                    }) { Text("Apply") }
                },
            )

            HorizontalDivider()
            Text("Media", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Filter", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Constants.MEDIA_FILTERS.forEach { mode ->
                    FilterChip(selected = settings.mediaFilter == mode, onClick = { update { it.copy(mediaFilter = mode) } }, label = { Text(mode) })
                }
            }
            Text("Filename mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Constants.FILENAME_MODES.forEach { mode ->
                    FilterChip(selected = settings.filenameMode == mode, onClick = { update { it.copy(filenameMode = mode) } }, label = { Text(mode) })
                }
            }
            LabeledSwitch("Verify MD5 when provided", settings.verifyMd5) { v -> update { it.copy(verifyMd5 = v) } }
            OutlinedTextField(
                maxMbText, { maxMbText = it.filter(Char::isDigit).take(5) },
                label = { Text("Max file size MB (0 = unlimited)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        maxMbText.toIntOrNull()?.let { v -> update { it.copy(maxFileMb = v.coerceAtLeast(0)) } }
                    }) { Text("Apply") }
                },
            )

            HorizontalDivider()
            Text("Download location", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = settings.downloadLocation == DownloadLocation.AppExternal, onClick = { update { it.copy(downloadLocation = DownloadLocation.AppExternal) } }, label = { Text("App storage") })
                FilterChip(selected = settings.downloadLocation == DownloadLocation.MediaStoreDownloads, onClick = { update { it.copy(downloadLocation = DownloadLocation.MediaStoreDownloads) } }, label = { Text("Downloads") })
            }
            Text(
                when (settings.downloadLocation) {
                    DownloadLocation.AppExternal -> "App-specific: ${StorageHelper.appExternalRoot(context).absolutePath}"
                    DownloadLocation.MediaStoreDownloads -> "Public: ${StorageHelper.publicDownloadsHint()} (via MediaStore)"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("Network", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Wi‑Fi only by default. Allow mobile data only if you accept metered usage.", style = MaterialTheme.typography.bodyMedium)
            LabeledSwitch("Allow mobile data", settings.allowMobileData) { v -> update { it.copy(allowMobileData = v, wifiOnly = !v) } }
            Text("API gap ${settings.rateGap}s · CDN gap ${settings.cdnGap}s", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("Battery", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Android may pause background downloads. Opt in to ignore battery optimizations so watching stays reliable.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(if (ignoringBattery) "Status: unrestricted" else "Status: optimized (may delay checks)", style = MaterialTheme.typography.labelLarge)
            Button(onClick = {
                try { context.startActivity(BatteryHelper.requestIgnoreOptimizationsIntent(context)) }
                catch (_: Exception) { context.startActivity(BatteryHelper.openBatterySettingsIntent(context)) }
                ignoringBattery = BatteryHelper.isIgnoringOptimizations(context)
                Toast.makeText(context, "Check battery setting, then return", Toast.LENGTH_SHORT).show()
            }) { Text("Request ignore battery optimizations") }

            HorizontalDivider()
            Text("Appearance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            LabeledSwitch("Follow system dark mode", settings.followSystemTheme) { v -> update { it.copy(followSystemTheme = v) } }
            if (!settings.followSystemTheme) {
                LabeledSwitch("Dark theme", settings.darkTheme) { v -> update { it.copy(darkTheme = v) } }
            }
            LabeledSwitch("Dynamic color (Material You)", settings.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }

            HorizontalDivider()
            Text("ThreadSyphon Android 1.0 · No account · Respectful polling", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LabeledSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
