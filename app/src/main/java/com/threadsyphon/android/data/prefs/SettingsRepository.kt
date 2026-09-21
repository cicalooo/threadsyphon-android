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
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val FOLLOW_SYSTEM = booleanPreferencesKey("follow_system_theme")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            defaultInterval = p[Keys.INTERVAL] ?: 30,
            notifications = p[Keys.NOTIFICATIONS] ?: true,
            mediaFilter = p[Keys.MEDIA_FILTER] ?: "all",
            maxFileMb = p[Keys.MAX_FILE_MB] ?: 0,
            filenameMode = p[Keys.FILENAME_MODE] ?: "original",
            verifyMd5 = p[Keys.VERIFY_MD5] ?: true,
            rateGap = p[Keys.RATE_GAP] ?: 1.0f,
            cdnGap = p[Keys.CDN_GAP] ?: 0.25f,
            allowMobileData = p[Keys.ALLOW_MOBILE] ?: false,
            downloadLocation = when (p[Keys.DOWNLOAD_LOCATION]) {
                DownloadLocation.MediaStoreDownloads.name -> DownloadLocation.MediaStoreDownloads
                else -> DownloadLocation.AppExternal
            },
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            darkTheme = p[Keys.DARK_THEME] ?: false,
            followSystemTheme = p[Keys.FOLLOW_SYSTEM] ?: true,
        ).normalized()
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = AppSettings(
                defaultInterval = prefs[Keys.INTERVAL] ?: 30,
                notifications = prefs[Keys.NOTIFICATIONS] ?: true,
                mediaFilter = prefs[Keys.MEDIA_FILTER] ?: "all",
                maxFileMb = prefs[Keys.MAX_FILE_MB] ?: 0,
                filenameMode = prefs[Keys.FILENAME_MODE] ?: "original",
                verifyMd5 = prefs[Keys.VERIFY_MD5] ?: true,
                rateGap = prefs[Keys.RATE_GAP] ?: 1.0f,
                cdnGap = prefs[Keys.CDN_GAP] ?: 0.25f,
                allowMobileData = prefs[Keys.ALLOW_MOBILE] ?: false,
                downloadLocation = when (prefs[Keys.DOWNLOAD_LOCATION]) {
                    DownloadLocation.MediaStoreDownloads.name -> DownloadLocation.MediaStoreDownloads
                    else -> DownloadLocation.AppExternal
                },
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
                darkTheme = prefs[Keys.DARK_THEME] ?: false,
                followSystemTheme = prefs[Keys.FOLLOW_SYSTEM] ?: true,
            ).normalized()
            val next = transform(current).normalized()
            prefs[Keys.INTERVAL] = next.defaultInterval
            prefs[Keys.NOTIFICATIONS] = next.notifications
            prefs[Keys.MEDIA_FILTER] = next.mediaFilter
            prefs[Keys.MAX_FILE_MB] = next.maxFileMb
            prefs[Keys.FILENAME_MODE] = next.filenameMode
            prefs[Keys.VERIFY_MD5] = next.verifyMd5
            prefs[Keys.RATE_GAP] = next.rateGap
            prefs[Keys.CDN_GAP] = next.cdnGap
            prefs[Keys.ALLOW_MOBILE] = next.allowMobileData
            prefs[Keys.DOWNLOAD_LOCATION] = next.downloadLocation.name
            prefs[Keys.DYNAMIC_COLOR] = next.dynamicColor
            prefs[Keys.DARK_THEME] = next.darkTheme
            prefs[Keys.FOLLOW_SYSTEM] = next.followSystemTheme
        }
    }
}
