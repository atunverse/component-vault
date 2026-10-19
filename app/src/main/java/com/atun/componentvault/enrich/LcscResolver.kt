package com.atun.componentvault.enrich

/**
 * 检索编排：缓存 → 远端 → 失败降级。
 *
 * 设计要点：
 *  1. **内存 LRU 缓存**（128 条）。同一次录入里用户会反复改关键词/回退，
 *     不缓存的话每次重打一遍网络，体验会碎。命中缓存直接返回并标记 fromCache。
 *  2. **不抛异常给 UI**。所有失败折成 [Result.Failed]，让界面显示"未联网，用的是本地推断"
 *     而不是弹一个红色错误——库存录入是高频动作，偶发失败不该打断流程。
 *  3. **按 C 编号精确反查**。用户手里只有 `C8734` 时，直接用 keyword 搜，
 *     再按 `code` 完全相等过滤，避免 DAC8734EVM 这类同号段干扰。
 */
object LcscResolver {

    sealed class Result {
        data class Hits(val query: String, val hits: List<LcscHit>, val fromCache: Boolean = false) : Result()
        data class Empty(val query: String) : Result()
        data class Failed(val query: String, val reason: String) : Result()
    }

    private const val MAX_CACHE = 128

    private val cache = object : LinkedHashMap<String, List<LcscHit>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LcscHit>>?): Boolean =
            size > MAX_CACHE
    }

    private fun key(input: String) = LcscMatcher.normalize(input)

    var client: LcscClient = LcscClient()

    /** 只读缓存，供输入过程中的即时预览用，绝不发网络请求 */
    fun cachedOnly(input: String): List<LcscHit>? {
        val k = key(input)
        if (k.isEmpty()) return null
        synchronized(cache) { return cache[k] }
    }

    /**
     * 主入口：输入型号、关键词或立创号都走这里。
     * @param forceRefresh 下拉刷新时绕过缓存
     */
    suspend fun resolve(input: String, forceRefresh: Boolean = false): Result {
        val q = input.trim()
        if (q.length < 2) return Result.Empty(q)
        val k = key(q)

        if (!forceRefresh) {
            synchronized(cache) { cache[k] }?.let { return Result.Hits(q, it, fromCache = true) }
        }

        return try {
            // C 编号查询多取一些：立创会把同号段的其它型号一起返回
            val pageSize = if (LcscMatcher.isLcscCode(q)) 30 else 20
            val raw = client.searchRaw(q, page = 1, pageSize = pageSize)
            var hits = LcscMatcher.rank(raw.productList, q, limit = 8)

            // 立创号精确过滤：只要 code 完全相等的那些，防止张冠李戴
            if (LcscMatcher.isLcscCode(q)) {
                val exact = hits.filter { it.lcscCode.equals(q, ignoreCase = true) }
                if (exact.isNotEmpty()) hits = exact else {
                    // 精确没中，可能用户给的是别的写法，退回严格相等匹配原始结果
                    val exactRaw = raw.productList.filter { it.code.equals(q, ignoreCase = true) }
                    if (exactRaw.isNotEmpty()) hits = LcscMatcher.rank(exactRaw, q, limit = 8)
                }
            }

            if (hits.isEmpty()) Result.Empty(q) else {
                synchronized(cache) { cache[k] = hits }
                Result.Hits(q, hits)
            }
        } catch (e: LcscClient.LcscException) {
            // 网络失败但有旧缓存时，宁可给旧数据也不给空
            val old = synchronized(cache) { cache[k] }
            if (!old.isNullOrEmpty()) Result.Hits(q, old, fromCache = true)
            else Result.Failed(q, e.message ?: "网络不可用")
        } catch (e: Exception) {
            Result.Failed(q, e.message ?: "未知错误")
        }
    }

    /**
     * 已在库器件：只有立创号时反查商品页地址（用于详情页"打开立创商城"按钮）。
     * 优先用本地已存的 productId，没有才联网。
     */
    suspend fun resolveByCode(code: String): LcscHit? {
        val r = resolve(code)
        val hits = (r as? Result.Hits)?.hits ?: return null
        return hits.firstOrNull { it.lcscCode.equals(code.trim(), ignoreCase = true) } ?: hits.firstOrNull()
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    fun cacheSize(): Int = synchronized(cache) { cache.size }
}
