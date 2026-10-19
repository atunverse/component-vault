package com.atun.componentvault.enrich

/**
 * 补全编排器。
 *
 * 分层顺序决定了用户体验：先给「立刻就有」的结果，再让网络结果原地覆盖。
 * 批次一只启用前三层（规则 / 型号库），第四层立创 API 留好挂载点，
 * 拿到开放平台 Key 后只需在 [remoteSuggest] 里接上，不影响其他层。
 */
object Enricher {

    data class Candidate(
        val guess: RuleParser.Guess,
        val lcscCode: String = "",
        val datasheetUrl: String = "",
        val brand: String = "",
        val source: String = "本地规则",
        /** 参数字段里哪些是推断值，界面上要标虚线框 */
        val guessed: Boolean = true
    )

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

    /**
     * 立创商城开放平台查询。需要 ips.lcsc.com 的 Key（1000 次/日、200 次/分）。
     * 未配置 Key 时直接返回空列表，调用方按「无结果」处理即可。
     */
    suspend fun remoteSuggest(input: String, apiKey: String): List<Candidate> {
        if (apiKey.isBlank() || input.isBlank()) return emptyList()
        return RemoteLcsc.search(input, apiKey).map { r ->
            Candidate(
                guess = r.toGuess(),
                lcscCode = r.lcscCode,
                datasheetUrl = r.datasheetUrl,
                brand = r.brand,
                source = "立创商城",
                guessed = false
            )
        }
    }
}
