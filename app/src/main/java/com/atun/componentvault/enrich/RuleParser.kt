package com.atun.componentvault.enrich

import java.util.Locale
import kotlin.math.pow

/**
 * 型号规则解析器。
 *
 * 这是「断网也能补全」的兜底能力：不需要任何 API，只凭型号字面就能推导出
 * 值、封装、分类、功能标签。经验上，常见无源器件（电阻/电容/电感）加上
 * 一批高频有源型号，覆盖率能达到六成左右 —— 足够让录入流程不至于卡死。
 *
 * 解析出来的结果是「推测值」，界面上必须用虚线框标注，让用户知道哪几项
 * 是机器猜的、需要复核。这是准确性的底线。
 */
object RuleParser {

    /** 单条补全结果。confidence 用于排序与 UI 上的置信度提示 */
    data class Guess(
        val name: String,
        val category: String,
        val packageName: String,
        val params: Map<String, String>,
        val tags: List<String>,
        val summary: String,
        val confidence: Double,
        val matchedBy: String
    )

    private class Spec(
        val name: String,
        val category: String,
        val pkg: String,
        val params: Map<String, String>,
        val tags: List<String>
    )

    // ---------------------------------------------------------------- 已知型号

    private val KNOWN: Map<String, Spec> = mapOf(
        // 二极管
        "SS34" to Spec(
            "肖特基二极管", "二极管", "SMA (DO-214AC)",
            linkedMapOf("类型" to "肖特基", "反向耐压" to "40V", "正向电流" to "3A"),
            listOf("整流", "防反接", "续流", "开关电源")
        ),
        "SS14" to Spec(
            "肖特基二极管", "二极管", "SMA (DO-214AC)",
            linkedMapOf("类型" to "肖特基", "反向耐压" to "40V", "正向电流" to "1A"),
            listOf("整流", "防反接", "续流")
        ),
        "SS54" to Spec(
            "肖特基二极管", "二极管", "SMA (DO-214AC)",
            linkedMapOf("类型" to "肖特基", "反向耐压" to "40V", "正向电流" to "5A"),
            listOf("整流", "防反接", "大电流")
        ),
        "1N4148" to Spec(
            "开关二极管", "二极管", "SOD-123",
            linkedMapOf("类型" to "小信号开关", "反向耐压" to "100V", "正向电流" to "200mA"),
            listOf("开关", "信号", "限幅")
        ),
        "1N4007" to Spec(
            "整流二极管", "二极管", "DO-41",
            linkedMapOf("类型" to "普通整流", "反向耐压" to "1000V", "正向电流" to "1A"),
            listOf("整流", "防反接")
        ),
        "1N5819" to Spec(
            "肖特基二极管", "二极管", "DO-41",
            linkedMapOf("类型" to "肖特基", "反向耐压" to "40V", "正向电流" to "1A"),
            listOf("整流", "续流")
        ),
        "BAT54C" to Spec(
            "共阴双肖特基", "二极管", "SOT-23",
            linkedMapOf("类型" to "肖特基", "反向耐压" to "30V", "正向电流" to "200mA"),
            listOf("钳位", "信号", "双管")
        ),
        // MOSFET
        "AO3400" to Spec(
            "N沟道MOSFET", "晶体管", "SOT-23",
            linkedMapOf("沟道" to "N", "Vds" to "30V", "Id" to "5.7A"),
            listOf("开关", "驱动", "电平转换")
        ),
        "AO3401" to Spec(
            "P沟道MOSFET", "晶体管", "SOT-23",
            linkedMapOf("沟道" to "P", "Vds" to "30V", "Id" to "4A"),
            listOf("开关", "高边驱动", "防反接")
        ),
        "2N7002" to Spec(
            "N沟道MOSFET", "晶体管", "SOT-23",
            linkedMapOf("沟道" to "N", "Vds" to "60V", "Id" to "300mA"),
            listOf("开关", "电平转换", "小信号")
        ),
        "IRLZ44N" to Spec(
            "N沟道MOSFET", "晶体管", "TO-220",
            linkedMapOf("沟道" to "N", "Vds" to "55V", "Id" to "47A", "驱动电平" to "逻辑电平"),
            listOf("大电流", "电机驱动", "开关")
        ),
        // 三极管
        "S8050" to Spec(
            "NPN三极管", "晶体管", "SOT-23",
            linkedMapOf("极性" to "NPN", "Vceo" to "25V", "Ic" to "500mA"),
            listOf("放大", "开关", "驱动")
        ),
        "S8550" to Spec(
            "PNP三极管", "晶体管", "SOT-23",
            linkedMapOf("极性" to "PNP", "Vceo" to "25V", "Ic" to "500mA"),
            listOf("放大", "开关", "驱动")
        ),
        // 电源
        "AMS1117-3.3" to Spec(
            "低压差线性稳压器", "电源管理", "SOT-223",
            linkedMapOf("类型" to "LDO", "输出电压" to "3.3V", "输出电流" to "1A", "压差" to "1.3V"),
            listOf("稳压", "LDO", "3.3V")
        ),
        "AMS1117-5.0" to Spec(
            "低压差线性稳压器", "电源管理", "SOT-223",
            linkedMapOf("类型" to "LDO", "输出电压" to "5.0V", "输出电流" to "1A", "压差" to "1.3V"),
            listOf("稳压", "LDO", "5V")
        ),
        "LM7805" to Spec(
            "线性稳压器", "电源管理", "TO-220",
            linkedMapOf("类型" to "线性稳压", "输出电压" to "5V", "输出电流" to "1.5A"),
            listOf("稳压", "5V")
        ),
        "LM2596S-ADJ" to Spec(
            "降压型开关稳压器", "电源管理", "TO-263",
            linkedMapOf("类型" to "Buck", "输入电压" to "4.5–40V", "输出电流" to "3A", "输出" to "可调"),
            listOf("降压", "DCDC", "大电流")
        ),
        "TPS5430DDA" to Spec(
            "降压型开关稳压器", "电源管理", "SOIC-8",
            linkedMapOf("类型" to "Buck", "输入电压" to "5.5–36V", "输出电流" to "3A"),
            listOf("降压", "DCDC")
        ),
        "XL4015" to Spec(
            "降压型开关稳压器", "电源管理", "TO-263",
            linkedMapOf("类型" to "Buck", "输入电压" to "8–36V", "输出电流" to "5A"),
            listOf("降压", "DCDC", "大电流")
        ),
        "MP1584EN" to Spec(
            "降压型开关稳压器", "电源管理", "SOP-8",
            linkedMapOf("类型" to "Buck", "输入电压" to "4.5–28V", "输出电流" to "3A"),
            listOf("降压", "DCDC", "小体积")
        ),
        // 模拟 / 逻辑
        "NE555" to Spec(
            "单路定时器", "集成电路", "SOP-8",
            linkedMapOf("功能" to "定时/振荡", "供电电压" to "4.5–16V", "输出电流" to "200mA"),
            listOf("定时", "振荡", "PWM")
        ),
        "LM358" to Spec(
            "双运放", "集成电路", "SOP-8",
            linkedMapOf("功能" to "通用运放", "供电电压" to "3–32V", "通道数" to "2"),
            listOf("运放", "信号调理", "比较")
        ),
        "LM324" to Spec(
            "四运放", "集成电路", "SOP-14",
            linkedMapOf("功能" to "通用运放", "供电电压" to "3–32V", "通道数" to "4"),
            listOf("运放", "信号调理")
        ),
        "74HC595" to Spec(
            "8位移位寄存器", "集成电路", "SOP-16",
            linkedMapOf("功能" to "串入并出", "供电电压" to "2–6V", "输出位数" to "8"),
            listOf("IO扩展", "数码管", "移位")
        ),
        "ULN2003" to Spec(
            "七路达林顿驱动", "集成电路", "SOP-16",
            linkedMapOf("功能" to "达林顿阵列", "输出电流" to "500mA/路", "通道数" to "7"),
            listOf("驱动", "继电器", "步进电机")
        ),
        // MCU / 接口
        "STM32F103C8T6" to Spec(
            "ARM Cortex-M3 微控制器", "微控制器", "LQFP-48",
            linkedMapOf("内核" to "Cortex-M3", "主频" to "72MHz", "Flash" to "64KB", "RAM" to "20KB", "供电" to "2.0–3.6V"),
            listOf("MCU", "最小系统", "通用控制")
        ),
        "STM32F103RCT6" to Spec(
            "ARM Cortex-M3 微控制器", "微控制器", "LQFP-64",
            linkedMapOf("内核" to "Cortex-M3", "主频" to "72MHz", "Flash" to "256KB", "RAM" to "48KB", "供电" to "2.0–3.6V"),
            listOf("MCU", "通用控制")
        ),
        "CH340G" to Spec(
            "USB转串口芯片", "接口芯片", "SOP-16",
            linkedMapOf("功能" to "USB-UART", "供电电压" to "3.3/5V", "最高波特率" to "2Mbps"),
            listOf("USB", "串口", "下载")
        ),
        "CH340C" to Spec(
            "USB转串口芯片", "接口芯片", "SOP-16",
            linkedMapOf("功能" to "USB-UART", "供电电压" to "3.3/5V", "内置晶振" to "是"),
            listOf("USB", "串口", "下载")
        ),
        "ESP32-WROOM-32" to Spec(
            "WiFi+蓝牙模组", "无线模组", "SMD-38",
            linkedMapOf("无线" to "WiFi 802.11 b/g/n + BT4.2", "内核" to "双核 Xtensa LX6", "主频" to "240MHz", "Flash" to "4MB"),
            listOf("WiFi", "蓝牙", "物联网")
        ),
        "ESP-12F" to Spec(
            "WiFi模组", "无线模组", "SMD-22",
            linkedMapOf("无线" to "WiFi 802.11 b/g/n", "内核" to "ESP8266", "Flash" to "4MB"),
            listOf("WiFi", "物联网")
        ),
        "ATmega328P-AU" to Spec(
            "8位AVR微控制器", "微控制器", "TQFP-32",
            linkedMapOf("内核" to "AVR", "主频" to "20MHz", "Flash" to "32KB", "RAM" to "2KB"),
            listOf("MCU", "Arduino")
        )
    )

    // ---------------------------------------------------------------- 封装识别

    private val CHIP_PKG = mapOf(
        "0201" to "0201 (0603)",
        "0402" to "0402 (1005)",
        "0603" to "0603 (1608)",
        "0805" to "0805 (2012)",
        "1206" to "1206 (3216)",
        "1210" to "1210 (3225)",
        "1812" to "1812 (4532)",
        "2010" to "2010 (5025)",
        "2512" to "2512 (6332)"
    )

    private val OTHER_PKG = listOf(
        "USB-C-16P", "TYPE-C-16P", "SOT-23-5", "SOT-23-3", "SOT-23", "SOT-223", "SOT-89", "SOT-323", "SOT-363",
        "SOD-123", "SOD-323", "SOD-523", "SOD-80",
        "DO-214AC", "DO-214AA", "DO-214AB", "DO-41", "DO-35",
        "TO-220", "TO-252", "TO-263", "TO-92", "TO-247",
        "SOP-8", "SOP-14", "SOP-16", "SOP-20", "SOIC-8", "SOIC-14", "SOIC-16",
        "TSSOP-16", "TSSOP-20", "TSSOP-24",
        "QFN-16", "QFN-32", "QFN-48", "LQFP-32", "LQFP-48", "LQFP-64", "LQFP-100",
        "TQFP-32", "TQFP-44", "QFP-44", "QFP-64",
        "DIP-8", "DIP-14", "DIP-16",
        "SMA", "SMB", "SMC"
    ).sortedByDescending { it.length }

    /** EIA-96 两位码对应的基值（E96 序列 100–976） */
    private val EIA96 = listOf(
        100, 102, 105, 107, 110, 113, 115, 118, 121, 124,
        127, 130, 133, 137, 140, 143, 147, 150, 154, 158,
        162, 165, 169, 174, 178, 182, 187, 191, 196, 200,
        205, 210, 215, 221, 226, 232, 237, 243, 249, 255,
        261, 267, 274, 280, 287, 294, 301, 309, 316, 324,
        332, 340, 348, 357, 365, 374, 383, 392, 402, 412,
        422, 432, 442, 453, 464, 475, 487, 499, 511, 523,
        536, 549, 562, 576, 590, 604, 619, 634, 649, 665,
        681, 698, 715, 732, 750, 768, 787, 806, 825, 845,
        866, 887, 909, 931, 953, 976
    )

    // ---------------------------------------------------------------- 对外入口

    /**
     * 解析一个输入串，返回候选列表（可能多个，比如纯数字码同时是电阻码和电容码）。
     * 第一个为默认推荐项。
     */
    fun parse(raw: String): List<Guess> {
        val text = raw.trim()
            .replace('，', ' ').replace(',', ' ')
            .replace('，', ' ')
        if (text.isEmpty()) return emptyList()
        val up = text.uppercase(Locale.US)
        val pkg = detectPackage(up)
        val tokens = up.split(Regex("\\s+")).filter { it.isNotBlank() }

        // 1) 已知型号：整串命中优先，其次任意 token 命中
        KNOWN[up]?.let { return listOf(it.toGuess(pkg, 0.95, "型号库")) }
        KNOWN[up.replace("-", "")]?.let { return listOf(it.toGuess(pkg, 0.95, "型号库")) }
        for (t in tokens) {
            KNOWN[t]?.let { return listOf(it.toGuess(pkg, 0.92, "型号库")) }
        }

        // 2) 带单位的显式值：10K / 100N / 0.1U / 4.7UH
        unitValue(up, pkg)?.let { return listOf(it) }

        // 3) 三位/四位码、R 记法、EIA-96
        val candidates = mutableListOf<Guess>()
        val code = tokens.firstOrNull { isCodeLike(it) } ?: up

        if (code.matches(Regex("^\\d{4}$"))) {
            resistorFromFourDigit(code)?.let { candidates += resistorGuess(it, pkg, "4位电阻码") }
        }
        if (code.matches(Regex("^\\d{3}$"))) {
            capacitorFromThreeDigit(code)?.let { candidates += capacitorGuess(it, pkg, "3位容码") }
            resistorFromThreeDigit(code)?.let { candidates += resistorGuess(it, pkg, "3位阻码") }
        }
        if (code.matches(Regex("^\\d{2}[ZYXRABCDEF]$"))) {
            eia96(code)?.let { candidates += resistorGuess(it, pkg, "EIA-96") }
        }
        if (code.contains('R') && code.matches(Regex("^\\d*R\\d*$"))) {
            resistorFromR(value = code)?.let { candidates += resistorGuess(it, pkg, "R记法") }
        }
        if (candidates.isNotEmpty()) return candidates

        // 4) 都认不出来，返回一个待补充的空壳，不阻断录入
        return listOf(
            Guess(
                name = text,
                category = "未分类",
                packageName = pkg ?: "",
                params = emptyMap(),
                tags = emptyList(),
                summary = "未识别型号，已按原文录入，请补充参数",
                confidence = 0.2,
                matchedBy = "原文"
            )
        )
    }

    // ---------------------------------------------------------------- 封装

    fun detectPackage(upper: String): String? {
        for ((k, v) in CHIP_PKG) {
            if (Regex("(?<![0-9])$k(?![0-9])").containsMatchIn(upper)) return v
        }
        for (p in OTHER_PKG) {
            if (upper.contains(p)) return p
        }
        // 常见别名
        if (upper.contains("0805")) return "0805 (2012)"
        return null
    }

    // ---------------------------------------------------------------- 码解析

    private fun isCodeLike(t: String): Boolean =
        t.matches(Regex("^\\d{3,4}$")) ||
            t.matches(Regex("^\\d{2}[ZYXRABCDEF]$")) ||
            t.matches(Regex("^\\d*R\\d*$"))

    private fun resistorFromThreeDigit(code: String): Double? {
        if (code == "000") return 0.0
        val base = code.substring(0, 2).toIntOrNull() ?: return null
        val mult = code[2].digitToIntOrNull() ?: return null
        if (base == 0) return null
        return base * 10.0.pow(mult)
    }

    private fun resistorFromFourDigit(code: String): Double? {
        if (code == "0000") return 0.0
        val base = code.substring(0, 3).toIntOrNull() ?: return null
        val mult = code[3].digitToIntOrNull() ?: return null
        if (base == 0) return null
        return base * 10.0.pow(mult)
    }

    private fun capacitorFromThreeDigit(code: String): Double? {
        val base = code.substring(0, 2).toIntOrNull() ?: return null
        val mult = code[2].digitToIntOrNull() ?: return null
        if (base == 0) return null
        // pF
        return base * 10.0.pow(mult)
    }

    private fun resistorFromR(value: String): Double? {
        val idx = value.indexOf('R')
        if (idx < 0) return null
        val intPart = value.substring(0, idx)
        val decPart = value.substring(idx + 1)
        val v = (if (intPart.isEmpty()) "0" else intPart) + "." + (if (decPart.isEmpty()) "0" else decPart)
        return v.toDoubleOrNull()
    }

    private fun eia96(code: String): Double? {
        val n = code.substring(0, 2).toIntOrNull() ?: return null
        val base = EIA96.getOrNull(n - 1) ?: return null
        return when (code[2]) {
            'Z' -> base * 0.001
            'Y' -> base * 0.01
            'X' -> base * 0.1
            'A' -> base * 1.0
            'B' -> base * 10.0
            'C' -> base * 100.0
            'D' -> base * 1000.0
            'E' -> base * 10000.0
            'F' -> base * 100000.0
            else -> null
        }
    }

    // ---------------------------------------------------------------- 带单位

    private fun unitValue(upper: String, pkg: String?): Guess? {
        Regex("^(\\d+(?:\\.\\d+)?)(K|M|R)$").find(upper)?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2]) {
                "K" -> 1e3
                "M" -> 1e6
                else -> 1.0
            }
            return resistorGuess(v * mult, pkg, "带单位")
        }
        Regex("^(\\d+(?:\\.\\d+)?)(P|N|U)$").find(upper)?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2]) {
                "P" -> 1.0
                "N" -> 1e3
                else -> 1e6
            }
            return capacitorGuess(v * mult, pkg, "带单位")
        }
        Regex("^(\\d+(?:\\.\\d+)?)UH$").find(upper)?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            return Guess(
                name = "功率电感",
                category = "电感",
                packageName = pkg ?: "0603 (1608)",
                params = linkedMapOf("电感量" to fmtHenry(v * 1e-6), "类型" to "功率电感"),
                tags = listOf("滤波", "储能", "DCDC"),
                summary = "${fmtHenry(v * 1e-6)} 功率电感，用于开关电源储能与滤波",
                confidence = 0.75,
                matchedBy = "带单位"
            )
        }
        return null
    }

    // ---------------------------------------------------------------- 构造结果

    private fun resistorGuess(ohm: Double, pkg: String?, by: String): Guess {
        val value = fmtOhm(ohm)
        return Guess(
            name = "贴片电阻",
            category = "电阻",
            packageName = pkg ?: "0603 (1608)",
            params = linkedMapOf(
                "阻值" to value,
                "精度" to if (pkg == null) "±1%（推测）" else "±1%",
                "功率" to powerOf(pkg)
            ),
            tags = guessResistorTags(ohm),
            summary = "$value 贴片电阻，${pkg ?: "0603"} 封装，常用于${guessResistorTags(ohm).take(2).joinToString("、")}",
            confidence = if (pkg == null) 0.7 else 0.85,
            matchedBy = by
        )
    }

    private fun capacitorGuess(pf: Double, pkg: String?, by: String): Guess {
        val value = fmtFarad(pf)
        return Guess(
            name = "贴片电容",
            category = "电容",
            packageName = pkg ?: defaultCapPkg(pf),
            params = linkedMapOf(
                "容值" to value,
                "耐压" to if (pf >= 1e6) "25V" else "50V",
                "介质" to if (pf >= 1e6) "X5R" else "X7R"
            ),
            tags = listOf("去耦", "滤波", "旁路"),
            summary = "$value 贴片电容，${pkg ?: defaultCapPkg(pf)} 封装，用于电源去耦与滤波",
            confidence = if (pkg == null) 0.65 else 0.8,
            matchedBy = by
        )
    }

    private fun Spec.toGuess(pkg: String?, conf: Double, by: String): Guess {
        val realPkg: String = pkg ?: this.pkg
        val p = LinkedHashMap(params)
        if (realPkg.isNotEmpty() && !p.containsKey("封装")) p["封装"] = realPkg
        val parts = mutableListOf(name)
        params.entries.take(3).forEach { parts += "${it.key} ${it.value}" }
        if (realPkg.isNotEmpty()) parts += realPkg
        return Guess(
            name = name,
            category = category,
            packageName = realPkg,
            params = p,
            tags = tags,
            summary = parts.joinToString("，") + "；" + tags.take(3).joinToString("、"),
            confidence = conf,
            matchedBy = by
        )
    }

    // ---------------------------------------------------------------- 格式化

    fun fmtOhm(v: Double): String = when {
        v == 0.0 -> "0Ω"
        v >= 1e6 -> trimNum(v / 1e6) + "MΩ"
        v >= 1e3 -> trimNum(v / 1e3) + "kΩ"
        else -> trimNum(v) + "Ω"
    }

    fun fmtFarad(pf: Double): String = when {
        pf >= 1e6 -> trimNum(pf / 1e6) + "µF"
        pf >= 1e3 -> trimNum(pf / 1e3) + "nF"
        else -> trimNum(pf) + "pF"
    }

    private fun fmtHenry(h: Double): String = when {
        h >= 1e-3 -> trimNum(h / 1e-3) + "mH"
        h >= 1e-6 -> trimNum(h / 1e-6) + "µH"
        else -> trimNum(h / 1e-9) + "nH"
    }

    private fun trimNum(d: Double): String {
        val s = String.format(Locale.US, "%.3f", d)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    private fun powerOf(pkg: String?): String = when (pkg) {
        "0201 (0603)", "0402 (1005)" -> "1/16W"
        "0603 (1608)" -> "1/10W"
        "0805 (2012)" -> "1/8W"
        "1206 (3216)", "1210 (3225)" -> "1/4W"
        "1812 (4532)", "2010 (5025)", "2512 (6332)" -> "1W"
        else -> "1/10W"
    }

    private fun defaultCapPkg(pf: Double): String = when {
        pf >= 1e6 -> "0805 (2012)"
        pf >= 1e4 -> "0603 (1608)"
        else -> "0402 (1005)"
    }

    private fun guessResistorTags(ohm: Double): List<String> {
        val tags = mutableListOf<String>()
        when {
            ohm == 0.0 -> { tags += "跳线"; tags += "零欧"; }
            ohm >= 1e4 && ohm <= 1e5 -> { tags += "上拉"; tags += "下拉"; tags += "分压"; }
            ohm <= 50.0 -> { tags += "限流"; tags += "采样"; }
            ohm >= 1e5 -> { tags += "分压"; tags += "高阻"; }
            else -> { tags += "限流"; tags += "分压"; }
        }
        tags += "通用"
        return tags
    }
}
