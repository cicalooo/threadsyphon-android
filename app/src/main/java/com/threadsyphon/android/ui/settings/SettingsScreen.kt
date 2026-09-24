package com.threadsyphon.android.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
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
import com.threadsyphon.android.service.WatchService
import com.threadsyphon.android.util.BatteryHelper
import com.threadsyphon.android.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: WatchRepository) {
    val settings by repository.settings().collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var ignoringBattery by remember { mutableStateOf(BatteryHelper.isIgnoringBatteryOptimizations(context)) }
    var intervalText by remember(settings.defaultInterval) { mutableStateOf(settings.defaultInterval.toString()) }
    var maxMbText by remember(settings.maxFileMb) { mutableStateOf(settings.maxFileMb.toString()) }
    var allFiles by remember { mutableStateOf(StorageHelper.hasAllFilesAccess()) }

    fun update(block: (AppSettings) -> AppSettings) {
        scope.launch { repository.updateSettings(block) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        val path = StorageHelper.pathFromTreeUri(uri)
        update {
            it.copy(
                downloadLocation = DownloadLocation.CustomPath,
                customRootUri = uri.toString(),
                customRootPath = path ?: it.customRootPath,
            )
        }
        Toast.makeText(
            context,
            if (path != null) "Custom root: $path" else "Custom tree saved (URI)",
            Toast.LENGTH_SHORT,
        ).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = repository.exportWatchListJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                Toast.makeText(context, "Watch list exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                    } ?: throw IllegalStateException("Could not read file")
                }
                val (threads, rules) = repository.importWatchListJson(text)
                WatchService.start(context)
                Toast.makeText(
                    context,
                    "Imported $threads thread(s), $rules rule(s)",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    val shareExport = {
        scope.launch {
            try {
                val json = repository.exportWatchListJson()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, "threadsyphon-watchlist.json")
                    putExtra(Intent.EXTRA_TEXT, json)
                }
                context.startActivity(Intent.createChooser(send, "Export watch list"))
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Watching", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            LabeledSwitch("Event notifications", settings.notifications) { v ->
                update { it.copy(notifications = v) }
            }
            LabeledSwitch("Auto-hide finished from Active", settings.autoHideFinished) { v ->
                update { it.copy(autoHideFinished = v) }
            }
            Text(
                "When on, completed / 404 threads leave Active (see Finished filter).",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                intervalText,
                { intervalText = it.filter(Char::isDigit).take(5) },
                label = { Text("Default check interval (seconds)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        intervalText.toIntOrNull()?.let { v ->
                            update { it.copy(defaultInterval = v.coerceIn(15, 3600)) }
                        }
                    }) { Text("Apply") }
                },
            )

            HorizontalDivider()
            Text("Media", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Filter (default for new watches)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Constants.MEDIA_FILTERS.forEach { mode ->
                    FilterChip(
                        selected = settings.mediaFilter == mode,
                        onClick = { update { it.copy(mediaFilter = mode) } },
                        label = { Text(Constants.MEDIA_FILTER_LABELS[mode] ?: mode) },
                    )
                }
            }
            Text("Filename mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Constants.FILENAME_MODES.forEach { mode ->
                    FilterChip(
                        selected = settings.filenameMode == mode,
                        onClick = { update { it.copy(filenameMode = mode) } },
                        label = { Text(mode) },
                    )
                }
            }
            LabeledSwitch("Verify MD5 when provided", settings.verifyMd5) { v ->
                update { it.copy(verifyMd5 = v) }
            }
            OutlinedTextField(
                maxMbText,
                { maxMbText = it.filter(Char::isDigit).take(5) },
                label = { Text("Max file size MB (0 = unlimited)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        maxMbText.toIntOrNull()?.let { v ->
                            update { it.copy(maxFileMb = v.coerceAtLeast(0)) }
                        }
                    }) { Text("Apply") }
                },
            )

            HorizontalDivider()
            Text("Storage", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Default saves to visible shared storage: ${StorageHelper.sharedRootHint()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Text(
                    if (allFiles) "All-files access: granted"
                    else "All-files access: needed for Internal storage/threadsyphon",
                    style = MaterialTheme.typography.labelLarge,
                )
                Button(onClick = {
                    context.startActivity(StorageHelper.allFilesAccessIntent(context))
                    allFiles = StorageHelper.hasAllFilesAccess()
                }) { Text(if (allFiles) "All-files settings" else "Grant all-files access") }
            }
            Text("Save location", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = settings.downloadLocation == DownloadLocation.SharedRoot,
                    onClick = {
                        update { it.copy(downloadLocation = DownloadLocation.SharedRoot) }
                        allFiles = StorageHelper.hasAllFilesAccess()
                        if (!allFiles) {
                            try {
                                context.startActivity(StorageHelper.allFilesAccessIntent(context))
                            } catch (_: Exception) {
                            }
                            Toast.makeText(
                                context,
                                "Grant All files access so Internal storage/threadsyphon works",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    label = { Text("Internal storage/threadsyphon") },
                )
                FilterChip(
                    selected = settings.downloadLocation == DownloadLocation.CustomPath,
                    onClick = { folderPicker.launch(null) },
                    label = { Text("Custom folder…") },
                )
                FilterChip(
                    selected = settings.downloadLocation == DownloadLocation.MediaStoreDownloads,
                    onClick = { update { it.copy(downloadLocation = DownloadLocation.MediaStoreDownloads) } },
                    label = { Text("Downloads (MediaStore)") },
                )
                FilterChip(
                    selected = settings.downloadLocation == DownloadLocation.AppExternal,
                    onClick = { update { it.copy(downloadLocation = DownloadLocation.AppExternal) } },
                    label = { Text("App-private (not recommended)") },
                )
            }
            Text(
                when (settings.downloadLocation) {
                    DownloadLocation.SharedRoot -> StorageHelper.sharedRoot().absolutePath
                    DownloadLocation.CustomPath -> settings.customRootPath.ifBlank { settings.customRootUri }
                    DownloadLocation.AppExternal -> StorageHelper.appExternalRoot(context).absolutePath
                    DownloadLocation.MediaStoreDownloads -> StorageHelper.publicDownloadsHint()
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (settings.downloadLocation == DownloadLocation.CustomPath) {
                OutlinedButton(onClick = { folderPicker.launch(null) }) {
                    Text("Pick custom folder")
                }
            }

            HorizontalDivider()
            Text("Watch list", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("threadsyphon-watchlist.json") }) {
                    Text("Export JSON")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                    Text("Import JSON")
                }
            }
            TextButton(onClick = { shareExport() }) { Text("Share watch list…") }

            HorizontalDivider()
            Text("Network", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Wi‑Fi only by default. Allow mobile data only if you accept metered usage.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LabeledSwitch("Allow mobile data", settings.allowMobileData) { v ->
                update { it.copy(allowMobileData = v, wifiOnly = !v) }
            }
            Text(
                "API gap ${settings.rateGap}s · CDN gap ${settings.cdnGap}s",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("Battery / keep-alive", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "threadsyphon is always-watching. Grant unrestricted battery and (on Samsung) add it to Never sleeping apps so the watcher notification stays up overnight.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (ignoringBattery) "Battery status: unrestricted" else "Battery status: optimized (OS may kill watcher)",
                style = MaterialTheme.typography.labelLarge,
            )
            Button(onClick = {
                try {
                    BatteryHelper.requestIgnoreBatteryOptimizations(context)
                } catch (_: Exception) {
                    BatteryHelper.openBatterySettings(context)
                }
                ignoringBattery = BatteryHelper.isIgnoringBatteryOptimizations(context)
                Toast.makeText(context, "Confirm unrestricted, then return", Toast.LENGTH_SHORT).show()
            }) { Text("Ignore battery optimizations") }
            OutlinedButton(onClick = {
                val oem = BatteryHelper.openOemBackgroundAllowlist(context)
                Toast.makeText(
                    context,
                    if (oem) "Open Never sleeping / background allow list and add threadsyphon"
                    else "Opened app info — set battery to Unrestricted",
                    Toast.LENGTH_LONG,
                ).show()
            }) { Text("Samsung / OEM background settings") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val exactOk = BatteryHelper.canScheduleExactAlarms(context)
                Text(
                    if (exactOk) "Exact alarms: allowed (keep-alive restart OK)"
                    else "Exact alarms: not allowed (heartbeat may be delayed)",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!exactOk) {
                    OutlinedButton(onClick = {
                        BatteryHelper.openExactAlarmSettings(context)
                    }) { Text("Allow exact alarms") }
                }
            }
            TextButton(onClick = { BatteryHelper.openAppDetails(context) }) {
                Text("Open app info")
            }

            HorizontalDivider()
            Text("Appearance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            LabeledSwitch("Follow system dark mode", settings.followSystemTheme) { v ->
                update { it.copy(followSystemTheme = v) }
            }
            if (!settings.followSystemTheme) {
                LabeledSwitch("Dark theme", settings.darkTheme) { v ->
                    update { it.copy(darkTheme = v) }
                }
            }
            LabeledSwitch("Dynamic color (Material You)", settings.dynamicColor) { v ->
                update { it.copy(dynamicColor = v) }
            }

            HorizontalDivider()
            Text(
                "threadsyphon Android 1.0.8 · Always watching · No account",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
