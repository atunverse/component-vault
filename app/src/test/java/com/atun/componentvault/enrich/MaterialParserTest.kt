package com.atun.componentvault.enrich

import com.atun.componentvault.data.Domains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五金 / 3D 耗材规范名解析。
 *
 * 这层存在的唯一理由：立创检索对规范名极准、对口语极不准。
 * 实测（2026-10-20）：搜「M3螺丝」返回接线端子和理线器；
 * 搜「DIN912 M3」返回 30 条全部是内六角圆柱头螺钉。
 */
class MaterialParserTest {

    @Test
    fun `M3星8 解析为 DIN912 规范名`() {
        val d = MaterialParser.parse("M3*8")
        assertNotNull(d)
        assertEquals(Domains.HARDWARE, d!!.domain)
        assertEquals("DIN912 M3×8", d.mpn)
        assertEquals("M3×8", d.packageName)
        assertEquals("螺钉", d.category)
        assertEquals("M3", d.params["螺纹"])
        assertEquals("8mm", d.params["长度"])
        assertEquals("304 不锈钢", d.params["材质"])
        // 检索词刻意不带长度：带长度会漏掉「M3-0.5X8」这种写法
        assertEquals("DIN912 M3", d.searchKeyword)
        assertEquals("个", d.unit)
    }

    @Test
    fun `五种分隔符写法等价`() {
        listOf("M3*8", "M3x8", "M3X8", "M3×8", "M3-0.5X8").forEach { input ->
            val d = MaterialParser.parse(input)
            assertNotNull("未解析：$input", d)
            assertEquals("螺纹解析失败：$input", "M3", d!!.params["螺纹"])
            assertEquals("长度解析失败：$input", "8mm", d.params["长度"])
        }
    }

    @Test
    fun `材质与头型可从原文识别`() {
        val d = MaterialParser.parse("M4x12 316 沉头")!!
        assertEquals("316", d.params["材质"])
        assertEquals("沉头", d.params["头型"])
        assertEquals("M4", d.params["螺纹"])
    }

    @Test
    fun `螺母走 GB6170 且无长度`() {
        val d = MaterialParser.parse("M3螺母")!!
        assertEquals("螺母", d.category)
        assertEquals("GB6170 M3", d.mpn)
        assertNull(d.params["长度"])
        assertEquals("M3 螺母", d.searchKeyword)
    }

    @Test
    fun `铜柱走单独检索词`() {
        val d = MaterialParser.parse("铜柱 M3*6+6")!!
        assertEquals("铜柱", d.category)
        assertEquals("单头六角铜螺柱", d.name)
        assertTrue(d.searchKeyword.startsWith("铜柱"))
    }

    @Test
    fun `只有螺纹号时不误判为电子件`() {
        // M5 单独出现：类型未知，但仍应进入五金分支（界面再让用户选类型）
        val d = MaterialParser.parse("M5")
        assertNotNull(d)
        assertEquals(Domains.HARDWARE, d!!.domain)
        assertNull(d.params["长度"])
    }

    @Test
    fun `电子型号不能被当成五金`() {
        listOf("STM32F103C8T6", "SS34", "AO3400", "10k 0603", "C8734").forEach {
            assertNull("误判为五金：$it", MaterialParser.parse(it))
        }
    }

    @Test
    fun `3D耗材按克管理且不做立创检索`() {
        val d = MaterialParser.parse("PLA 1.75 黑")!!
        assertEquals(Domains.FILAMENT, d.domain)
        assertEquals("g", d.unit)
        assertEquals("PLA", d.params["材质"])
        assertEquals("1.75mm", d.params["直径"])
        assertEquals("黑", d.params["颜色"])
        assertEquals("1000g", d.params["净重"])
        // 实测立创 EDA 库不收录耗材，检索只会返回固态继电器，所以必须留空
        assertEquals("", d.searchKeyword)
    }

    @Test
    fun `耗材可识别 2_85 与带 kg 的净重`() {
        val d = MaterialParser.parse("PETG 2.85 透明 2kg")!!
        assertEquals("2.85mm", d.params["直径"])
        assertEquals("2000g", d.params["净重"])
    }

    @Test
    fun `PLA191 这种型号不被当成耗材`() {
        assertNull(MaterialParser.parse("PLA191"))
    }
}
