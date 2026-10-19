package com.atun.componentvault.enrich

/**
 * 匹配与排序。整个方案里"型号 → 立创号对应关系是否准确"就落在这个文件上。
 *
 * 为什么必须自己再排一次序：立创搜索是**模糊检索**，直接取第一条会张冠李戴。
 * 实测反例：
 *  - 搜 `C8734` 返回 14 条，第一条正确，但第二条是 `DAC8734EVM`（另一个器件）
 *  - 搜 `SS34` 返回 404 条，前两条型号都叫 SS34，但封装分别是 SMC(DO-214AB) 与 SMA(DO-214AC)
 *  - 搜 `10k 0603` 返回 265047 条，靠相关性排序，型号字段并非精确等于查询串
 *
 * 所以策略是：**先精确、后模糊；同分不合并，全部给出让用户挑**。
 * 这个类是纯函数，不依赖网络和 Android，见 LcscMatcherTest 的用例。
 */
object LcscMatcher {

    /** 立创号形态：C + 3~9 位数字 */
    private val CODE_RE = Regex("^[Cc]\\d{3,9}$")

    /** 型号常见包装后缀，归一化时剥掉，避免 NE555 匹配不到 NE555DR */
    private val PKG_SUFFIXES = listOf("PBF", "TR", "T/R", "G", "E3", "E4", "LF", "NOPB")

    fun isLcscCode(input: String): Boolean = CODE_RE.matches(input.trim())

    /**
     * 归一化：去空白、连字符、下划线、斜杠，统一大写。
     * 型号在不同来源里写法不一致（`AMS1117-3.3` / `AMS1117 3.3`），不归一化就匹配不上。
     * 保留 `.`，因为 3.3 这类电压值去掉点会变成 33，语义全变。
     */
    fun normalize(s: String): String =
        s.uppercase().replace(Regex("[\\s\\-_/]+"), "").trim()

    private fun stripSuffix(norm: String): String {
        for (sfx in PKG_SUFFIXES) {
            if (norm.length > sfx.length + 2 && norm.endsWith(sfx)) {
                return norm.dropLast(sfx.length)
            }
        }
        return norm
    }

    /**
     * 单条评分。分值只为拉开档次，不做概率解释：
     *  - C 编号完全相等        1000（用户直接给了立创号，最强证据）
     *  - 型号归一化后完全相等   900
     *  - 剥后缀后完全相等       850（NE555 vs NE555DR 这类）
     *  - 型号前缀匹配           700（IRF540N vs IRF540NPBF）
     *  - 型号包含               550
     *  - 标题/分类含关键词       300（纯模糊，画面上要提示"疑似"）
     */
    fun score(p: LcscProduct, query: String): Int {
        val qRaw = query.trim()
        val q = normalize(qRaw)
        if (q.isEmpty()) return 0

        var s = when {
            isLcscCode(qRaw) && p.code.equals(qRaw, ignoreCase = true) -> 1000
            isLcscCode(qRaw) && p.code.contains(qRaw, ignoreCase = true) -> 400
            else -> 0
        }

        if (s == 0) {
            val m = normalize(p.model)
            s = when {
                m.isEmpty() -> 0
                m == q -> 900
                stripSuffix(m) == stripSuffix(q) -> 850
                m.startsWith(q) -> 700
                q.length >= 3 && m.contains(q) -> 550
                else -> {
                    val hay = p.name + " " + p.catalogName + " " + p.brandName
                    if (query.length >= 2 && hay.contains(query, ignoreCase = true)) 300 else 0
                }
            }
        }

        if (s > 0) {
            // 实用加分：有货、有手册、有图，优先展示更能直接用的那条
            if (p.stock > 0) s += 20
            if (!p.pdfFileUrl.isNullOrBlank()) s += 10
            if (p.firstImage.isNotEmpty()) s += 5
        }
        return s
    }

    fun kindOf(p: LcscProduct, query: String): LcscHit.MatchKind {
        val qRaw = query.trim()
        val q = normalize(qRaw)
        if (isLcscCode(qRaw) && p.code.equals(qRaw, ignoreCase = true)) return LcscHit.MatchKind.CODE
        val m = normalize(p.model)
        return when {
            m == q || stripSuffix(m) == stripSuffix(q) -> LcscHit.MatchKind.EXACT
            m.startsWith(q) -> LcscHit.MatchKind.PREFIX
            else -> LcscHit.MatchKind.FUZZY
        }
    }

    /**
     * 排序 + 裁剪 + 转 LcscHit。
     * @param limit 给用户看的候选数量，默认 8（要有 2~3 个备选，因为同型号常有多封装）
     * @param minScore 低于该分视为不相关直接丢弃，避免"搜 A 出来一堆无关件"
     */
    fun rank(
        list: List<LcscProduct>,
        query: String,
        limit: Int = 8,
        minScore: Int = 300
    ): List<LcscHit> =
        list.asSequence()
            .map { it to score(it, query) }
            .filter { it.second >= minScore }
            .sortedWith(
                compareByDescending<Pair<LcscProduct, Int>> { it.second }
                    .thenByDescending { it.first.stock }
            )
            .take(limit)
            .map { (p, s) ->
                LcscHit(
                    lcscCode = p.code,
                    mpn = p.model,
                    packageName = p.standard,
                    brand = p.brandName,
                    category = p.catalogName,
                    title = p.name,
                    stock = p.stock,
                    unitPrice = p.unitPrice,
                    specs = p.specs,
                    productId = p.id,
                    productUrl = LcscLink.best(p.id, p.code),
                    datasheetUrl = LcscLink.datasheet(p.pdfFileUrl),
                    imageUrl = p.firstImage,
                    score = s,
                    matchKind = kindOf(p, query)
                )
            }
            .toList()

    /**
     * 同型号多封装检测：用于界面提示「该型号有 N 种封装，请确认」。
     * 这是 SS34 案例的直接产物——自动选第一条有很大概率选错封装。
     */
    fun distinctPackages(hits: List<LcscHit>): List<String> =
        hits.map { it.packageName }.filter { it.isNotBlank() }.distinct()
}
