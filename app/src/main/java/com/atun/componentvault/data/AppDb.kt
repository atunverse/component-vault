package com.atun.componentvault.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Part::class, Txn::class, ParamDef::class],
    version = 3,
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
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE part ADD COLUMN lcscProductId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 → v3：物料域泛化。新增 6 列，同时把 qty / lowThreshold / txn 的三个数量
         * 从 INTEGER 改成 REAL（耗材要记 3.5 米、734 克）。
         *
         * SQLite 不支持 ALTER COLUMN，所以是「建新表 → 拷数据 → 删旧表 → 改名」。
         * 这里手写的 DDL 必须与 Room 由实体推导出的结构逐字一致（列顺序、类型、
         * NOT NULL、不加 DEFAULT），否则 Room 的 validateMigration 会判定迁移失败。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `part_new` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `internalCode` TEXT NOT NULL,
                      `mpn` TEXT NOT NULL,
                      `name` TEXT NOT NULL,
                      `category` TEXT NOT NULL,
                      `domain` TEXT NOT NULL,
                      `packageName` TEXT NOT NULL,
                      `spec` TEXT NOT NULL,
                      `brand` TEXT NOT NULL,
                      `lcscCode` TEXT NOT NULL,
                      `lcscProductId` INTEGER NOT NULL,
                      `datasheetUrl` TEXT NOT NULL,
                      `paramsJson` TEXT NOT NULL,
                      `tags` TEXT NOT NULL,
                      `summary` TEXT NOT NULL,
                      `qty` REAL NOT NULL,
                      `unit` TEXT NOT NULL,
                      `lowThreshold` REAL NOT NULL,
                      `location` TEXT NOT NULL,
                      `price` REAL NOT NULL,
                      `note` TEXT NOT NULL,
                      `iconPath` TEXT NOT NULL,
                      `iconSource` TEXT NOT NULL,
                      `iconUrl` TEXT NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `part_new` (
                      `id`,`internalCode`,`mpn`,`name`,`category`,`domain`,`packageName`,
                      `spec`,`brand`,`lcscCode`,`lcscProductId`,`datasheetUrl`,`paramsJson`,
                      `tags`,`summary`,`qty`,`unit`,`lowThreshold`,`location`,`price`,`note`,
                      `iconPath`,`iconSource`,`iconUrl`,`createdAt`,`updatedAt`
                    )
                    SELECT
                      `id`,`internalCode`,`mpn`,`name`,`category`,'electronics',`packageName`,
                      '',`brand`,`lcscCode`,`lcscProductId`,`datasheetUrl`,`paramsJson`,
                      `tags`,`summary`,`qty`,'个',`lowThreshold`,`location`,`price`,`note`,
                      '','','',`createdAt`,`updatedAt`
                    FROM `part`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `part`")
                db.execSQL("ALTER TABLE `part_new` RENAME TO `part`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_part_internalCode` ON `part` (`internalCode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_part_mpn` ON `part` (`mpn`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_part_category` ON `part` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_part_packageName` ON `part` (`packageName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_part_domain` ON `part` (`domain`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `txn_new` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `partId` INTEGER NOT NULL,
                      `type` TEXT NOT NULL,
                      `delta` REAL NOT NULL,
                      `before` REAL NOT NULL,
                      `after` REAL NOT NULL,
                      `ref` TEXT NOT NULL,
                      `note` TEXT NOT NULL,
                      `idempotencyKey` TEXT NOT NULL,
                      `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `txn_new` (
                      `id`,`partId`,`type`,`delta`,`before`,`after`,`ref`,`note`,
                      `idempotencyKey`,`createdAt`
                    )
                    SELECT
                      `id`,`partId`,`type`,`delta`,`before`,`after`,`ref`,`note`,
                      `idempotencyKey`,`createdAt`
                    FROM `txn`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `txn`")
                db.execSQL("ALTER TABLE `txn_new` RENAME TO `txn`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_partId` ON `txn` (`partId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_idempotencyKey` ON `txn` (`idempotencyKey`)")
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // v0.3.0 起刻意不再挂 fallbackToDestructiveMigration()：
                // 它会在迁移校验失败时「静默删表重建」，用户看到的是「App 正常但库存空了」，
                // 比直接崩溃危险得多。宁可崩在迁移上让人看见。
                .build()
                .also { instance = it }
        }
    }
}
