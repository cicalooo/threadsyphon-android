package com.threadsyphon.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WatchedThreadEntity::class,
        WatchRuleEntity::class,
        DownloadedFileEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun threads(): ThreadDao
    abstract fun rules(): RuleDao
    abstract fun downloads(): DownloadDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "threadsyphon.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
