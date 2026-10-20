package com.atun.componentvault.data

import com.atun.componentvault.enrich.FilamentPresets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 3D 耗材的参数键名。
 *
 * 为什么放在 paramsJson 里而不是给 part 表加十个列：
 * 这套应用的整体设计是「分类差异靠 ParamDef 数据驱动」——电阻要阻值精度功率、
 * 螺钉要螺纹长度头型、耗材要密度温度。给耗材单独加列，等于在实体上开一个特例口子，
 * 后面每来一个新品类都得改表、改迁移、改局域网序列化。
 * 耗材只是参数最多的一类，用同一套机制即可，且升级不需要数据库迁移。
 */
object FilamentKeys {
    const val MATERIAL = "材质"
    const val DIAMETER = "直径"
    const val COLOR = "颜色"
    const val COLOR_HEX = "颜色值"
    const val NET_WEIGHT = "净重"
    const val SPOOL_WEIGHT = "空盘重"
    const val DENSITY = "密度"
    const val NOZZLE = "喷嘴温度"
    const val BED = "热床温度"
    const val LOT = "批次号"
    const val OPENED_AT = "开封日期"
    const val TRAITS = "特性"
}

/**
 * 耗材信息的读取与推算。纯函数，不碰 Compose 也不碰数据库，可 JVM 单测。
 *
 * 所有取值都有「参数优先、预设兜底」两级：用户没填密度时，按材料名去预设里拿，
 * 这样刚录进来、只选了「PETG」的盘也能算剩余长度。
 */
object FilamentInfo {

    private val NUM = Regex("""\d+(?:\.\d+)?""")

    /** 从「1000g」「1.75mm」「200-220」这类文本里取第一个数 */
    fun firstNumber(text: String?): Double? =
        text?.let { NUM.find(it)?.value?.toDoubleOrNull() }

    fun params(p: Part): Map<String, String> = runCatching {
        Json.parseToJsonElement(p.paramsJson).jsonObject.entries
            .associate { it.key to it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())

    fun get(p: Part, key: String): String = params(p)[key]?.trim().orEmpty()

    // ------------------------------------------------------------ 基础字段

    /** 材料类型。老数据里材料写在 category 上，这里兼容两种存法 */
    fun material(p: Part): String =
        get(p, FilamentKeys.MATERIAL).ifBlank { p.category }.ifBlank { "未知" }

    fun diameterMm(p: Part): Double? =
        firstNumber(get(p, FilamentKeys.DIAMETER))
            ?: firstNumber(p.packageName)
            ?: 1.75

    fun netWeight(p: Part): Double? = firstNumber(get(p, FilamentKeys.NET_WEIGHT))

    fun spoolWeight(p: Part): Double? = firstNumber(get(p, FilamentKeys.SPOOL_WEIGHT))

    fun density(p: Part): Double? =
        firstNumber(get(p, FilamentKeys.DENSITY)) ?: FilamentPresets.material(material(p))?.density

    fun nozzle(p: Part): String =
        get(p, FilamentKeys.NOZZLE).ifBlank { FilamentPresets.material(material(p))?.nozzle.orEmpty() }

    fun bed(p: Part): String =
        get(p, FilamentKeys.BED).ifBlank { FilamentPresets.material(material(p))?.bed.orEmpty() }

    fun colorName(p: Part): String = get(p, FilamentKeys.COLOR)

    /**
     * 颜色色值列表。单色返回 1 项，渐变耗材返回 2 项以上。
     *
     * 渐变不新增字段：「颜色值」里用逗号串联多个色值即可（`#E53935,#FB8C00,#FDD835`），
     * 这与 Spoolman 的 `multi_color_hexes` 存法一致；单色数据完全兼容。
     */
    fun colorHexes(p: Part): List<String> {
        val explicit = splitHexes(get(p, FilamentKeys.COLOR_HEX))
        if (explicit.isNotEmpty()) return explicit
        return splitHexes(FilamentPresets.color(colorName(p))?.hex)
    }

    /** 颜色色值（完整串，渐变时为逗号分隔的多值）。没填时按颜色名去预设里查 */
    fun colorHex(p: Part): String = colorHexes(p).joinToString(",")

    /** 是否渐变色 */
    fun gradient(p: Part): Boolean = colorHexes(p).size > 1

    /**
     * 图标上显示的短材料名。图标只有 46dp，放不下「哑光 PLA」「PA（尼龙）」这种全称，
     * 去掉括注与修饰前缀，只留真正区分的那一段。
     */
    fun shortMaterial(p: Part): String {
        val compact = material(p)
            .replace(Regex("（[^）]*）|\\([^)]*\\)"), "")
            .trim()
            .substringAfterLast(' ')
            .trim()
        return compact.ifBlank { "耗材" }.take(6)
    }

    private val HEX_PART = Regex("""^#?([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$""")

    /**
     * sRGB 相对亮度（0 = 黑，1 = 白）。公式与 Compose 的 `Color.luminance()` 一致，
     * 但这里是纯 Kotlin —— 图标要在 JVM 单测里也能验证「深色料配白字」这件事。
     */
    fun hexLuminance(hex: String): Float? {
        val body = splitHexes(hex).firstOrNull()?.removePrefix("#") ?: return null
        val rgb = if (body.length == 8) body.substring(2) else body
        if (rgb.length != 6) return null
        fun channel(i: Int): Double {
            val v = (rgb.substring(i, i + 2).toIntOrNull(16) ?: return 0.0) / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return (0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)).toFloat()
    }

    /**
     * 色块上该用白字还是深字。
     *
     * 取整段渐变的**平均**亮度：只看一端会在另一端糊掉。
     * 阈值 0.45 是偏保守的取法 —— 宁可早一点换白字，也别在中间灰上黑白都不清。
     */
    fun preferLightText(hexes: List<String>): Boolean {
        val ls = hexes.mapNotNull { hexLuminance(it) }
        if (ls.isEmpty()) return false
        return ls.average() < 0.45
    }

    /**
     * 解析颜色值文本。支持 `#1A1A1A`、`1a1a1a`、`#1A1A1A,#F2F2F2`、`#1A1A1A #F2F2F2`
     * 等写法（中文逗号、顿号也吃），非法片段直接丢弃而不是整串失败。
     *
     * 纯函数、不碰 android.graphics：图标与单测共用同一套解析。
     */
    fun splitHexes(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', '，', ';', '；', '|', '、', ' ')
            .map { it.trim() }
            .mapNotNull { part ->
                val body = HEX_PART.find(part)?.groupValues?.get(1) ?: return@mapNotNull null
                "#" + body.uppercase()
            }
    }

    fun lot(p: Part): String = get(p, FilamentKeys.LOT)

    /** 开封日期，ISO 文本（yyyy-MM-dd），未标记返回空串 */
    fun openedAt(p: Part): String = get(p, FilamentKeys.OPENED_AT)

    /** 已开封天数；未标记开封返回 null */
    fun openedDays(p: Part, today: LocalDate = LocalDate.now()): Long? {
        val raw = openedAt(p)
        if (raw.isBlank()) return null
        val d = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return null
        return ChronoUnit.DAYS.between(d, today).coerceAtLeast(0)
    }

    // ------------------------------------------------------------ 推算

    /** 剩余比例 0~1。净重未知时返回 null，界面就不画进度条 */
    fun percent(p: Part): Float? {
        val net = netWeight(p) ?: return null
        if (net <= 0) return null
        val gross = p.qty
        return (gross / net).coerceIn(0.0, 1.0).toFloat()
    }

    /** 剩余长度（米）。这是 Spoolman 没有、但盘算「还能打多少个件」时最直观的数 */
    fun remainingMeters(p: Part): Double? {
        val d = density(p) ?: return null
        val dia = diameterMm(p) ?: return null
        return FilamentPresets.lengthMeters(p.qty, d, dia)
    }

    /** 列表副标题：材料 · 颜色 · 线径 · 剩余/净重 */
    fun subtitle(p: Part): String {
        val parts = mutableListOf<String>()
        parts += material(p)
        colorName(p).takeIf { it.isNotBlank() }?.let { parts += it }
        diameterMm(p)?.let { parts += "${fmtQty(it)}mm" }
        val net = netWeight(p)
        parts += if (net != null && net > 0) "${fmtQty(p.qty)}/${fmtQty(net)}g" else "${fmtQty(p.qty)}g"
        return parts.joinToString(" · ")
    }

    /**
     * 规范化材料名：预设里有的用预设名，没有的保留原样但去空格。
     * 用于把「petg」「PETG 1.75」这类输入收敛成「PETG」。
     */
    fun normalizeMaterial(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return FilamentPresets.guessMaterial(t)?.name ?: t
    }
}
