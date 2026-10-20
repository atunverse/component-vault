package com.atun.componentvault.enrich

/**
 * 3D 打印耗材预设库。
 *
 * 字段模型参考开源项目 Spoolman（Donkie/Spoolman）里的 filament 表：
 * `material` / `diameter` / `density` / `weight`（净重）/ `spool_weight`（空盘重）/
 * `settings_extruder_temp`（喷嘴温度）/ `settings_bed_temp`（热床温度）/ `color_hex`。
 * Spoolman 另外有 vendor（厂商）与 spool（单盘：剩余重量、位置、批次号、价格）两层，
 * 本应用把 spool 直接当库存物品（qty 就是剩余克数），vendor 落到 brand 字段，
 * 这样盘级追踪、仓位、流水都能复用已有机制，不用为了耗材再复制一套出入库。
 *
 * 这里是纯数据 + 纯函数，不依赖 Android，可直接跑 JVM 单测。
 *
 * 温度与密度取自材料通识区间，是**推荐起点**而不是厂商实测值：
 * 同一材料不同品牌差异可达 ±20℃，所以界面上明确写「预设仅供参考」。
 */
object FilamentPresets {

    data class Material(
        val name: String,
        /** g/cm³，用于把克数换算成剩余长度 */
        val density: Double,
        /** 喷嘴温度区间，℃ */
        val nozzle: String,
        /** 热床温度区间，℃ */
        val bed: String,
        /** 特性一句话 */
        val trait: String,
        /** 典型用途 */
        val usage: String,
        /** 是否需要封箱（翘边 / 温度敏感） */
        val enclosure: Boolean = false
    )

    data class Color(val name: String, val hex: String)

    // ------------------------------------------------------------ 材料类型

    val MATERIALS: List<Material> = listOf(
        Material("PLA", 1.24, "195-215", "55-65", "最好打印，刚度高，耐温仅约 55℃", "模型、手办、外观件、原型"),
        Material("PLA+", 1.24, "205-225", "55-65", "增韧配方，层间强度高于普通 PLA", "功能件、卡扣、承力小件"),
        Material("哑光 PLA", 1.24, "200-220", "55-65", "表面无光泽，层纹不明显", "外观件、展示模型"),
        Material("丝绸 PLA", 1.24, "200-220", "55-65", "丝绸光泽，层间结合略弱", "装饰件、花瓶"),
        Material("木质 PLA", 1.28, "195-220", "55-60", "含木粉，气味天然，易堵喷嘴", "工艺品、仿木件"),
        Material("夜光 PLA", 1.25, "200-220", "55-65", "含荧光粉，磨损喷嘴", "装饰件、玩具"),
        Material("大理石 PLA", 1.25, "200-220", "55-65", "随机纹理，层纹掩盖好", "雕塑、摆件"),
        Material("PLA-CF", 1.30, "210-230", "55-65", "碳纤增强，刚度高，需硬质喷嘴", "结构件、治具、支架"),
        Material("PETG", 1.27, "230-250", "75-85", "韧性好、耐化学、略拉丝，易粘床", "结构件、户外件、耐温约 70℃"),
        Material("PETG-CF", 1.30, "240-260", "75-85", "碳纤增强 PETG，刚度与耐温兼顾", "高强度结构件、机械臂"),
        Material("PCTG", 1.23, "250-270", "80-90", "透明度高，韧性优于 PETG", "透明外壳、灯罩"),
        Material("ABS", 1.04, "235-260", "95-110", "耐冲击、耐温约 95℃，有气味、易翘边", "电器外壳、汽车内饰", enclosure = true),
        Material("ASA", 1.07, "240-265", "95-110", "ABS 的耐候版，抗紫外", "户外长期暴露件", enclosure = true),
        Material("TPU", 1.21, "210-235", "30-50", "柔性回弹，需低速打印", "密封圈、减震脚、表带"),
        Material("TPE", 1.10, "220-240", "40-60", "比 TPU 更软，弹性更大", "软胶件、握把"),
        Material("PC", 1.20, "260-290", "100-110", "强度与耐温最高的常规材料", "受力结构件、透明罩", enclosure = true),
        Material("PA（尼龙）", 1.14, "250-270", "70-80", "耐磨、强度高，吸湿需干燥", "齿轮、铰链、耐磨件", enclosure = true),
        Material("PA-CF", 1.19, "260-280", "70-80", "碳纤尼龙，刚度接近金属件", "高负载结构件", enclosure = true),
        Material("PP", 0.90, "220-250", "80-100", "最轻，耐化学，极难粘床", "活页铰链、化学容器", enclosure = true),
        Material("HIPS", 1.04, "220-245", "95-105", "ABS 的替代品，可作可溶支撑", "支撑材料、轻量外壳", enclosure = true),
        Material("PVA", 1.23, "190-210", "55-65", "水溶支撑，怕潮", "双喷头可溶支撑"),
        Material("BVOH", 1.22, "190-210", "55-65", "水溶支撑，溶解快于 PVA", "双喷头可溶支撑")
    )

    // ------------------------------------------------------------ 品牌

    /** 预设品牌。允许自由填写 —— 这里只是减少输入，不是白名单。 */
    val BRANDS: List<String> = listOf(
        "eSUN 易生",
        "三绿 SUNLU",
        "拓竹 Bambu Lab",
        "Polymaker",
        "创想三维 Creality",
        "纵维立方 Anycubic",
        "JAYO",
        "Kingroon",
        "ERYONE",
        "Overture",
        "Geeetech",
        "Prusament",
        "Devil Design",
        "Fiberlogy"
    )

    // ------------------------------------------------------------ 颜色

    val COLORS: List<Color> = listOf(
        Color("黑", "#1A1A1A"),
        Color("白", "#F2F2F2"),
        Color("灰", "#9E9E9E"),
        Color("银", "#C0C0C0"),
        Color("红", "#D32F2F"),
        Color("橙", "#F57C00"),
        Color("黄", "#FBC02D"),
        Color("绿", "#2E7D32"),
        Color("浅绿", "#8BC34A"),
        Color("蓝", "#1565C0"),
        Color("天蓝", "#4FC3F7"),
        Color("紫", "#7B1FA2"),
        Color("粉", "#EC407A"),
        Color("棕", "#6D4C41"),
        Color("金", "#C9A227"),
        Color("米白", "#EFE6D8"),
        Color("透明", "#E3F2FD")
    )

    // ------------------------------------------------------------ 规格选项

    /** 线径，mm。2.85 现在少见，但老机器还有 */
    val DIAMETERS: List<String> = listOf("1.75", "2.85")

    /** 标称净重，g */
    val NET_WEIGHTS: List<String> = listOf("250", "500", "750", "1000", "2000", "3000")

    /** 空盘重，g。算「这盘还能剩多少料」时要减掉 */
    val SPOOL_WEIGHTS: List<String> = listOf("0", "150", "190", "220", "250", "300")

    // ------------------------------------------------------------ 查询

    fun material(name: String?): Material? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        return MATERIALS.firstOrNull { it.name.equals(n, ignoreCase = true) }
            ?: MATERIALS.firstOrNull { it.name.contains(n, ignoreCase = true) }
    }

    fun color(name: String?): Color? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        return COLORS.firstOrNull { it.name == n }
    }

    /** 材料名列表，给下拉用 */
    fun materialNames(): List<String> = MATERIALS.map { it.name }

    /**
     * 从一段自由文本里猜材料。用于「快录」里用户直接打「PETG 黑 1.75」这种，
     * 或者立创/手工来的名字里带材料词。**先长后短**，否则 "PLA-CF" 会先命中 "PLA"。
     */
    fun guessMaterial(text: String): Material? {
        val t = text.uppercase()
        return MATERIALS.sortedByDescending { it.name.length }
            .firstOrNull { t.contains(it.name.uppercase()) }
    }

    /**
     * 从自由文本里猜颜色名。**先长后短**：否则「浅绿」会先命中「绿」，
     * 「米白」会先命中「白」，存进库里的颜色就错了。
     */
    fun guessColor(text: String): Color? =
        COLORS.sortedByDescending { it.name.length }.firstOrNull { text.contains(it.name) }

    /** 剩余长度估算：质量(g) → 长度(m)。体积=质量/密度，长度=体积/截面积 */
    fun lengthMeters(massG: Double, density: Double, diameterMm: Double): Double? {
        if (massG <= 0 || density <= 0 || diameterMm <= 0) return null
        val radiusCm = diameterMm / 20.0
        val areaCm2 = Math.PI * radiusCm * radiusCm
        val volumeCm3 = massG / density
        return volumeCm3 / areaCm2 / 100.0
    }
}
