package com.atun.componentvault.enrich

import com.atun.componentvault.data.Domains
import com.atun.componentvault.data.FilamentKeys
import com.atun.componentvault.data.fmtQty

/**
 * 非电子物料的解析器：五金标准件 + 3D 打印耗材。
 *
 * 为什么要有这一层：立创的检索是**关键词模糊匹配**，对规范名极准、对口语极不准。
 * 实测（2026-10-20）：
 *  - 搜「螺丝」→ 20,793 条，前几条是理线器和插拔式接线端子（完全跑偏）
 *  - 搜「M3螺丝」→ 28,051 条，同样跑偏
 *  - 搜「DIN912 M3」→ 30 条，**全部**是内六角圆柱头螺钉（100% 命中）
 *  - 搜「铜柱」→ 1,603 条，命中单头六角铜螺柱
 *
 * 所以用户敲 `M3*8` 时不能直接拿它去查，必须先翻译成 `DIN912 M3×8`，
 * 再把检索词裁成 `DIN912 M3`（带长度会漏掉不同写法）。
 *
 * 3D 打印耗材是另一个极端：立创 EDA 元件库根本不收录（搜「PLA 1.75mm」返回的是
 * 固态继电器和 LED 数码管），所以耗材纯本地管理，不做联网补全。
 */
object MaterialParser {

    /** 一条「已经翻译好、可以直接入库」的草稿 */
    data class Draft(
        val domain: String,
        val mpn: String,
        val name: String,
        val category: String,
        val packageName: String,
        val spec: String,
        val brand: String,
        val params: Map<String, String>,
        val tags: List<String>,
        val summary: String,
        val unit: String,
        /** 给立创的检索词。空串表示这一类立创不收录，别去查 */
        val searchKeyword: String,
        /** 解析依据，显示在界面上让用户知道是怎么来的 */
        val basis: String
    )

    // ---------------------------------------------------------------- 五金

    /** 螺纹 + 长度：M3*8 / M3x8 / M3×8 / M3X8 / M3-0.5X8 / 3*8 */
    private val THREAD_LEN = Regex(
        """(?i)(?:^|\s)(M\s*\d+(?:\.\d+)?)\s*(?:-\s*\d+(?:\.\d+)?\s*)?[*x×X]\s*(\d+(?:\.\d+)?(?:\s*\+\s*\d+(?:\.\d+)?)?)"""
    )
    /**
     * 只有螺纹：M3 / M4 / M2.5，也包括紧跟中文的写法（M3螺母、M3平垫）。
     * 结尾用负向断言而不是「空白或行尾」，否则 "M3螺母" 会匹配不上 ——
     * 因为 M3 后面跟的是全角汉字，不是空格。
     */
    private val THREAD_ONLY = Regex("""(?i)(?:^|[\s（(])(M\s*\d+(?:\.\d+)?)(?![\d.])""")

    private val KIND_WORDS = listOf(
        "螺钉", "螺丝", "螺栓", "螺母", "螺帽", "垫圈", "平垫", "弹垫", "铜柱", "螺柱",
        "支柱", "隔离柱", "轴承", "销", "卡簧", "弹簧", "型材", "角码"
    )

    private val MATERIALS = listOf(
        "304", "316", "12.9", "8.8", "10.9", "黄铜", "不锈钢", "碳钢", "铝合金", "铝", "尼龙", "钛"
    )

    private val HEADS = listOf(
        "内六角圆柱头", "内六角沉头", "内六角盘头", "十字盘头", "十字沉头",
        "一字", "圆头", "半圆头", "六角头", "沉头", "盘头"
    )

    /**
     * 解析入口。返回 null 表示「不是五金也不是耗材」，调用方应退回电子件流程。
     */
    fun parse(input: String): Draft? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        filament(raw)?.let { return it }
        return fastener(raw)
    }

    private fun fastener(raw: String): Draft? {
        val tl = THREAD_LEN.find(raw)
        val threadOnly = THREAD_ONLY.find(raw)
        val hasKind = KIND_WORDS.any { raw.contains(it) }
        val thread = (tl?.groupValues?.get(1) ?: threadOnly?.groupValues?.get(1))
            ?.replace(" ", "")
            ?.uppercase()
        val length = tl?.groupValues?.get(2)?.replace(" ", "")

        // 既没有螺纹也没有品类词 → 不是五金
        if (thread == null && !hasKind) return null
        if (thread == null) return null

        val kind = when {
            raw.contains("螺母") || raw.contains("螺帽") -> "螺母"
            raw.contains("铜柱") || raw.contains("螺柱") || raw.contains("支柱") || raw.contains("隔离柱") -> "铜柱"
            raw.contains("垫圈") || raw.contains("平垫") || raw.contains("弹垫") -> "垫圈"
            raw.contains("轴承") -> "轴承"
            raw.contains("销") -> "销"
            raw.contains("卡簧") -> "卡簧"
            raw.contains("弹簧") -> "弹簧"
            raw.contains("型材") -> "型材"
            raw.contains("角码") -> "角码"
            else -> "螺钉"
        }

        val material = MATERIALS.firstOrNull { raw.contains(it) } ?: "304 不锈钢"
        val head = HEADS.firstOrNull { raw.contains(it) } ?: when (kind) {
            "螺钉" -> "内六角圆柱头"
            "螺母" -> "六角"
            "铜柱" -> "单头六角"
            else -> ""
        }
        val standard = when {
            raw.contains("DIN912") || (kind == "螺钉" && head == "内六角圆柱头") -> "DIN912"
            raw.contains("DIN7991") -> "DIN7991"
            raw.contains("DIN7985") -> "DIN7985"
            kind == "螺母" -> "GB6170"
            kind == "垫圈" -> "GB97"
            else -> ""
        }

        val sizeLabel = when (kind) {
            "螺钉" -> if (length != null) "$thread×$length" else thread
            "铜柱" -> if (length != null) "$thread×$length" else thread
            "螺母" -> thread
            "垫圈" -> thread
            else -> if (length != null) "$thread×$length" else thread
        }

        // 规范名：能直接当型号用，也是给人看的主标题
        val canonical = listOf(standard, sizeLabel).filter { it.isNotBlank() }.joinToString(" ")
        val displayName = when (kind) {
            "螺钉" -> "${head}螺钉".trim()
            "螺母" -> "${if (head == "六角") "六角" else ""}螺母"
            "垫圈" -> "平垫圈"
            "铜柱" -> "单头六角铜螺柱"
            else -> kind
        }

        val params = LinkedHashMap<String, String>()
        params["螺纹"] = thread
        if (length != null) params["长度"] = "${length}mm"
        if (kind == "螺钉" || kind == "铜柱") params["头型"] = head
        if (standard.isNotBlank()) params["标准"] = standard
        params["材质"] = material

        // 检索词：实测「DIN912 M3」30 条全对；「M3螺丝」2.8 万条全错
        val keyword = when (kind) {
            "螺钉" -> if (standard.isNotBlank()) "$standard $thread" else "$thread 螺钉"
            "螺母" -> "$thread 螺母"
            "垫圈" -> "$thread 垫片"
            "铜柱" -> "铜柱 $thread"
            "轴承" -> "$thread 轴承"
            else -> "$thread $kind"
        }

        val specText = buildList {
            add("螺纹 $thread")
            if (length != null) add("长 ${length}mm")
            if (head.isNotBlank()) add(head)
            add(material)
        }.joinToString(" · ")

        return Draft(
            domain = Domains.HARDWARE,
            mpn = canonical,
            name = displayName,
            category = kind,
            packageName = sizeLabel,
            spec = specText,
            brand = "",
            params = params,
            tags = listOfNotNull(
                if (kind == "螺钉" || kind == "螺母") "紧固" else null,
                "五金", thread
            ),
            summary = "$displayName $sizeLabel，$material" + if (standard.isNotBlank()) "，$standard" else "",
            unit = "个",
            searchKeyword = keyword,
            basis = "按标准件规则解析：$thread" + (length?.let { " × $it" } ?: "") + " → $keyword"
        )
    }

    // ---------------------------------------------------------------- 3D 耗材

    // 材料表与颜色表统一由 FilamentPresets 提供：预设里加一种材料，录入解析自动跟上，
    // 不会出现「预设里有 PETG-CF，解析器却只认到 PETG」这种两套名单不一致的问题。
    private fun filament(raw: String): Draft? {
        val upper = raw.uppercase()
        val material = FilamentPresets.guessMaterial(raw) ?: return null
        // 只写 PLA 也要能识别，但要避免「PLA191」这类型号误判
        if (material.name == "PLA" && Regex("PLA\\d").containsMatchIn(upper)) return null

        val diameterMm = when {
            raw.contains("2.85") -> 2.85
            raw.contains("3.0") -> 3.0
            else -> 1.75
        }
        val diameter = fmtQty(diameterMm)
        // 走 guessColor（长名优先），否则「浅绿」「米白」会被认成「绿」「白」
        val color = FilamentPresets.guessColor(raw)?.name ?: "未标注"
        val netWeight = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:kg|KG|Kg)").find(raw)?.let {
            (it.groupValues[1].toDoubleOrNull() ?: 1.0) * 1000.0
        } ?: Regex("(\\d{3,4})\\s*(?:g|G|克)").find(raw)?.let {
            it.groupValues[1].toDoubleOrNull()
        } ?: 1000.0

        val mpn = "${material.name} $diameter $color"
        // 预设的密度与温度直接带进参数：录完就能算剩余米数，不用再手填
        val params = linkedMapOf(
            FilamentKeys.MATERIAL to material.name,
            FilamentKeys.DIAMETER to diameter,
            FilamentKeys.COLOR to color,
            FilamentKeys.NET_WEIGHT to netWeight.toInt().toString(),
            FilamentKeys.DENSITY to material.density.toString(),
            FilamentKeys.NOZZLE to material.nozzle,
            FilamentKeys.BED to material.bed
        )
        FilamentPresets.color(color)?.let { params[FilamentKeys.COLOR_HEX] = it.hex }

        return Draft(
            domain = Domains.FILAMENT,
            mpn = mpn,
            name = "${material.name} 线材",
            category = material.name,
            packageName = "${diameter}mm",
            spec = "直径 $diameter mm · 净重 ${(netWeight / 1000.0).let { if (it == 1.0) "1kg" else "${it}kg" }} · $color",
            brand = "",
            params = params,
            tags = listOfNotNull("3D打印", material.name, if (color != "未标注") "$color 色" else null),
            summary = "${material.name} 线材 $diameter mm $color，净重 ${netWeight.toInt()}g",
            unit = "g",
            // 实测：立创 EDA 库不收录耗材，检索「PLA 1.75mm」返回固态继电器，纯属噪声
            searchKeyword = "",
            basis = "按 3D 耗材规则解析：${material.name} / $diameter / $color → 按克管理"
        )
    }
}
