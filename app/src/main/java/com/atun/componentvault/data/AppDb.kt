package com.atun.componentvault.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Part::class, Txn::class, ParamDef::class],
    version = 2,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {

    abstract fun partDao(): PartDao
    abstract fun txnDao(): TxnDao
    abstract fun paramDao(): ParamDefDao

    companion object {
        /**
         * v1 → v2：新增 part.lcscProductId。
         *
         * 必须显式给迁移而不是直接靠 fallbackToDestructiveMigration ——
         * 用户可能已经在 v0.1.0 里录了库存，重建表等于把数据删了。
         * 兜底的 destructive 迁移保留，只在极端情况下生效。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE part ADD COLUMN lcscProductId INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "component_vault.db"
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
