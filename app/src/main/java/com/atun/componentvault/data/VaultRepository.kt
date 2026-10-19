package com.atun.componentvault.data

import kotlinx.coroutines.flow.Flow

/**
 * 库存业务仓库。所有改数量的动作都必须走 [moveStock]，
 * 保证「改数量」和「写流水」在同一个事务语义里，不出现只改数不留痕的情况。
 */
class VaultRepository(private val db: AppDb) {

    private val partDao = db.partDao()
    private val txnDao = db.txnDao()
    private val paramDao = db.paramDao()

    data class StockResult(val ok: Boolean, val qty: Int, val message: String)

    fun observeParts(): Flow<List<Part>> = partDao.observeAll()

    fun observeTxns(): Flow<List<Txn>> = txnDao.observeRecent(200)

    suspend fun allParts(): List<Part> = partDao.all()

    suspend fun part(id: Long): Part? = partDao.byId(id)

    suspend fun txnsOf(partId: Long): List<Txn> = txnDao.forPart(partId)

    suspend fun stats(): StockStats {
        val all = partDao.all()
        return StockStats(
            totalKinds = all.size,
            totalQty = all.sumOf { it.qty.toLong() },
            lowKinds = all.count { it.isLow },
            outKinds = all.count { it.isOut }
        )
    }

    /** 生成下一个内部编号，循环检查避免删除后复用导致冲突 */
    suspend fun nextInternalCode(): String {
        var n = partDao.maxId() + 1
        var code = formatCode(n)
        while (partDao.byInternalCode(code) != null) {
            n++
            code = formatCode(n)
        }
        return code
    }

    private fun formatCode(n: Long): String = "CV-" + n.toString().padStart(6, '0')

    suspend fun create(part: Part): Long {
        val code = part.internalCode.ifBlank { nextInternalCode() }
        val now = System.currentTimeMillis()
        val id = partDao.insert(part.copy(internalCode = code, createdAt = now, updatedAt = now))
        if (part.qty != 0) {
            txnDao.insert(
                Txn(
                    partId = id,
                    type = "CREATE",
                    delta = part.qty,
                    before = 0,
                    after = part.qty,
                    note = "建档初始库存"
                )
            )
        }
        return id
    }

    suspend fun update(part: Part) {
        partDao.update(part.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(part: Part) {
        partDao.delete(part)
    }

    /**
     * 库存变动。delta 为增量，负数为出库。
     * idempotencyKey 非空时做重放保护 —— 局域网多端同时提交不会重复扣减。
     */
    suspend fun moveStock(
        partId: Long,
        type: String,
        delta: Int,
        ref: String = "",
        note: String = "",
        idempotencyKey: String = ""
    ): StockResult {
        if (idempotencyKey.isNotBlank()) {
            val exist = txnDao.findByIdempotencyKey(idempotencyKey)
            if (exist != null) {
                val p = partDao.byId(partId)
                return StockResult(true, p?.qty ?: 0, "重复请求已忽略")
            }
        }
        val p = partDao.byId(partId) ?: return StockResult(false, 0, "器件不存在")
        val after = p.qty + delta
        if (after < 0) return StockResult(false, p.qty, "库存不足，当前 ${p.qty}")
        partDao.setQty(partId, after, System.currentTimeMillis())
        txnDao.insert(
            Txn(
                partId = partId,
                type = type,
                delta = delta,
                before = p.qty,
                after = after,
                ref = ref,
                note = note,
                idempotencyKey = idempotencyKey
            )
        )
        return StockResult(true, after, "已更新为 $after")
    }

    /** 首次启动灌入示例数据，避免打开就是空白页 */
    suspend fun seedIfEmpty() {
        if (partDao.kindCount() > 0) return
        val demo = listOf(
            Part(
                internalCode = "", mpn = "10kΩ", name = "贴片电阻", category = "电阻",
                packageName = "0603 (1608)", brand = "厚声",
                paramsJson = """{"阻值":"10kΩ","精度":"±1%","功率":"1/10W"}""",
                tags = "上拉,限流,分压", summary = "10kΩ 贴片电阻，0603 封装，常用于上拉、分压",
                qty = 420, lowThreshold = 100, location = "A-01-3", price = 0.01
            ),
            Part(
                internalCode = "", mpn = "100nF", name = "贴片电容", category = "电容",
                packageName = "0603 (1608)", brand = "三星",
                paramsJson = """{"容值":"100nF","耐压":"50V","介质":"X7R"}""",
                tags = "去耦,滤波,旁路", summary = "100nF 贴片电容，0603 封装，用于电源去耦与滤波",
                qty = 260, lowThreshold = 100, location = "A-02-1", price = 0.02
            ),
            Part(
                internalCode = "", mpn = "4.7µH", name = "功率电感", category = "电感",
                packageName = "0603 (1608)",
                paramsJson = """{"电感量":"4.7µH","电流":"1.2A","类型":"功率电感"}""",
                tags = "储能,滤波,DCDC", summary = "4.7µH 功率电感，0603 封装，用于开关电源储能",
                qty = 35, lowThreshold = 50, location = "A-03-2", price = 0.12
            ),
            Part(
                internalCode = "", mpn = "AMS1117-3.3", name = "低压差线性稳压器", category = "电源管理",
                packageName = "SOT-223", brand = "AMS",
                paramsJson = """{"类型":"LDO","输出电压":"3.3V","输出电流":"1A","压差":"1.3V"}""",
                tags = "稳压,LDO,3.3V", summary = "LDO 3.3V 1A，SOT-223，常作为 5V 转 3.3V 主稳压",
                qty = 86, lowThreshold = 20, location = "B-01-1", price = 0.35
            ),
            Part(
                internalCode = "", mpn = "STM32F103C8T6", name = "ARM Cortex-M3 微控制器", category = "微控制器",
                packageName = "LQFP-48", brand = "ST",
                paramsJson = """{"内核":"Cortex-M3","主频":"72MHz","Flash":"64KB","RAM":"20KB"}""",
                tags = "MCU,最小系统,通用控制", summary = "Cortex-M3 72MHz 64KB Flash，LQFP-48，通用控制主力型号",
                qty = 12, lowThreshold = 5, location = "B-02-2", price = 8.60
            ),
            Part(
                internalCode = "", mpn = "SS34", name = "肖特基二极管", category = "二极管",
                packageName = "SMA (DO-214AC)",
                paramsJson = """{"类型":"肖特基","反向耐压":"40V","正向电流":"3A"}""",
                tags = "整流,防反接,续流", summary = "肖特基 40V 3A，SMA 封装，用于防反接与续流",
                qty = 0, lowThreshold = 30, location = "C-01-1", price = 0.14
            ),
            Part(
                internalCode = "", mpn = "AO3400", name = "N沟道MOSFET", category = "晶体管",
                packageName = "SOT-23", brand = "AOS",
                paramsJson = """{"沟道":"N","Vds":"30V","Id":"5.7A"}""",
                tags = "开关,驱动,电平转换", summary = "N 沟道 MOSFET 30V 5.7A，SOT-23，小电流开关首选",
                qty = 240, lowThreshold = 50, location = "C-02-3", price = 0.09
            ),
            Part(
                internalCode = "", mpn = "CH340C", name = "USB转串口芯片", category = "接口芯片",
                packageName = "SOP-16", brand = "沁恒",
                paramsJson = """{"功能":"USB-UART","供电电压":"3.3/5V","内置晶振":"是"}""",
                tags = "USB,串口,下载", summary = "USB 转串口，SOP-16，内置晶振，常用于下载与调试",
                qty = 18, lowThreshold = 20, location = "B-03-1", price = 1.85
            )
        )
        demo.forEach { create(it) }
        seedParamDefs()
    }

    private suspend fun seedParamDefs() {
        if (paramDao.count() > 0) return
        paramDao.upsertAll(
            listOf(
                ParamDef("电阻", "阻值", "阻值", "Ω", "text", 1),
                ParamDef("电阻", "精度", "精度", "", "text", 2),
                ParamDef("电阻", "功率", "额定功率", "W", "text", 3),
                ParamDef("电容", "容值", "容值", "F", "text", 1),
                ParamDef("电容", "耐压", "耐压", "V", "text", 2),
                ParamDef("电容", "介质", "介质", "", "text", 3),
                ParamDef("电感", "电感量", "电感量", "H", "text", 1),
                ParamDef("电感", "电流", "额定电流", "A", "text", 2),
                ParamDef("二极管", "类型", "类型", "", "text", 1),
                ParamDef("二极管", "反向耐压", "反向耐压", "V", "text", 2),
                ParamDef("二极管", "正向电流", "正向电流", "A", "text", 3),
                ParamDef("晶体管", "沟道", "沟道/极性", "", "text", 1),
                ParamDef("晶体管", "Vds", "耐压", "V", "text", 2),
                ParamDef("晶体管", "Id", "电流", "A", "text", 3),
                ParamDef("电源管理", "输出电压", "输出电压", "V", "text", 1),
                ParamDef("电源管理", "输出电流", "输出电流", "A", "text", 2),
                ParamDef("微控制器", "内核", "内核", "", "text", 1),
                ParamDef("微控制器", "主频", "主频", "Hz", "text", 2),
                ParamDef("微控制器", "Flash", "Flash", "", "text", 3)
            )
        )
    }
}
