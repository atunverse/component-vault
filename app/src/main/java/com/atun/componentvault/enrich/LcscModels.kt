package com.atun.componentvault.enrich

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 立创元件库响应模型。
 *
 * 数据源：`https://pro.lceda.cn/api/eda/product/search?keyword=&currentPage=&pageSize=`
 * 这是立创 EDA 专业版元件库前端自用的检索接口，**无需 AppKey / 无需登录**，
 * 返回的 `code` 字段就是立创商城编号（如 C8734），`id` 是商品页数字 ID。
 *
 * 实测（2026-10）：
 *  - 连续 10 次不同关键词请求 0 失败，638–840ms，均值 768ms
 *  - pageSize 上限至少 50
 *  - 大小写不敏感（stm32f103c8t6 与 STM32F103C8T6 同结果）
 *  - 支持中文关键词（「肖特基二极管」27112 条、「贴片电阻 10k」12652 条）
 *
 * 字段全部给默认值 + ignoreUnknownKeys，接口加字段时客户端不会崩。
 */
@Serializable
data class LcscProduct(
    /** 商品页数字 ID，链接 https://item.szlcsc.com/{id}.html 的唯一正确来源 */
    val id: Long = 0,
    /** 立创商城编号，如 C8734 */
    val code: String = "",
    /** 型号，如 STM32F103C8T6 */
    val model: String = "",
    /** 封装，如 LQFP-48(7x7) / 0603 */
    val standard: String = "",
    val brandName: String = "",
    val catalogName: String = "",
    val catalogId: Long = 0,
    /** 官方商品标题，例如「主流增强型ARM Cortex-M3 MCU，具有64 KB Flash…」 */
    val name: String = "",
    /** 库存数量，字符串型，可能是空串 */
    val stockNumber: String = "",
    /** 多张图用 `<$>` 分隔 */
    val image: String = "",
    /** 数据手册相对路径，拼 https://atta.szlcsc.com 前缀即可下载 */
    val pdfFileUrl: String? = null,
    val priceList: List<LcscPrice> = emptyList(),
    @SerialName("device_info")
    val deviceInfo: LcscDeviceInfo? = null,
    @SerialName("JLCPCB Part Class")
    val jlcPartClass: String? = null
) {
    /** 首图，去掉多图分隔符后的第一张 */
    val firstImage: String get() = image.substringBefore("<\$>").takeIf { it.startsWith("http") } ?: ""

    val stock: Int get() = stockNumber.filter { it.isDigit() }.toIntOrNull() ?: 0

    /** 阶梯价第一档（即单颗价） */
    val unitPrice: Double get() = priceList.firstOrNull { it.startNumber <= 1 }?.price
        ?: priceList.minByOrNull { it.startNumber }?.price
        ?: 0.0

    /**
     * 结构化参数。立创把关键参数压成分号分隔的 `键:值;` 串，
     * 例如 "CPU内核:ARM Cortex-M3;CPU最大主频:72MHz;CPU位数:32 Bit;"
     */
    val specs: Map<String, String>
        get() = (deviceInfo?.description ?: "")
            .split(';')
            .mapNotNull { seg ->
                val i = seg.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                val k = seg.substring(0, i).trim()
                val v = seg.substring(i + 1).trim()
                if (k.isEmpty() || v.isEmpty()) null else k to v
            }
            .toMap()
}

@Serializable
data class LcscPrice(
    val startNumber: Int = 0,
    val endNumber: Int = 0,
    val price: Double = 0.0
)

@Serializable
data class LcscDeviceInfo(
    val description: String? = null,
    val uuid: String? = null
)

@Serializable
data class LcscResponse(
    val code: Int = 0,
    val msg: String? = null,
    val result: LcscResult? = null,
    val ok: Boolean = false
)

@Serializable
data class LcscResult(
    val total: Long = 0,
    val productList: List<LcscProduct> = emptyList()
)

/**
 * 链接构造。这里是整个方案最容易出错的地方，四个候选格式中只有一个可用：
 *
 * | 格式 | 实测结果 |
 * |---|---|
 * | `item.szlcsc.com/{id}.html` | ✅ 200，title 含型号与立创号 |
 * | `item.szlcsc.com/{C编号}.html` | ❌ 跳 404 页，但 **HTTP 仍是 200**（软 404） |
 * | `www.szlcsc.com/p/{C编号}.html` | ❌ 404 |
 * | `so.szlcsc.com/global.html?k={C编号}` | ⚠️ 203，瑞数 WAF 挑战页（浏览器可开，程序不可） |
 *
 * 所以链接必须用接口返回的数字 `id`，不能用立创号拼。立创号只作业务标识。
 */
object LcscLink {
    const val DATASHEET_HOST = "https://atta.szlcsc.com"

    /** 商品详情页，id 来自搜索结果的 `id` 字段 */
    fun product(productId: Long): String = "https://item.szlcsc.com/$productId.html"

    /**
     * 只有立创号、没有数字 id 时的兜底：
     * 走搜索页跳转。浏览器里可正常打开并落到该商品，但程序抓取会撞 WAF，
     * 所以只用于"点给人看"，不用于自动解析。
     */
    fun searchByCode(code: String): String =
        "https://so.szlcsc.com/global.html?k=${java.net.URLEncoder.encode(code, "UTF-8")}"

    /** 优先精确商品页，退化为搜索页 */
    fun best(productId: Long, code: String): String =
        if (productId > 0) product(productId) else searchByCode(code)

    /** 数据手册；pdfFileUrl 是相对路径，需拼 atta 域 */
    fun datasheet(pdfPath: String?): String? {
        val p = pdfPath?.trim().orEmpty()
        if (p.isEmpty()) return null
        return if (p.startsWith("http")) p else DATASHEET_HOST + (if (p.startsWith("/")) p else "/$p")
    }

    /**
     * 软 404 判定：立创的 404 页 HTTP 状态码是 200，正文含「您要访问的页面没有找到」。
     * 只信状态码会把错误链接当成可用链接，这是实测踩到的坑。
     */
    fun looksLikeSoft404(html: String): Boolean =
        html.contains("您要访问的页面没有找到") || html.contains("404.html")
}

/** 归一化后的搜索结果，UI 直接消费这个 */
data class LcscHit(
    val lcscCode: String,
    val mpn: String,
    val packageName: String,
    val brand: String,
    val category: String,
    val title: String,
    val stock: Int,
    val unitPrice: Double,
    val specs: Map<String, String>,
    val productId: Long,
    val productUrl: String,
    val datasheetUrl: String?,
    val imageUrl: String,
    val score: Int,
    /** 命中方式：C编号 / 型号精确 / 型号前缀 / 模糊，用于界面提示可信度 */
    val matchKind: MatchKind
) {
    enum class MatchKind { CODE, EXACT, PREFIX, FUZZY }
}

/** JSON 解析入口，宽松模式 */
internal val lcscJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
