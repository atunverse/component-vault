package com.atun.componentvault.enrich

/**
 * 立创商城开放平台对接占位。
 *
 * 已核实的接口事实：
 *  - 端点 https://ips.lcsc.com/rest/wmsc2agent/search/product 真实存在，
 *    无 Key 调用返回 {"code":424,"message":"Key Is Required"}。
 *  - 配额 1000 次/日、200 次/分，单页最多 30 条 —— 必须做本地缓存，不能每次输入都查。
 *  - 网页端有瑞数 WAF 挑战，不可爬取，只能走开放平台。
 *
 * 待接入：填入 Key 后实现 HTTP 调用与 JSON 解析。
 * 这里的返回值结构先固定下来，方便上层代码无需改动。
 */
object RemoteLcsc {

    data class RemotePart(
        val mpn: String,
        val brand: String,
        val packageName: String,
        val category: String,
        val lcscCode: String,
        val datasheetUrl: String,
        val specs: Map<String, String>,
        val stock: Int,
        val price: Double
    ) {
        fun toGuess(): RuleParser.Guess {
            val parts = mutableListOf(mpn.ifEmpty { "未知型号" })
            specs.entries.take(3).forEach { parts += "${it.key} ${it.value}" }
            if (packageName.isNotEmpty()) parts += packageName
            return RuleParser.Guess(
                name = mpn.ifEmpty { "未命名器件" },
                category = category.ifEmpty { "未分类" },
                packageName = packageName,
                params = specs,
                tags = listOf(category).filter { it.isNotBlank() },
                summary = parts.joinToString("，"),
                confidence = 0.98,
                matchedBy = "立创商城"
            )
        }
    }

    suspend fun search(keyword: String, apiKey: String): List<RemotePart> {
        // TODO: 接入开放平台。签名与字段映射按官方文档实现后放开。
        return emptyList()
    }
}
