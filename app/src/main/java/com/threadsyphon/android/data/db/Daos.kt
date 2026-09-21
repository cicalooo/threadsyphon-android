package com.threadsyphon.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadDao {
    @Query("SELECT * FROM watched_threads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WatchedThreadEntity>>

    @Query("SELECT * FROM watched_threads ORDER BY createdAt DESC")
    suspend fun getAll(): List<WatchedThreadEntity>

    @Query("SELECT * FROM watched_threads WHERE id = :id")
    fun observeById(id: String): Flow<WatchedThreadEntity?>

    @Query("SELECT * FROM watched_threads WHERE id = :id")
    suspend fun getById(id: String): WatchedThreadEntity?

    @Query("SELECT * FROM watched_threads WHERE board = :board AND threadNo = :threadNo LIMIT 1")
    suspend fun findByBoardThread(board: String, threadNo: Long): WatchedThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchedThreadEntity)

    @Update
    suspend fun update(entity: WatchedThreadEntity)

    @Query("DELETE FROM watched_threads WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE watched_threads SET status = :status WHERE id IN (:ids)")
    suspend fun setStatus(ids: List<String>, status: String)

    @Query("SELECT COUNT(*) FROM watched_threads WHERE status IN ('Watching','Downloading')")
    fun observeActiveCount(): Flow<Int>
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM watch_rules ORDER BY name ASC")
    fun observeAll(): Flow<List<WatchRuleEntity>>

    @Query("SELECT * FROM watch_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<WatchRuleEntity>

    @Query("SELECT * FROM watch_rules")
    suspend fun getAll(): List<WatchRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchRuleEntity)

    @Query("DELETE FROM watch_rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE watch_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedFileEntity)

    @Query("SELECT * FROM downloaded_files WHERE threadId = :threadId")
    suspend fun forThread(threadId: String): List<DownloadedFileEntity>

    @Query("SELECT key FROM downloaded_files WHERE threadId = :threadId")
    suspend fun keysForThread(threadId: String): List<String>

    @Query("DELETE FROM downloaded_files WHERE threadId IN (:threadIds)")
    suspend fun deleteForThreads(threadIds: List<String>)
}
