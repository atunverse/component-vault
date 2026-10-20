package com.atun.componentvault.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 物料域。v0.3.0 引入，用来把「电子元器件」泛化为「DIY 物料」。
 *
 * 为什么不给五金/耗材另建表：出入库、流水、仓位、局域网、BOM 比对、搜索这六套逻辑
 * 对螺丝和电阻完全一致，另建表等于把它们复制一遍，后续每加一个品类再复制一次。
 * 真正的差异只有两点 —— 计量单位（个/支/克/把）和补全数据源，各用一个字段解决。
 */
object Domains {
    /** 电子元器件：走立创 EDA 元件库 */
    const val ELECTRONICS = "electronics"
    /** 五金紧固件：走本地标准件规范化 + 立创检索 */
    const val HARDWARE = "hardware"
    /** 工具量具：手工填，默认关低库存预警 */
    const val TOOL = "tool"
    /** 3D 打印耗材：立创不收录，纯本地管理，按克计 */
    const val FILAMENT = "filament"
    /** 其他任意物品：完全手工 */
    const val OTHER = "other"

    val ALL = listOf(ELECTRONICS, HARDWARE, TOOL, FILAMENT, OTHER)

    fun label(domain: String): String = when (domain) {
        ELECTRONICS -> "电子"
        HARDWARE -> "五金"
        TOOL -> "工具"
        FILAMENT -> "3D 耗材"
        OTHER -> "其他"
        else -> domain
    }

    /** 各物料域的默认可选单位，录入时给快捷 Chip */
    fun units(domain: String): List<String> = when (domain) {
        FILAMENT -> listOf("g", "kg", "卷")
        TOOL -> listOf("把", "套", "个")
        HARDWARE -> listOf("个", "支", "包", "盒", "米")
        else -> listOf("个", "片", "只", "盘", "米", "卷")
    }

    fun defaultUnit(domain: String): String = when (domain) {
        FILAMENT -> "g"
        TOOL -> "把"
        HARDWARE -> "个"
        else -> "个"
    }
}

/** 数量显示：整数不显示小数点，小数最多两位 */
fun fmtQty(v: Double): String {
    if (abs(v - v.roundToLong()) < 0.005) return v.roundToLong().toString()
    val s = String.format("%.2f", v)
    return s.trimEnd('0').trimEnd('.')
}

/**
 * 库存器件。型号允许为空 —— 老库存/散料常常只有内部编号或丝印，
 * 强制型号必填会让用户放弃录入，这是库存类 App 最常见的失败点。
 */
@Entity(
    tableName = "part",
    indices = [
        Index(value = ["internalCode"], unique = true),
        Index(value = ["mpn"]),
        Index(value = ["category"]),
        Index(value = ["packageName"]),
        Index(value = ["domain"])
    ]
)
data class Part(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 内部编号，形如 CV-000123，永远不为空 */
    val internalCode: String,
    /** 型号/丝印/标准件规范名，可空 */
    val mpn: String = "",
    /** 名称，如「贴片电阻」「内六角圆柱头螺钉」 */
    val name: String = "",
    /** 分类，如「电阻」「螺钉」「PLA」 */
    val category: String = "",
    /** 物料域，见 [Domains] */
    val domain: String = Domains.ELECTRONICS,
    /** 封装，如「0603 (1608)」；五金用规格串，如「M3×8」 */
    val packageName: String = "",
    /** 规格补充，五金/耗材用：螺纹 M3 · 长 8mm · 304 / 直径 1.75mm · 净重 1kg */
    val spec: String = "",
    /** 品牌 / 材质 / 供应商 */
    val brand: String = "",
    /** 立创商城元件编号，如 C8734。业务标识，人读用 */
    val lcscCode: String = "",
    /**
     * 立创商城商品页数字 ID（搜索接口的 id 字段）。
     * 电子件是 https://item.szlcsc.com/{id}.html ；
     * 五金件同域名会 308 跳到 https://item.szlcsc.com/mro/{id}.html ，
     * 统一存这个数字 id 即可，浏览器会自己跟跳转。
     */
    val lcscProductId: Long = 0,
    /** 数据手册地址 */
    val datasheetUrl: String = "",
    /** 动态参数，JSON 对象字符串，key 由分类的 ParamDef 决定 */
    val paramsJson: String = "{}",
    /** 功能标签，逗号分隔，如「上拉,限流」 */
    val tags: String = "",
    /** 自动生成的一句话简介 */
    val summary: String = "",
    /**
     * 当前库存数量。用 Double 是因为耗材要记「3.5 米热缩管」「半卷焊锡」
     * 以及 3D 打印线材的「剩余 734g」。显示时整数不带小数点。
     */
    val qty: Double = 0.0,
    /** 计量单位：个 / 支 / 把 / g / 米 / 卷 */
    val unit: String = "个",
    /** 低库存阈值。工具类填 0 即关闭预警 */
    val lowThreshold: Double = 10.0,
    /** 仓位，如「A-03-2」「工具柜第 2 抽」 */
    val location: String = "",
    /** 参考单价（元），按 [unit] 计 */
    val price: Double = 0.0,
    val note: String = "",
    /** 本地图标文件名（相对 icons/ 目录），如 p12.jpg。空串 = 无图 */
    val iconPath: String = "",
    /** 图片来源：lcsc / photo / album */
    val iconSource: String = "",
    /** 立创原图 URL，用于重新下载与存量回填 */
    val iconUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isOut: Boolean get() = qty <= 0.0
    val isLow: Boolean get() = qty > 0.0 && qty <= lowThreshold
    val tagList: List<String> get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val qtyText: String get() = fmtQty(qty)
    val lowText: String get() = fmtQty(lowThreshold)
    /** 库存金额小计 */
    val amount: Double get() = qty * price
    val displayTitle: String get() = mpn.ifBlank { internalCode }
    /** 是否有立创商品页可直达 */
    val hasLcscProduct: Boolean get() = lcscProductId > 0
    val hasLcscAny: Boolean get() = lcscProductId > 0 || lcscCode.isNotBlank()
    val hasIcon: Boolean get() = iconPath.isNotBlank()

    /** 3D 耗材剩余比例，净重未知时返回 null */
    fun filamentPercent(netWeightG: Double?): Float? {
        val net = netWeightG ?: return null
        if (net <= 0.0) return null
        return (qty / net).coerceIn(0.0, 1.0).toFloat()
    }
}

/**
 * 出入库流水。before / after / delta 三个值同时记录，
 * 是为了在局域网多端并发写入时能审计出「谁把数量改错了」，也能做幂等重放。
 */
@Entity(
    tableName = "txn",
    indices = [Index(value = ["partId"]), Index(value = ["idempotencyKey"], unique = false)]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val partId: Long,
    /** IN 入库 / OUT 出库 / ADJ 盘点调整 / CREATE 新建 / EDIT 资料修改 */
    val type: String,
    val delta: Double,
    val before: Double,
    val after: Double,
    /** 关联单据，如 BOM 名称、工单号 */
    val ref: String = "",
    val note: String = "",
    /** 幂等键：局域网重试时避免重复扣减 */
    val idempotencyKey: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val typeLabel: String
        get() = when (type) {
            "IN" -> "入库"
            "OUT" -> "出库"
            "ADJ" -> "盘点"
            "CREATE" -> "建档"
            "EDIT" -> "改资料"
            else -> type
        }

    /** 带符号的数量文本，EDIT 没有数量变化 */
    val deltaText: String
        get() = when {
            type == "EDIT" -> "—"
            delta > 0 -> "+" + fmtQty(delta)
            else -> fmtQty(delta)
        }
}

/**
 * 分类的动态参数定义。新增一个分类只需要插数据，不需要改代码、不用发版。
 * 这是整套方案能覆盖「电阻 / 电容 / MCU / 螺钉 / PLA」等差异极大的品类的前提。
 */
@Entity(tableName = "param_def", primaryKeys = ["category", "key"])
data class ParamDef(
    val category: String,
    val key: String,
    val label: String,
    val unit: String = "",
    /** text / number / enum */
    val type: String = "text",
    val order: Int = 0
)

/** 界面用的库存概览统计 */
data class StockStats(
    val totalKinds: Int = 0,
    val totalQty: Long = 0,
    val lowKinds: Int = 0,
    val outKinds: Int = 0
)
