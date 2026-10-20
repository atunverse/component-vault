package com.atun.componentvault.data

import android.content.Context
import com.atun.componentvault.enrich.LcscLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * 库存业务仓库。所有改数量的动作都必须走 [moveStock]，
 * 保证「改数量」和「写流水」在同一个事务语义里，不出现只改数不留痕的情况。
 */
class VaultRepository(private val db: AppDb, private val appContext: Context) {

    private val partDao = db.partDao()
    private val txnDao = db.txnDao()
    private val paramDao = db.paramDao()

    /** 图标下载/回填用的后台作用域，生命周期与应用一致 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class StockResult(val ok: Boolean, val qty: Double, val message: String)

    data class BackfillProgress(
        val done: Int = 0,
        val total: Int = 0,
        val ok: Int = 0,
        val running: Boolean = false
    )

    fun observeParts(): Flow<List<Part>> = partDao.observeAll()

    fun observePart(id: Long): Flow<Part?> = partDao.observeById(id)

    fun observeTxns(): Flow<List<Txn>> = txnDao.observeRecent(200)

    suspend fun allParts(): List<Part> = partDao.all()

    suspend fun part(id: Long): Part? = partDao.byId(id)

    suspend fun txnsOf(partId: Long, limit: Int = 30): List<Txn> = txnDao.forPart(partId, limit)

    suspend fun stats(): StockStats {
        val all = partDao.all()
        return StockStats(
            totalKinds = all.size,
            totalQty = all.sumOf { it.qty }.roundToLong(),
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
        if (part.qty != 0.0) {
            txnDao.insert(
                Txn(
                    partId = id,
                    type = "CREATE",
                    delta = part.qty,
                    before = 0.0,
                    after = part.qty,
                    note = "建档初始库存"
                )
            )
        }
        // 图标是增强项：后台异步抓，失败不影响入库，也不给用户转圈
        if (part.iconPath.isBlank() && (part.iconUrl.isNotBlank() || part.lcscProductId > 0)) {
            scope.launch { fetchIcon(id) }
        }
        return id
    }

    /**
     * 修改资料。**刻意不是整行覆盖**。
     *
     * 编辑页打开时抓的是一份快照，这期间用户可能在电脑端通过局域网出库了。
     * 如果直接 `@Update(edited)`，qty 会被写回快照里的旧值 —— 数量凭空回滚，
     * 而且没有任何流水，这是这套「手机录入 + 电脑管理」架构下最容易出的账目事故。
     *
     * 因此：数量、内部编号、创建时间、图标字段一律以库中最新值为准。
     */
    suspend fun updateEditable(edited: Part): Boolean {
        val fresh = partDao.byId(edited.id) ?: return false
        partDao.update(
            edited.copy(
                qty = fresh.qty,
                internalCode = fresh.internalCode,
                createdAt = fresh.createdAt,
                iconPath = fresh.iconPath,
                iconSource = fresh.iconSource,
                iconUrl = fresh.iconUrl,
                updatedAt = System.currentTimeMillis()
            )
        )
        val note = diffNote(fresh, edited)
        txnDao.insert(
            Txn(
                partId = edited.id,
                type = "EDIT",
                delta = 0.0,
                before = fresh.qty,
                after = fresh.qty,
                note = note
            )
        )
        return true
    }

    /** 图标单独走这个入口，避免与资料编辑混在一起 */
    suspend fun updateIcon(partId: Long, fileName: String, source: String, url: String) {
        val p = partDao.byId(partId) ?: return
        // 换图时删掉旧文件，避免孤儿
        if (p.iconPath.isNotBlank() && p.iconPath != fileName) IconStore.delete(appContext, p.iconPath)
        partDao.update(
            p.copy(
                iconPath = fileName,
                iconSource = source,
                iconUrl = if (url.isNotBlank()) url else p.iconUrl,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 抓一次图标（远程 URL 优先，其次按立创号回查）。
     * 返回是否成功。失败静默 —— 列表会退回封装文字占位。
     */
    suspend fun fetchIcon(partId: Long): Boolean {
        val p = partDao.byId(partId) ?: return false
        val url = when {
            p.iconUrl.isNotBlank() -> p.iconUrl
            else -> resolveImageUrl(p)
        }
        if (url.isBlank()) return false
        if (!IconStore.fromUrl(appContext, partId, url)) return false
        partDao.update(
            p.copy(
                iconPath = IconStore.nameFor(partId),
                iconSource = "lcsc",
                iconUrl = url,
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    /** 只有立创号没有图 URL 时，回查一次检索接口 */
    private suspend fun resolveImageUrl(p: Part): String {
        val key = when {
            p.lcscProductId > 0 -> p.lcscProductId.toString()
            p.lcscCode.isNotBlank() -> p.lcscCode
            else -> return ""
        }
        val hit = com.atun.componentvault.enrich.LcscResolver.resolve(key)
        val hits = (hit as? com.atun.componentvault.enrich.LcscResolver.Result.Hits)?.hits
            ?: return ""
        return hits.firstOrNull { it.imageUrl.isNotBlank() }?.imageUrl.orEmpty()
    }

    /**
     * 存量器件批量补图。串行 + 间隔，避免把立创图床打成压力测试。
     * onProgress 会在每个器件处理完后回调一次，用于界面显示进度。
     */
    suspend fun backfillIcons(
        onProgress: (BackfillProgress) -> Unit
    ): BackfillProgress {
        val targets = partDao.all().filter { !it.hasIcon }
        var done = 0
        var ok = 0
        onProgress(BackfillProgress(0, targets.size, 0, true))
        for (p in targets) {
            if (fetchIcon(p.id)) ok++
            done++
            onProgress(BackfillProgress(done, targets.size, ok, true))
            kotlinx.coroutines.delay(280)
        }
        val final = BackfillProgress(done, targets.size, ok, false)
        onProgress(final)
        return final
    }

    /** 删除器件：连带流水与图标文件一起清 */
    suspend fun deleteWithIcon(part: Part) {
        IconStore.delete(appContext, part.iconPath)
        txnDao.deleteForPart(part.id)
        partDao.delete(part)
    }

    /** 清理 DB 里已无引用的图标文件 */
    suspend fun purgeOrphanIcons(): Int =
        IconStore.purgeOrphans(appContext, partDao.all().map { it.iconPath }.filter { it.isNotBlank() }.toSet())

    /** 已有图标的器件数 */
    suspend fun countIcons(): Int = partDao.all().count { it.hasIcon }

    /** 有没有图标缺失的器件（决定是否显示「补图」按钮） */
    suspend fun missingIconCount(): Int = partDao.all().count { !it.hasIcon }

    /** 立创商品页地址（有数字 id 走精确页，只有立创号退化为搜索页） */
    fun productUrl(p: Part): String = LcscLink.best(p.lcscProductId, p.lcscCode)

    suspend fun moveStock(
        partId: Long,
        type: String,
        delta: Double,
        ref: String = "",
        note: String = "",
        idempotencyKey: String = ""
    ): StockResult {
        if (idempotencyKey.isNotBlank()) {
            val exist = txnDao.findByIdempotencyKey(idempotencyKey)
            if (exist != null) {
                val p = partDao.byId(partId)
                return StockResult(true, p?.qty ?: 0.0, "重复请求已忽略")
            }
        }
        val p = partDao.byId(partId) ?: return StockResult(false, 0.0, "器件不存在")
        val after = p.qty + delta
        if (after < 0) return StockResult(false, p.qty, "库存不足，当前 ${fmtQty(p.qty)}${p.unit}")
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
        return StockResult(true, after, "已更新为 ${fmtQty(after)}${p.unit}")
    }

    /** 首次启动灌入示例数据，避免打开就是空白页 */
    suspend fun seedIfEmpty() {
        // 参数模板先补齐：老用户升级上来时 part 表非空，但五金/耗材的分类模板还没有，
        // 所以这一步必须放在「有没有数据」的判断之前，且自身幂等。
        seedParamDefs()
        if (partDao.kindCount() > 0) return
        val demo = listOf(
            Part(
                internalCode = "", mpn = "10kΩ", name = "贴片电阻", category = "电阻",
                packageName = "0603 (1608)", brand = "厚声",
                paramsJson = """{"阻值":"10kΩ","精度":"±1%","功率":"1/10W"}""",
                tags = "上拉,限流,分压", summary = "10kΩ 贴片电阻，0603 封装，常用于上拉、分压",
                qty = 420.0, lowThreshold = 100.0, location = "A-01-3", price = 0.01
            ),
            Part(
                internalCode = "", mpn = "100nF", name = "贴片电容", category = "电容",
                packageName = "0603 (1608)", brand = "三星",
                paramsJson = """{"容值":"100nF","耐压":"50V","介质":"X7R"}""",
                tags = "去耦,滤波,旁路", summary = "100nF 贴片电容，0603 封装，用于电源去耦与滤波",
                qty = 260.0, lowThreshold = 100.0, location = "A-02-1", price = 0.02
            ),
            Part(
                internalCode = "", mpn = "AMS1117-3.3", name = "低压差线性稳压器", category = "电源管理",
                packageName = "SOT-223", brand = "AMS",
                paramsJson = """{"类型":"LDO","输出电压":"3.3V","输出电流":"1A","压差":"1.3V"}""",
                tags = "稳压,LDO,3.3V", summary = "LDO 3.3V 1A，SOT-223，常作为 5V 转 3.3V 主稳压",
                qty = 86.0, lowThreshold = 20.0, location = "B-01-1", price = 0.35
            ),
            Part(
                internalCode = "", mpn = "STM32F103C8T6", name = "ARM Cortex-M3 微控制器", category = "微控制器",
                packageName = "LQFP-48", brand = "ST",
                paramsJson = """{"内核":"Cortex-M3","主频":"72MHz","Flash":"64KB","RAM":"20KB"}""",
                tags = "MCU,最小系统,通用控制", summary = "Cortex-M3 72MHz 64KB Flash，LQFP-48，通用控制主力型号",
                qty = 12.0, lowThreshold = 5.0, location = "B-02-2", price = 8.60
            ),
            Part(
                internalCode = "", mpn = "AO3400", name = "N沟道MOSFET", category = "晶体管",
                packageName = "SOT-23", brand = "AOS",
                paramsJson = """{"沟道":"N","Vds":"30V","Id":"5.7A"}""",
                tags = "开关,驱动,电平转换", summary = "N 沟道 MOSFET 30V 5.7A，SOT-23，小电流开关首选",
                qty = 240.0, lowThreshold = 50.0, location = "C-02-3", price = 0.09
            ),
            // ---- 五金示例：让新品类一打开就能看见
            Part(
                internalCode = "", mpn = "DIN912 M3×8", name = "内六角圆柱头螺钉", category = "螺钉",
                domain = Domains.HARDWARE, packageName = "M3×8", spec = "螺纹 M3 · 长 8mm · 内六角圆柱头 · 304 不锈钢",
                brand = "AOFENG(奥峰)",
                paramsJson = """{"螺纹":"M3","长度":"8mm","头型":"内六角圆柱头","标准":"DIN912","材质":"304 不锈钢"}""",
                tags = "打印机,VORON,结构件", summary = "DIN912 M3×8 内六角圆柱头螺钉，304 不锈钢",
                qty = 180.0, unit = "个", lowThreshold = 40.0, location = "五金柜 A-1", price = 0.06
            ),
            Part(
                internalCode = "", mpn = "M3 六角螺母", name = "六角螺母", category = "螺母",
                domain = Domains.HARDWARE, packageName = "M3", spec = "螺纹 M3 · 对边 5.5mm · 304 不锈钢",
                paramsJson = """{"螺纹":"M3","对边":"5.5mm","标准":"GB6170","材质":"304 不锈钢"}""",
                tags = "紧固,结构件", summary = "M3 六角螺母，304 不锈钢",
                qty = 22.0, unit = "个", lowThreshold = 50.0, location = "五金柜 A-2", price = 0.03
            ),
            Part(
                internalCode = "", mpn = "M3 平垫", name = "平垫圈", category = "垫圈",
                domain = Domains.HARDWARE, packageName = "M3", spec = "内径 3.2mm · 外径 7mm · 厚 0.5mm",
                paramsJson = """{"内径":"3.2mm","外径":"7mm","厚度":"0.5mm","标准":"GB97"}""",
                tags = "紧固", summary = "M3 平垫圈，304 不锈钢",
                qty = 300.0, unit = "个", lowThreshold = 50.0, location = "五金柜 A-3", price = 0.01
            ),
            Part(
                internalCode = "", mpn = "内六角扳手 1.5-6mm", name = "内六角扳手组", category = "扳手",
                domain = Domains.TOOL, packageName = "9 件套", spec = "1.5 / 2 / 2.5 / 3 / 4 / 5 / 6mm",
                paramsJson = """{"规格":"1.5-6mm","件数":"9","材质":"S2 钢"}""",
                tags = "工具,内六角", summary = "内六角扳手 9 件套，S2 钢",
                qty = 1.0, unit = "套", lowThreshold = 0.0, location = "工具柜 第 2 抽", price = 28.0
            ),
            // ---- 3D 打印耗材示例
            Part(
                internalCode = "", mpn = "PLA 1.75 黑", name = "PLA 线材", category = "PLA",
                domain = Domains.FILAMENT, packageName = "1.75mm", spec = "直径 1.75mm · 净重 1kg · 黑色",
                brand = "eSUN",
                paramsJson = """{"材质":"PLA","直径":"1.75mm","颜色":"黑色","净重":"1000g"}""",
                tags = "VORON,外壳件,易打印", summary = "PLA 1.75mm 黑色，1kg 盘装，易打印适合外观件",
                qty = 734.0, unit = "g", lowThreshold = 200.0, location = "耗材架 第 1 层", price = 0.09
            ),
            Part(
                internalCode = "", mpn = "PETG 1.75 透明", name = "PETG 线材", category = "PETG",
                domain = Domains.FILAMENT, packageName = "1.75mm", spec = "直径 1.75mm · 净重 1kg · 透明",
                brand = "Polymaker",
                paramsJson = """{"材质":"PETG","直径":"1.75mm","颜色":"透明","净重":"1000g"}""",
                tags = "VORON,结构件,耐温", summary = "PETG 1.75mm 透明，1kg 盘装，韧性与耐温优于 PLA",
                qty = 120.0, unit = "g", lowThreshold = 250.0, location = "耗材架 第 2 层", price = 0.12
            )
        )
        demo.forEach { create(it) }
    }

    /** 幂等：ParamDef 主键是 (category, key)，重复 upsert 只会刷新标签，不会产生重复行 */
    private suspend fun seedParamDefs() {
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
                ParamDef("微控制器", "Flash", "Flash", "", "text", 3),
                // ---- 五金
                ParamDef("螺钉", "螺纹", "螺纹", "", "text", 1),
                ParamDef("螺钉", "长度", "长度", "mm", "text", 2),
                ParamDef("螺钉", "头型", "头型", "", "text", 3),
                ParamDef("螺钉", "标准", "执行标准", "", "text", 4),
                ParamDef("螺钉", "材质", "材质/强度", "", "text", 5),
                ParamDef("螺母", "螺纹", "螺纹", "", "text", 1),
                ParamDef("螺母", "对边", "对边尺寸", "mm", "text", 2),
                ParamDef("螺母", "标准", "执行标准", "", "text", 3),
                ParamDef("螺母", "材质", "材质/强度", "", "text", 4),
                ParamDef("垫圈", "内径", "内径", "mm", "text", 1),
                ParamDef("垫圈", "外径", "外径", "mm", "text", 2),
                ParamDef("垫圈", "厚度", "厚度", "mm", "text", 3),
                ParamDef("垫圈", "标准", "执行标准", "", "text", 4),
                ParamDef("铜柱", "螺纹", "螺纹", "", "text", 1),
                ParamDef("铜柱", "规格", "规格（柱高+螺纹长）", "mm", "text", 2),
                ParamDef("轴承", "内径", "内径", "mm", "text", 1),
                ParamDef("轴承", "外径", "外径", "mm", "text", 2),
                ParamDef("轴承", "厚度", "厚度", "mm", "text", 3),
                // ---- 工具
                ParamDef("扳手", "规格", "规格范围", "mm", "text", 1),
                ParamDef("扳手", "件数", "件数", "", "text", 2),
                ParamDef("扳手", "材质", "材质", "", "text", 3),
                ParamDef("螺丝刀", "规格", "规格", "", "text", 1),
                ParamDef("螺丝刀", "头型", "头型", "", "text", 2),
                ParamDef("量具", "量程", "量程", "mm", "text", 1),
                ParamDef("量具", "分辨率", "分辨率", "mm", "text", 2),
                // ---- 3D 耗材
                ParamDef("PLA", "材质", "材质", "", "text", 1),
                ParamDef("PLA", "直径", "线径", "mm", "text", 2),
                ParamDef("PLA", "颜色", "颜色", "", "text", 3),
                ParamDef("PLA", "净重", "净重", "g", "text", 4),
                ParamDef("PETG", "材质", "材质", "", "text", 1),
                ParamDef("PETG", "直径", "线径", "mm", "text", 2),
                ParamDef("PETG", "颜色", "颜色", "", "text", 3),
                ParamDef("PETG", "净重", "净重", "g", "text", 4),
                ParamDef("ABS", "材质", "材质", "", "text", 1),
                ParamDef("ABS", "直径", "线径", "mm", "text", 2),
                ParamDef("ABS", "颜色", "颜色", "", "text", 3),
                ParamDef("ABS", "净重", "净重", "g", "text", 4),
                ParamDef("TPU", "材质", "材质", "", "text", 1),
                ParamDef("TPU", "直径", "线径", "mm", "text", 2),
                ParamDef("TPU", "颜色", "颜色", "", "text", 3),
                ParamDef("TPU", "净重", "净重", "g", "text", 4)
            )
        )
    }
}

/**
 * 编辑操作的差异描述，写进流水备注。
 * 抽成纯函数是为了能跑 JVM 单测 —— 这段逻辑决定「改了什么」能不能被追溯。
 */
internal fun diffNote(before: Part, after: Part): String {
    val parts = mutableListOf<String>()
    if (before.location != after.location) {
        parts += "仓位 ${before.location.ifBlank { "空" }}→${after.location.ifBlank { "空" }}"
    }
    if (before.category != after.category) parts += "分类 ${before.category}→${after.category}"
    if (before.domain != after.domain) {
        parts += "物料域 ${Domains.label(before.domain)}→${Domains.label(after.domain)}"
    }
    if (before.mpn != after.mpn) parts += "型号 ${before.mpn.ifBlank { "空" }}→${after.mpn.ifBlank { "空" }}"
    if (before.lowThreshold != after.lowThreshold) {
        parts += "阈值 ${fmtQty(before.lowThreshold)}→${fmtQty(after.lowThreshold)}"
    }
    if (before.unit != after.unit) parts += "单位 ${before.unit}→${after.unit}"
    if (before.price != after.price) parts += "单价 ${before.price}→${after.price}"
    return parts.joinToString("; ").ifBlank { "资料修改" }
}
