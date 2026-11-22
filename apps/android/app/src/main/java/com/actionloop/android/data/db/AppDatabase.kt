package com.actionloop.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TaskEntity::class, StepEntity::class, OutboxEntity::class, ReminderEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // 1→2：新增 reminders 表（保留已有 tasks/steps/outbox 数据，不破坏式迁移）。
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, " +
                        "`taskId` TEXT NOT NULL, `taskTitle` TEXT NOT NULL, `triggerAt` TEXT NOT NULL, " +
                        "`channel` TEXT NOT NULL, `state` TEXT NOT NULL, `dedupeKey` TEXT NOT NULL, " +
                        "`cachedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_taskId` ON `reminders` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_workspaceId` ON `reminders` (`workspaceId`)")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "actionloop.db"
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build().also { instance = it }
        }
    }
}
