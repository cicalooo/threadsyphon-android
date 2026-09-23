package com.threadsyphon.android.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.threadsyphon.android.data.model.WatchStatus

@Entity(tableName = "watched_threads")
data class WatchedThreadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val board: String,
    val threadNo: Long,
    val label: String = "",
    val subject: String = "",
    val intervalSec: Int = 30,
    val mediaFilter: String = "all",
    val filenameMode: String = "original",
    val maxFileMb: Int = 0,
    val verifyMd5: Boolean = true,
    val status: String = WatchStatus.Ready.name,
    val savedCount: Int = 0,
    /** Matching media files known from last successful JSON parse. */
    val totalFiles: Int = 0,
    val lastError: String = "",
    val lastCheckedAt: Long = 0L,
    val nextCheckAt: Long = 0L,
    val autoStart: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val folderRelative: String = "",
    /** OP media tim for list thumbnail (0 = none). */
    val thumbTim: Long = 0L,
    /** Hidden from Active (auto-archive / manual). */
    val hidden: Boolean = false,
)

@Entity(tableName = "watch_rules")
data class WatchRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val board: String,
    val query: String,
    val enabled: Boolean = true,
    val intervalSec: Int = 120,
    val matchLimit: Int = 5,
    val threadInterval: Int = 0,
    val labelPrefix: String = "",
    val notify: Boolean = true,
)

@Entity(tableName = "downloaded_files")
data class DownloadedFileEntity(
    @PrimaryKey val key: String,
    val threadId: String,
    val board: String,
    val threadNo: Long,
    val tim: Long,
    val filename: String,
    val ext: String,
    val size: Long,
    val md5: String = "",
    val completedAt: Long = System.currentTimeMillis(),
)
