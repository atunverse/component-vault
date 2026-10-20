package com.atun.componentvault.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PartDao {

    @Query("SELECT * FROM part ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Part>>

    /** 详情页用：直接订阅单条，入库/编辑后页面自动刷新，不需要手动回传 */
    @Query("SELECT * FROM part WHERE id = :id")
    fun observeById(id: Long): Flow<Part?>

    @Query("SELECT * FROM part ORDER BY updatedAt DESC")
    suspend fun all(): List<Part>

    @Query("SELECT * FROM part WHERE id = :id")
    suspend fun byId(id: Long): Part?

    @Query("SELECT * FROM part WHERE internalCode = :code LIMIT 1")
    suspend fun byInternalCode(code: String): Part?

    @Query("SELECT * FROM part WHERE mpn = :mpn OR lcscCode = :mpn LIMIT 1")
    suspend fun byMpnOrLcsc(mpn: String): Part?

    @Query("SELECT * FROM part WHERE qty > 0 AND qty <= lowThreshold ORDER BY qty ASC")
    suspend fun lowStock(): List<Part>

    @Query("SELECT COUNT(*) FROM part")
    suspend fun kindCount(): Int

    @Query("SELECT IFNULL(SUM(qty), 0) FROM part")
    suspend fun totalQty(): Double

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(part: Part): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(parts: List<Part>)

    @Update
    suspend fun update(part: Part)

    @Delete
    suspend fun delete(part: Part)

    @Query("UPDATE part SET qty = :qty, updatedAt = :ts WHERE id = :id")
    suspend fun setQty(id: Long, qty: Double, ts: Long)

    @Query("SELECT IFNULL(MAX(id), 0) FROM part")
    suspend fun maxId(): Long
}

@Dao
interface TxnDao {

    @Query("SELECT * FROM txn ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE partId = :partId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun forPart(partId: Long, limit: Int = 30): List<Txn>

    @Query("SELECT * FROM txn WHERE idempotencyKey = :key AND idempotencyKey != '' LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): Txn?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(txn: Txn): Long

    @Query("DELETE FROM txn WHERE partId = :partId")
    suspend fun deleteForPart(partId: Long)
}

@Dao
interface ParamDefDao {

    @Query("SELECT * FROM param_def ORDER BY category, `order`")
    suspend fun all(): List<ParamDef>

    @Query("SELECT * FROM param_def WHERE category = :category ORDER BY `order`")
    suspend fun forCategory(category: String): List<ParamDef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(defs: List<ParamDef>)

    @Query("SELECT COUNT(*) FROM param_def")
    suspend fun count(): Int
}
