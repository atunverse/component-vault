package com.atun.componentvault.enrich

/**
 * 关键词拆分与匹配。
 *
 * 解决的是「搜的时候带空格就不灵」这件事。手机输入法里打 `STM32 F103C8T6`
 * 或 `PLA 1.75 黑` 是极自然的写法，但之前整串直接丢给立创 / 直接整串 contains，
 * 空格在里面成了一颗多余的字符：
 *
 *  - 立创检索 `AMS1117 3.3` 能返回一堆结果，但拿整串去跟型号做归一化比对
 *    （归一化会删掉空格）时，只有 `AMS11173.3` 这种畸形串才"相等"，对不上。
 *  - 本地库存检索 `PLA 黑` 要求某个字段**连续**包含这两个字，而实际数据是
 *    「PLA 线材 … 黑色」，永远匹配不上。
 *
 * 所以统一成两条规则：
 *  1. **拆词**：按空白切，丢掉空串。
 *  2. **全词命中**：每个词都出现在某处才算命中（AND）。只有一个词时退化成原来的行为。
 *
 * 纯函数，无 Android 依赖，可直接跑 JVM 单测。
 */
object SearchTerms {

    /** 拆词。全角空格（U+3000）也要认，中文输入法很容易打出这个。 */
    fun split(raw: String): List<String> =
        raw.split(WS).map { it.trim() }.filter { it.isNotEmpty() }

    private val WS = Regex("[\\s\\u3000]+")

    /**
     * 挑一个「最像型号」的词拿去检索。
     *
     * 立创的检索对整串多词的支持时好时坏，但对单个型号极准。挑选顺序：
     *  1. 含数字且长度 >= 3 的词 —— 型号、容值、规格几乎都长这样（`F103C8T6`、`1.75`、`M3`）
     *  2. 最长的词
     *  3. 第一个词
     *
     * 只给一个词的输入会原样返回，不改变原有行为。
     */
    fun primary(terms: List<String>): String {
        if (terms.isEmpty()) return ""
        if (terms.size == 1) return terms[0]
        // 含数字的词里取**最长**的那个：`STM32 F103C8T6` 两个都含数字，
        // 但只有后者是完整料号，用 STM32 去查会返回整个系列几十条
        val numeric = terms.filter { t -> t.length >= 3 && t.any { it.isDigit() } }
        return numeric.maxByOrNull { it.length }
            ?: terms.maxByOrNull { it.length }
            ?: terms[0]
    }

    /** 单串是否包含所有词（不区分大小写）。词表为空时视为命中。 */
    fun matches(text: String, terms: List<String>): Boolean {
        if (terms.isEmpty()) return true
        val t = text.lowercase()
        return terms.all { t.contains(it.lowercase()) }
    }

    /** 多个字段里是否有任意一个字段包含所有词 —— 库存检索用这个 */
    fun matchesAny(fields: List<String>, terms: List<String>): Boolean {
        if (terms.isEmpty()) return true
        val hay = fields.filter { it.isNotBlank() }.joinToString("\u0001").lowercase()
        return terms.all { hay.contains(it.lowercase()) }
    }

    /**
     * 按词过滤候选，**过滤后为空就不过滤**。
     *
     * 这条兜底很重要：立创的商品标题常常只写了半个型号（`AMS1117-3.3` 写成
     * `AMS1117 3.3V 1A`），用户多打一个词就可能把所有正确结果都筛掉。
     * 与其给他一个空列表，不如把原始候选还给他，让人自己看。
     */
    fun <T> filterOrKeep(items: List<T>, terms: List<String>, text: (T) -> String): List<T> {
        if (terms.size <= 1) return items
        val kept = items.filter { matches(text(it), terms) }
        return kept.ifEmpty { items }
    }
}
