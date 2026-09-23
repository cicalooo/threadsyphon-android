package com.threadsyphon.android.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.threadsyphon.android.data.model.AppSettings
import com.threadsyphon.android.data.model.DownloadLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("threadsyphon_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val INTERVAL = intPreferencesKey("default_interval")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val MEDIA_FILTER = stringPreferencesKey("media_filter")
        val MAX_FILE_MB = intPreferencesKey("max_file_mb")
        val FILENAME_MODE = stringPreferencesKey("filename_mode")
        val VERIFY_MD5 = booleanPreferencesKey("verify_md5")
        val RATE_GAP = floatPreferencesKey("rate_gap")
        val CDN_GAP = floatPreferencesKey("cdn_gap")
        val ALLOW_MOBILE = booleanPreferencesKey("allow_mobile_data")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val CUSTOM_ROOT_PATH = stringPreferencesKey("custom_root_path")
        val CUSTOM_ROOT_URI = stringPreferencesKey("custom_root_uri")
        val AUTO_HIDE_FINISHED = booleanPreferencesKey("auto_hide_finished")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val FOLLOW_SYSTEM = booleanPreferencesKey("follow_system_theme")
        val SCOUT_INTERVAL = intPreferencesKey("scout_interval")
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        defaultInterval = this[Keys.INTERVAL] ?: 30,
        notifications = this[Keys.NOTIFICATIONS] ?: true,
        mediaFilter = this[Keys.MEDIA_FILTER] ?: "all",
        maxFileMb = this[Keys.MAX_FILE_MB] ?: 0,
        filenameMode = this[Keys.FILENAME_MODE] ?: "original",
        verifyMd5 = this[Keys.VERIFY_MD5] ?: true,
        rateGap = this[Keys.RATE_GAP] ?: 1.0f,
        cdnGap = this[Keys.CDN_GAP] ?: 0.25f,
        allowMobileData = this[Keys.ALLOW_MOBILE] ?: false,
        wifiOnly = this[Keys.WIFI_ONLY] ?: true,
        downloadLocation = when (this[Keys.DOWNLOAD_LOCATION]) {
            DownloadLocation.MediaStoreDownloads.name -> DownloadLocation.MediaStoreDownloads
            DownloadLocation.AppExternal.name -> DownloadLocation.AppExternal
            DownloadLocation.CustomPath.name -> DownloadLocation.CustomPath
            DownloadLocation.SharedRoot.name -> DownloadLocation.SharedRoot
            // Migrate legacy / missing → shared root (visible)
            else -> DownloadLocation.SharedRoot
        },
        customRootPath = this[Keys.CUSTOM_ROOT_PATH] ?: "",
        customRootUri = this[Keys.CUSTOM_ROOT_URI] ?: "",
        autoHideFinished = this[Keys.AUTO_HIDE_FINISHED] ?: true,
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        darkTheme = this[Keys.DARK_THEME] ?: false,
        followSystemTheme = this[Keys.FOLLOW_SYSTEM] ?: true,
        scoutIntervalSec = this[Keys.SCOUT_INTERVAL] ?: 120,
    ).normalized()

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings()).normalized()
            prefs[Keys.INTERVAL] = next.defaultInterval
            prefs[Keys.NOTIFICATIONS] = next.notifications
            prefs[Keys.MEDIA_FILTER] = next.mediaFilter
            prefs[Keys.MAX_FILE_MB] = next.maxFileMb
            prefs[Keys.FILENAME_MODE] = next.filenameMode
            prefs[Keys.VERIFY_MD5] = next.verifyMd5
            prefs[Keys.RATE_GAP] = next.rateGap
            prefs[Keys.CDN_GAP] = next.cdnGap
            prefs[Keys.ALLOW_MOBILE] = next.allowMobileData
            prefs[Keys.WIFI_ONLY] = next.wifiOnly
            prefs[Keys.DOWNLOAD_LOCATION] = next.downloadLocation.name
            prefs[Keys.CUSTOM_ROOT_PATH] = next.customRootPath
            prefs[Keys.CUSTOM_ROOT_URI] = next.customRootUri
            prefs[Keys.AUTO_HIDE_FINISHED] = next.autoHideFinished
            prefs[Keys.DYNAMIC_COLOR] = next.dynamicColor
            prefs[Keys.DARK_THEME] = next.darkTheme
            prefs[Keys.FOLLOW_SYSTEM] = next.followSystemTheme
            prefs[Keys.SCOUT_INTERVAL] = next.scoutIntervalSec
        }
    }
}
