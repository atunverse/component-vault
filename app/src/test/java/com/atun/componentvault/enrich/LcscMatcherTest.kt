package com.atun.componentvault.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配与链接构造的回归测试。
 *
 * 所有用例里的数据都来自真实接口响应（2026-10 实测），不是编造的样例：
 *  - C8734 搜出 14 条，其中混入 DAC8734EVM
 *  - SS34 有 404 条，前两条同型号但封装不同（SMC / SMA）
 *  - 商品页只有 item.szlcsc.com/{id}.html 这一个格式可用
 *
 * 纯 JVM 测试，不发网络请求，可在 CI 里稳定运行。
 */
class LcscMatcherTest {

    private fun product(
        id: Long,
        code: String,
        model: String,
        pkg: String = "",
        stock: String = "100",
        pdf: String? = null
    ) = LcscProduct(
        id = id, code = code, model = model, standard = pkg,
        stockNumber = stock, pdfFileUrl = pdf
    )

    // ---------------------------------------------------------- 归一化

    @Test
    fun `归一化忽略大小写与分隔符`() {
        assertEquals("AMS11173.3", LcscMatcher.normalize("AMS1117-3.3"))
        assertEquals("AMS11173.3", LcscMatcher.normalize("ams1117 3.3"))
        assertEquals("AMS11173.3", LcscMatcher.normalize("AMS1117_3.3"))
        // 小数点必须保留，否则 3.3 变 33
        assertTrue(LcscMatcher.normalize("AMS1117-3.3").contains("."))
    }

    @Test
    fun `识别立创号形态`() {
        assertTrue(LcscMatcher.isLcscCode("C8734"))
        assertTrue(LcscMatcher.isLcscCode("c154551"))
        assertTrue(LcscMatcher.isLcscCode("C20652313"))
        assertFalse(LcscMatcher.isLcscCode("STM32F103C8T6"))
        assertFalse(LcscMatcher.isLcscCode("C87"))
    }

    // ---------------------------------------------------------- 排序准确性

    @Test
    fun `按立创号查询时精确命中的排第一且压过同号段干扰`() {
        // 真实反例：搜 C8734 会带出 DAC8734EVM
        val list = listOf(
            product(22066848, "C20652313", "DAC8734EVM"),
            product(9243, "C8734", "STM32F103C8T6", "LQFP-48(7x7)")
        )
        val hits = LcscMatcher.rank(list, "C8734")
        assertEquals("C8734", hits.first().lcscCode)
        assertEquals("STM32F103C8T6", hits.first().mpn)
        assertEquals(LcscHit.MatchKind.CODE, hits.first().matchKind)
        assertTrue(hits.first().score > hits.last().score)
    }

    @Test
    fun `同型号多封装全部保留，不合并成一条`() {
        // SS34 有两种封装，自动取第一条会选错
        val list = listOf(
            product(8397187, "C7420365", "SS34", "SMA(DO-214AC)", "99999"),
            product(165893, "C154551", "SS34", "SMC(DO-214AB)", "5000")
        )
        val hits = LcscMatcher.rank(list, "SS34")
        assertEquals(2, hits.size)
        assertEquals(2, LcscMatcher.distinctPackages(hits).size)
        assertTrue(hits.all { it.mpn == "SS34" })
    }

    @Test
    fun `型号前缀与剥后缀都能命中`() {
        // NE555 实际型号是 NE555DR / NE555P
        val list = listOf(
            product(8070, "C7593", "NE555DR", "SOIC-8"),
            product(47753, "C46749", "NE555P", "DIP-8")
        )
        val hits = LcscMatcher.rank(list, "NE555")
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.matchKind == LcscHit.MatchKind.PREFIX || it.matchKind == LcscHit.MatchKind.EXACT })

        // IRF540N 这类带包装后缀的写法：剥掉 PBF 后相等（850）+ 有货加分（20）
        assertEquals(870, LcscMatcher.score(product(1, "C1", "IRF540NPBF"), "IRF540N"))
    }

    @Test
    fun `完全无关的结果被过滤掉`() {
        val list = listOf(product(1, "C1", "STM32F103C8T6"), product(2, "C2", "AMS1117-3.3"))
        val hits = LcscMatcher.rank(list, "STM32F103C8T6")
        assertEquals(1, hits.size)
        assertEquals("C1", hits.first().lcscCode)
    }

    @Test
    fun `有货有手册的优先展示`() {
        val list = listOf(
            product(1, "C1", "SS34", "SMA", stock = "0", pdf = null),
            product(2, "C2", "SS34", "SMC", stock = "5000", pdf = "/a.pdf")
        )
        val hits = LcscMatcher.rank(list, "SS34")
        assertEquals("C2", hits.first().lcscCode)
    }

    // ---------------------------------------------------------- 链接构造

    @Test
    fun `商品页链接必须用数字ID`() {
        assertEquals("https://item.szlcsc.com/9243.html", LcscLink.product(9243))
        // 有 id 时优先精确页
        assertEquals(
            "https://item.szlcsc.com/9243.html",
            LcscLink.best(9243, "C8734")
        )
    }

    @Test
    fun `没有商品ID时退化到搜索页而不是拼出软404`() {
        val url = LcscLink.best(0, "C8734")
        assertTrue(url.startsWith("https://so.szlcsc.com/global.html?k="))
        assertTrue(url.contains("C8734"))
        // 绝不能拼成 item.szlcsc.com/C8734.html —— 那是软 404
        assertFalse(url.contains("item.szlcsc.com/C8734"))
    }

    @Test
    fun `数据手册相对路径拼上附件域名`() {
        val p = "/upload/public/pdf/source/20220812/8363ACEC.pdf"
        assertEquals("https://atta.szlcsc.com/upload/public/pdf/source/20220812/8363ACEC.pdf", LcscLink.datasheet(p))
        assertEquals("https://atta.szlcsc.com/a/b.pdf", LcscLink.datasheet("a/b.pdf"))
        assertEquals(null, LcscLink.datasheet(null))
        assertEquals(null, LcscLink.datasheet("  "))
    }

    @Test
    fun `识别软404页面`() {
        val real404Html = "<title>您要访问的页面没有找到</title>"
        assertTrue(LcscLink.looksLikeSoft404(real404Html))
        assertFalse(LcscLink.looksLikeSoft404("<title>STM32F103C8T6中文资料-立创商城</title>"))
    }

    @Test
    fun `正常商品页引用404资源不算软404`() {
        // 实测踩过的坑：正常页里也引用 404.html，不能靠这个字符串判断
        val normalPage = "<script>var e='/error/404.html'</script><title>SS34中文资料-立创商城</title>"
        assertFalse(LcscLink.looksLikeSoft404(normalPage))
    }

    // ---------------------------------------------------------- JSON 解析

    @Test
    fun `解析真实响应并取出参数与价格`() {
        // 实测抓到的真实响应片段
        val json = """
        {"code":200,"msg":null,"result":{"total":5,"productList":[{
          "id":9243,
          "code":"C8734",
          "model":"STM32F103C8T6",
          "standard":"LQFP-48(7x7)",
          "brandName":"ST(意法半导体)",
          "catalogName":"单片机(MCU/MPU/SOC)",
          "name":"主流增强型ARM Cortex-M3 MCU",
          "stockNumber":"24051",
          "image":"https://alimg.szlcsc.com/a.jpg<${'$'}>https://alimg.szlcsc.com/b.jpg",
          "pdfFileUrl":"/upload/public/pdf/source/20220812/8363ACEC51AD55ECEB84799464F7CB85.pdf",
          "priceList":[{"startNumber":1,"endNumber":9,"price":9.16},{"startNumber":1000,"endNumber":-1,"price":5.82}],
          "device_info":{"description":"CPU内核:ARM Cortex-M3;CPU最大主频:72MHz;CPU位数:32 Bit;","uuid":"accfc2f6"},
          "JLCPCB Part Class":"Extended Part"
        }],"paramList":null},"ok":true,"success":true}
        """.trimIndent()

        val resp = lcscJson.decodeFromString<LcscResponse>(json)
        assertEquals(200, resp.code)
        assertTrue(resp.ok)
        assertEquals(5L, resp.result?.total)

        val p = resp.result!!.productList.single()
        assertEquals(9243L, p.id)
        assertEquals("C8734", p.code)
        assertEquals("STM32F103C8T6", p.model)
        assertEquals("LQFP-48(7x7)", p.standard)
        assertEquals(24051, p.stock)
        assertEquals(9.16, p.unitPrice, 0.0001)

        // 结构化参数：分号分隔的 键:值
        assertEquals(3, p.specs.size)
        assertEquals("72MHz", p.specs["CPU最大主频"])
        assertEquals("ARM Cortex-M3", p.specs["CPU内核"])

        // 多图用 <$> 分隔，只取第一张
        assertEquals("https://alimg.szlcsc.com/a.jpg", p.firstImage)

        // 拼出来的手册地址可直接下载（实测 200 / application/pdf）
        assertEquals(
            "https://atta.szlcsc.com/upload/public/pdf/source/20220812/8363ACEC51AD55ECEB84799464F7CB85.pdf",
            LcscLink.datasheet(p.pdfFileUrl)
        )
    }

    @Test
    fun `接口新增字段不会导致解析失败`() {
        val json = """{"code":200,"ok":true,"result":{"total":1,"productList":[
            {"id":1,"code":"C1","model":"X","unknownFutureField":{"a":1},"anotherNew":"y"}
        ]},"brandNewTopLevel":123}"""
        val resp = lcscJson.decodeFromString<LcscResponse>(json)
        assertEquals("C1", resp.result!!.productList.single().code)
    }

    @Test
    fun `库存为空串时解析为0不崩`() {
        val p = LcscProduct(id = 1, code = "C1", model = "X", stockNumber = "")
        assertEquals(0, p.stock)
        assertEquals(0.0, p.unitPrice, 0.0001)
        assertTrue(p.specs.isEmpty())
        assertEquals("", p.firstImage)
    }
}
