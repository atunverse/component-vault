package com.atun.componentvault.data

import android.content.Context
import com.atun.componentvault.enrich.LcscHit
import com.atun.componentvault.enrich.LcscLink
import com.atun.componentvault.enrich.LcscMatcher
import com.atun.componentvault.enrich.LcscResolver
import com.atun.componentvault.enrich.SearchTerms
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
        if (part.iconPath.isBlank() &&
            (part.iconUrl.isNotBlank() || part.lcscProductId > 0 || part.lcscCode.isNotBlank())
        ) {
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
     * 抓一次图标（远程 URL 优先，其次按立创号/型号回查）。
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

    /**
     * 只有图 URL 为空时才回查检索接口。
     *
     * ★ 这里曾经有个把库存「变成没有图标」的隐蔽缺陷（v0.3.0 实测反馈）：
     * 早先的实现优先拿 `lcscProductId` 当检索关键词。可立创的商品数字 id（如 9243）
     * 在它自己的检索里**不是一个有效关键词** —— 搜 `9243` 会返回 160 条毫不相干的结果
     * （接线端子、音频功放都出来了），再被 LcscMatcher 的 minScore 全部丢弃，于是一律抓不到图。
     * 立创号（C8734）才是能精确反查的标识，型号（STM32F103C8T6）次之，名称最后兜底。
     */
    private suspend fun resolveImageUrl(p: Part): String {
        val hit = searchByLocalIdentity(p) ?: return ""
        return hit.imageUrl
    }

    /**
     * 按本地已有信息去立创反查一条最可能的记录。
     * 检索词优先级：立创号 > 型号 > 名称。数字商品 id 不参与检索（见上）。
     */
    private suspend fun searchByLocalIdentity(p: Part): LcscHit? {
        for (key in searchKeys(p)) {
            val r = LcscResolver.resolve(key)
            val hits = (r as? LcscResolver.Result.Hits)?.hits ?: continue
            pickHit(p, hits)?.let { return it }
        }
        return null
    }

    /** 可用的检索词，去重且保持优先级 */
    private fun searchKeys(p: Part, extra: String = ""): List<String> {
        val keys = LinkedHashSet<String>()
        if (extra.isNotBlank()) keys += extra.trim()
        if (p.lcscCode.isNotBlank()) keys += p.lcscCode.trim()
        if (p.mpn.trim().length >= 2) keys += p.mpn.trim()
        if (p.name.trim().length >= 2) keys += p.name.trim()
        return keys.toList()
    }

    /**
     * 从候选里挑一条。**宁可挑不到，也不张冠李戴**：
     * 先认立创号完全相等，再认型号归一化后相等，最后只接受「型号前缀」级别的匹配。
     *
     * 模糊命中（标题里恰好出现了关键词）一律不要。反例很具体：
     * 库存里叫「10kΩ」的电阻，搜出来几百个不同封装的贴片电阻，标题都含「10kΩ」；
     * 随便挑第一条写进 lcscCode，用户下次点「立创商城」就跳到别人的商品页，
     * 挂上去的图也是别的封装 —— 这种"补全"比空着更糟。
     */
    private fun pickHit(p: Part, hits: List<LcscHit>): LcscHit? {
        if (hits.isEmpty()) return null
        if (p.lcscCode.isNotBlank()) {
            hits.firstOrNull { it.lcscCode.equals(p.lcscCode, ignoreCase = true) }?.let { return it }
        }
        if (p.mpn.isNotBlank()) {
            val q = LcscMatcher.normalize(p.mpn)
            hits.firstOrNull { LcscMatcher.normalize(it.mpn) == q }?.let { return it }
        }
        return hits.firstOrNull { it.matchKind != LcscHit.MatchKind.FUZZY }
    }

    /**
     * 一个「让用户自己挑」的立创候选。
     *
     * 只带界面需要展示的字段：型号、封装、品牌、单价、现货、匹配强度。
     * 不放 LcscHit 全量对象 —— 界面不该依赖检索层的模型，将来换数据源不用改 UI。
     */
    data class LcscOption(
        val lcscCode: String,
        val mpn: String,
        val packageName: String = "",
        val brand: String = "",
        val price: Double = 0.0,
        val stock: Int = 0,
        val matchKind: String = ""
    )

    /**
     * 刷新结果。界面直接展示，不用自己拼字符串。
     * @param message 给用户看的一句话，成功失败都有人话解释
     * @param options 只有模糊命中时才有值：让用户点一个直接用，不用自己再敲一遍
     */
    data class RefreshReport(
        val ok: Boolean,
        val message: String,
        val changed: List<String> = emptyList(),
        val code: String = "",
        val options: List<LcscOption> = emptyList(),
        val keyword: String = ""
    )

    /**
     * 按名字 / 型号 / 立创号重新获取立创信息，并把能填的都填回去。
     *
     * 这是「库存里那些当初没录立创号的老器件」的唯一修复入口：
     * 点一下 → 用型号去搜 → 命中后补上立创号、商品页、封装、单价、手册、图片。
     *
     * 只填**空着的**字段，不覆盖用户手改过的内容（单价除外：原价为 0 才填）。
     * 图标只在「本来没有图」或「原图来自立创」时替换，不会覆盖用户拍的照片。
     */
    suspend fun refreshLcsc(partId: Long, keyword: String = ""): RefreshReport {
        val p = partDao.byId(partId) ?: return RefreshReport(false, "器件不存在")
        val keys = searchKeys(p, keyword)
        if (keys.isEmpty()) return RefreshReport(false, "型号和名称都是空的，没有可检索的内容")

        var reason = "立创未收录该型号"
        var fallback: RefreshReport? = null
        for (raw in keys) {
            // 型号里带空格（`AMS1117 3.3`）时，只拿最像型号的那个词去查
            val key = SearchTerms.primary(SearchTerms.split(raw)).ifBlank { raw }
            when (val r = LcscResolver.resolve(key, forceRefresh = true)) {
                is LcscResolver.Result.Failed -> reason = "网络不可用：${r.reason}"
                is LcscResolver.Result.Empty -> reason = "立创检索「$key」没有结果"
                is LcscResolver.Result.Hits -> {
                    val hit = pickHit(p, r.hits)
                    if (hit != null) return applyHit(p, hit, key)
                    // 自动挑不准，但结果并非无意义：把它们变成可点选的候选，交给用户判断。
                    // 之前这里是死路一条（只回一句「只有模糊结果」），用户拿着一条具体型号
                    // 却只能自己去立创网站再搜一遍 —— 明明候选就在手上。
                    val opts = r.hits.take(MAX_OPTIONS).map {
                        LcscOption(
                            lcscCode = it.lcscCode,
                            mpn = it.mpn,
                            packageName = it.packageName,
                            brand = it.brand,
                            price = it.unitPrice,
                            stock = it.stock,
                            matchKind = when (it.matchKind) {
                                LcscHit.MatchKind.CODE -> "立创号精确"
                                LcscHit.MatchKind.EXACT -> "型号精确"
                                LcscHit.MatchKind.PREFIX -> "型号前缀"
                                LcscHit.MatchKind.FUZZY -> "疑似"
                            }
                        )
                    }
                    if (opts.isNotEmpty() && fallback == null) {
                        fallback = RefreshReport(
                            ok = false,
                            message = "没有完全匹配的型号，下面 ${opts.size} 个是立创的疑似项，" +
                                "点一个就按它填（或者把型号写具体些再刷新）",
                            options = opts,
                            keyword = key
                        )
                    }
                    reason = "立创只有模糊结果，没有自动填"
                }
            }
        }
        return fallback ?: RefreshReport(false, reason)
    }

    /** 候选最多给几条：再多用户也懒得看，反而把详情页撑得很长 */
    private companion object {
        const val MAX_OPTIONS = 6
    }

    /**
     * 用户从候选里手动挑了一条。
     *
     * 和自动匹配不同：**这是明确的意图**，所以允许覆盖已有的立创号、封装、品牌、单价与图标
     * （自动匹配只填空位，见 [applyHit] 的 override 参数）。
     * 但**不动用户的型号与名称** —— 库存里叫「10kΩ」是他自己的叫法，
     * 选个料号不代表要把它改名成 RC0603FR-0710KL。
     */
    suspend fun applyLcscOption(partId: Long, lcscCode: String): RefreshReport {
        val p = partDao.byId(partId) ?: return RefreshReport(false, "器件不存在")
        val code = lcscCode.trim()
        if (code.isBlank()) return RefreshReport(false, "立创号为空")

        // 先按 C 编号精确查一次：立创对 C 编号支持最好，一条就能定位
        val direct = (LcscResolver.resolve(code, forceRefresh = true) as? LcscResolver.Result.Hits)
            ?.hits.orEmpty()
        direct.firstOrNull { it.lcscCode.equals(code, ignoreCase = true) }?.let {
            return applyHit(p, it, "手动选择 $code", force = true)
        }

        // 有些件按 C 编号反查拿不到（立创偶尔只给商品页不给检索记录），
        // 退回原始检索词再查一遍，在这次的结果里按 code 找
        for (raw in searchKeys(p, "")) {
            val key = SearchTerms.primary(SearchTerms.split(raw)).ifBlank { raw }
            val rr = LcscResolver.resolve(key, forceRefresh = true)
            val hits = (rr as? LcscResolver.Result.Hits)?.hits.orEmpty()
            hits.firstOrNull { it.lcscCode.equals(code, ignoreCase = true) }?.let {
                return applyHit(p, it, "手动选择 $code", force = true)
            }
        }
        return RefreshReport(false, "没取到 $code 的详情，可能网络不通，稍后再试一次")
    }

    /** 批量刷新的进度快照 */
    data class RefreshBatch(
        val done: Int = 0,
        val total: Int = 0,
        val hit: Int = 0,
        val lastMessage: String = "",
        val running: Boolean = false
    )

    /**
     * 把「还没有立创号」的电子/五金件批量重新查一遍。
     *
     * 只处理 lcscCode 为空的 —— 已经有号的不动，避免把用户核过的对应关系冲掉。
     * 串行 + 间隔，别把立创检索接口当压力测试打。
     */
    suspend fun refreshMissingLcsc(onProgress: (RefreshBatch) -> Unit): RefreshBatch {
        val targets = partDao.all().filter {
            (it.domain == Domains.ELECTRONICS || it.domain == Domains.HARDWARE) && it.lcscCode.isBlank()
        }
        var done = 0
        var hit = 0
        var last = ""
        onProgress(RefreshBatch(0, targets.size, 0, "", true))
        for (p in targets) {
            val r = refreshLcsc(p.id)
            if (r.ok) hit++
            if (r.message.isNotBlank()) last = "${p.mpn.ifBlank { p.name }}：${r.message}"
            done++
            onProgress(RefreshBatch(done, targets.size, hit, last, true))
            kotlinx.coroutines.delay(320)
        }
        val final = RefreshBatch(done, targets.size, hit, last, false)
        onProgress(final)
        return final
    }

    /**
     * 把一条立创记录写回库存。
     *
     * @param force false = 自动匹配，**只填空位**，用户手改过的一律不动；
     *              true = 用户从候选里手动点的，明确要换成这条，允许覆盖。
     */
    private suspend fun applyHit(
        p: Part,
        hit: LcscHit,
        key: String,
        force: Boolean = false
    ): RefreshReport {
        val changed = mutableListOf<String>()

        val code = if (force || p.lcscCode.isBlank()) {
            hit.lcscCode.ifBlank { p.lcscCode }
        } else p.lcscCode
        if (code != p.lcscCode && code.isNotBlank()) changed += "立创号 $code"

        // 只有在「本来没号」「号与命中一致」或「用户手动指定」时才改商品 id，
        // 否则会出现 C8734 配着另一个商品的 id，链接直接跳错页
        val codeMatches = force || p.lcscCode.isBlank() ||
            hit.lcscCode.equals(p.lcscCode, ignoreCase = true)
        val productId = if (hit.productId > 0 && codeMatches) hit.productId else p.lcscProductId

        val pkg = when {
            force && hit.packageName.isNotBlank() -> hit.packageName
            p.packageName.isBlank() -> hit.packageName
            else -> p.packageName
        }
        if (pkg != p.packageName && pkg.isNotBlank()) changed += "封装 $pkg"

        val brand = when {
            force && hit.brand.isNotBlank() -> hit.brand
            p.brand.isBlank() -> hit.brand
            else -> p.brand
        }
        if (brand != p.brand && brand.isNotBlank()) changed += "品牌 $brand"

        val datasheet = when {
            force && !hit.datasheetUrl.isNullOrBlank() -> hit.datasheetUrl.orEmpty()
            p.datasheetUrl.isBlank() -> hit.datasheetUrl.orEmpty()
            else -> p.datasheetUrl
        }
        if (datasheet != p.datasheetUrl && datasheet.isNotBlank()) changed += "数据手册"

        val price = when {
            force && hit.unitPrice > 0.0 -> hit.unitPrice
            p.price <= 0.0 && hit.unitPrice > 0.0 -> hit.unitPrice
            else -> p.price
        }
        if (price != p.price) changed += "单价 ¥${fmtQty(price)}"

        val imageUrl = hit.imageUrl.ifBlank { p.iconUrl }

        // 图标：本来没图，或者原本就来自立创，才覆盖；用户拍的照片不动
        var iconPath = p.iconPath
        var iconSource = p.iconSource
        var iconOk = false
        // 用户手动选了这一条，图也跟着换（他明确表达要这个商品）
        val mayReplace = force || p.iconPath.isBlank() || p.iconSource == "lcsc"
        if (mayReplace && imageUrl.isNotBlank() && IconStore.fromUrl(appContext, p.id, imageUrl)) {
            iconPath = IconStore.nameFor(p.id)
            iconSource = "lcsc"
            iconOk = true
            if (p.iconPath != iconPath) changed += "图标"
        }

        partDao.update(
            p.copy(
                lcscCode = code,
                lcscProductId = productId,
                packageName = pkg,
                brand = brand,
                datasheetUrl = datasheet,
                price = price,
                iconUrl = imageUrl,
                iconPath = iconPath,
                iconSource = iconSource,
                updatedAt = System.currentTimeMillis()
            )
        )
        txnDao.insert(
            Txn(
                partId = p.id,
                type = "EDIT",
                delta = 0.0,
                before = p.qty,
                after = p.qty,
                note = "刷新立创信息（检索词「$key」）" +
                    if (changed.isEmpty()) "" else "：" + changed.joinToString("、")
            )
        )

        val message = when {
            changed.isNotEmpty() -> "已更新：" + changed.joinToString("、")
            iconOk || p.iconPath.isNotBlank() -> "立创信息已是最新（${code.ifBlank { hit.lcscCode }}）"
            else -> "立创有这条记录，但没取到图片"
        }
        return RefreshReport(true, message, changed, code)
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
                lcscCode = "C8734", lcscProductId = 9243,
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
                lcscCode = "C5138485",
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
            // ---- 3D 打印耗材示例（字段参照 Spoolman 的 filament + spool 两层）
            Part(
                internalCode = "", mpn = "PLA 1.75 黑", name = "PLA 线材", category = "PLA",
                domain = Domains.FILAMENT, packageName = "1.75mm",
                spec = "直径 1.75mm · 净重 1kg · 黑色",
                brand = "eSUN 易生",
                paramsJson = """{"材质":"PLA","直径":"1.75","颜色":"黑","颜色值":"#1A1A1A","净重":"1000","空盘重":"190","密度":"1.24","喷嘴温度":"195-215","热床温度":"55-65","批次号":"","开封日期":""}""",
                tags = "VORON,外壳件,易打印", summary = "PLA 1.75mm 黑色，1kg 盘装，易打印适合外观件",
                qty = 734.0, unit = "g", lowThreshold = 200.0, location = "耗材架 第 1 层", price = 89.0
            ),
            Part(
                internalCode = "", mpn = "PETG 1.75 透明", name = "PETG 线材", category = "PETG",
                domain = Domains.FILAMENT, packageName = "1.75mm",
                spec = "直径 1.75mm · 净重 1kg · 透明",
                brand = "Polymaker",
                paramsJson = """{"材质":"PETG","直径":"1.75","颜色":"透明","颜色值":"#E3F2FD","净重":"1000","空盘重":"220","密度":"1.27","喷嘴温度":"230-250","热床温度":"75-85","批次号":"","开封日期":""}""",
                tags = "VORON,结构件,耐温", summary = "PETG 1.75mm 透明，1kg 盘装，韧性与耐温优于 PLA",
                qty = 120.0, unit = "g", lowThreshold = 250.0, location = "耗材架 第 2 层", price = 109.0
            ),
            Part(
                internalCode = "", mpn = "TPU 1.75 黑", name = "TPU 柔性线材", category = "TPU",
                domain = Domains.FILAMENT, packageName = "1.75mm",
                spec = "直径 1.75mm · 净重 0.5kg · 黑色 · 95A",
                brand = "三绿 SUNLU",
                paramsJson = """{"材质":"TPU","直径":"1.75","颜色":"黑","颜色值":"#1A1A1A","净重":"500","空盘重":"190","密度":"1.21","喷嘴温度":"210-235","热床温度":"30-50","批次号":"","开封日期":""}""",
                tags = "柔性,密封圈,减震", summary = "TPU 95A 1.75mm 黑色，0.5kg 盘装，慢速打印",
                qty = 420.0, unit = "g", lowThreshold = 100.0, location = "耗材架 第 3 层", price = 79.0
            )
        )
        demo.forEach { create(it) }
    }

    /** 幂等：ParamDef 主键是 (category, key)，重复 upsert 只会刷新标签，不会产生重复行 */
    private suspend fun seedParamDefs() {
        // 耗材分类的参数模板按预设材料批量生成：新增一种材料只需要改 FilamentPresets，
        // 不用回来补 ParamDef，也不会漏掉某一种材料的温度项。
        val filamentDefs = com.atun.componentvault.enrich.FilamentPresets.MATERIALS.flatMap { m ->
            listOf(
                ParamDef(m.name, FilamentKeys.MATERIAL, "材质", "", "text", 1),
                ParamDef(m.name, FilamentKeys.DIAMETER, "线径", "mm", "number", 2),
                ParamDef(m.name, FilamentKeys.COLOR, "颜色", "", "text", 3),
                ParamDef(m.name, FilamentKeys.COLOR_HEX, "颜色色值", "", "text", 4),
                ParamDef(m.name, FilamentKeys.NET_WEIGHT, "标称净重", "g", "number", 5),
                ParamDef(m.name, FilamentKeys.SPOOL_WEIGHT, "空盘重", "g", "number", 6),
                ParamDef(m.name, FilamentKeys.DENSITY, "密度", "g/cm³", "number", 7),
                ParamDef(m.name, FilamentKeys.NOZZLE, "喷嘴温度", "℃", "text", 8),
                ParamDef(m.name, FilamentKeys.BED, "热床温度", "℃", "text", 9),
                ParamDef(m.name, FilamentKeys.LOT, "批次号", "", "text", 10),
                ParamDef(m.name, FilamentKeys.OPENED_AT, "开封日期", "", "text", 11)
            )
        }
        paramDao.upsertAll(
            filamentDefs + listOf(
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
                // ---- 3D 耗材的参数模板已在上方按 FilamentPresets 批量生成
                ParamDef("其他", "说明", "说明", "", "text", 1)
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
