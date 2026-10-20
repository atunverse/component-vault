package com.atun.componentvault.enrich

/**
 * 补全编排器。
 *
 * 分两段，互不阻塞：
 *  - **本地段** [localSuggest]：纯函数、毫秒级。输入即出卡片，断网也有结果。
 *  - **联网段** [lcscSuggest]：查立创元件库，拿到真实立创号与商品页链接后原地覆盖。
 *
 * 联网段**不需要任何 API Key**。用的是立创 EDA 专业版元件库的检索接口，
 * 见 [LcscClient] 里的实测记录。之前预留的开放平台 Key 方案已废弃：
 * ips.lcsc.com 要企业认证才发 Key，配额 1000 次/日，对个人库存工具没必要。
 */
object Enricher {

    data class Candidate(
        val guess: RuleParser.Guess,
        /**
         * 型号。必须单独放一个字段：
         * [RuleParser.Guess.name] 在本地规则里是"品类名"（如「贴片电阻」），
         * 在立创结果里是型号，两者语义不同。拿 name 当型号预填会填出品类名。
         */
        val mpn: String = "",
        val lcscCode: String = "",
        val datasheetUrl: String = "",
        val brand: String = "",
        val source: String = "本地规则",
        /** 参数字段里哪些是推断值，界面上要标虚线框 */
        val guessed: Boolean = true,
        /** 立创商城商品页，可直接打开。空串表示没有精确链接 */
        val productUrl: String = "",
        val productId: Long = 0,
        val stock: Int = 0,
        val unitPrice: Double = 0.0,
        val imageUrl: String = "",
        /** 计量单位，来自立创 stockUnit（电子件多为「个」，五金件是「支」「盒」） */
        val unit: String = "",
        val matchKind: String = ""
    ) {
        val hasLcscLink: Boolean get() = productUrl.isNotBlank()
    }

    /**
     * 同步、纯本地、毫秒级。输入即出卡片，不等网络。
     */
    fun localSuggest(input: String): List<Candidate> =
        RuleParser.parse(input).map { g ->
            Candidate(
                guess = g,
                source = when (g.matchedBy) {
                    "型号库" -> "内置型号库"
                    "原文" -> "未识别"
                    else -> "型号规则解析"
                },
                guessed = g.matchedBy != "型号库"
            )
        }

    /** 联网检索的三种结局，UI 要分别给出不同文案 */
    sealed class Remote {
        data class Hits(val query: String, val items: List<Candidate>, val fromCache: Boolean = false) : Remote()
        data object Empty : Remote()
        data class Failed(val reason: String) : Remote()
    }

    /**
     * 查立创元件库。输入型号 / 关键词 / C 编号都可以，**中间带空格也可以**。
     *
     * 多词处理：立创的检索接口对单个型号极准、对多词时好时坏，所以拿「最像型号的那个词」
     * 去查（见 [SearchTerms.primary]），剩下的词在结果里做 AND 过滤。
     * 过滤后为空就不过滤 —— 商品标题常常只写半个型号，宁可多给几条让人自己看，
     * 也好过因为多打一个词就得到「未收录」。
     *
     * 返回的每条 Candidate 都带 productUrl，点一下就能打开商品页。
     */
    suspend fun lcscSuggest(input: String, forceRefresh: Boolean = false): Remote {
        val terms = SearchTerms.split(input)
        val keyword = SearchTerms.primary(terms).ifBlank { input.trim() }
        return when (val r = LcscResolver.resolve(keyword, forceRefresh)) {
            is LcscResolver.Result.Hits -> {
                val hits = SearchTerms.filterOrKeep(r.hits, terms) { hit ->
                    "${hit.mpn} ${hit.title} ${hit.brand} ${hit.packageName} ${hit.category}"
                }
                Remote.Hits(
                    query = r.query,
                    items = hits.map { it.toCandidate() },
                    fromCache = r.fromCache
                )
            }
            is LcscResolver.Result.Empty -> Remote.Empty
            is LcscResolver.Result.Failed -> Remote.Failed(r.reason)
        }
    }

    /** 立创结果 → 候选卡片。数据来自官方，故 guessed=false */
    private fun LcscHit.toCandidate(): Candidate {
        val params = LinkedHashMap<String, String>()
        // 立创的结构化参数（CPU内核、容值、耐压…）直接作参数
        specs.forEach { (k, v) -> params[k] = v }
        if (params.isEmpty() && stock > 0) params["库存"] = "$stock 个"

        val summaryParts = mutableListOf<String>()
        if (brand.isNotBlank()) summaryParts += brand
        if (packageName.isNotBlank()) summaryParts += packageName
        specs.entries.take(2).forEach { summaryParts += "${it.key} ${it.value}" }
        if (stock > 0) summaryParts += "现货 $stock"
        val summary = if (summaryParts.isNotEmpty()) summaryParts.joinToString(" · ")
        else title.ifBlank { mpn }

        val tags = mutableListOf<String>()
        if (category.isNotBlank()) tags += category.substringBefore("(").trim()
        if (matchKind == LcscHit.MatchKind.FUZZY) tags += "疑似"

        return Candidate(
            guess = RuleParser.Guess(
                name = mpn.ifBlank { title.take(20) },
                category = category.ifBlank { "未分类" },
                packageName = packageName,
                params = params,
                tags = tags.distinct(),
                summary = summary,
                confidence = when (matchKind) {
                    LcscHit.MatchKind.CODE -> 1.0
                    LcscHit.MatchKind.EXACT -> 0.98
                    LcscHit.MatchKind.PREFIX -> 0.85
                    LcscHit.MatchKind.FUZZY -> 0.6
                },
                matchedBy = "立创商城"
            ),
            mpn = mpn,
            lcscCode = lcscCode,
            datasheetUrl = datasheetUrl.orEmpty(),
            brand = brand,
            source = "立创商城",
            guessed = false,
            productUrl = productUrl,
            productId = productId,
            stock = stock,
            unitPrice = unitPrice,
            imageUrl = imageUrl,
            unit = unit,
            matchKind = when (matchKind) {
                LcscHit.MatchKind.CODE -> "立创号精确"
                LcscHit.MatchKind.EXACT -> "型号精确"
                LcscHit.MatchKind.PREFIX -> "型号前缀"
                LcscHit.MatchKind.FUZZY -> "模糊匹配"
            }
        )
    }

    /**
     * 已在库器件 → 商品页链接。
     * 本地有 productId 就直接拼，无需联网；只有立创号时才去检索。
     */
    fun knownUrl(productId: Long, lcscCode: String): String =
        LcscLink.best(productId, lcscCode)
}
