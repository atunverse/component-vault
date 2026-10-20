package com.atun.componentvault.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 耗材预设库的纯 JVM 用例。
 *
 * 重点盯两件事：
 *  1. 材料识别不能「短名吃掉长名」（PLA-CF 被认成 PLA 会错带温度与密度）
 *  2. 剩余长度换算的量级要合理（1kg 1.75mm PLA 约 330 米，这是硬常识）
 */
class FilamentPresetsTest {

    @Test
    fun `预设材料表非空且关键字段有效`() {
        assertTrue("材料预设至少要有 15 种", FilamentPresets.MATERIALS.size >= 15)
        FilamentPresets.MATERIALS.forEach { m ->
            assertTrue("${m.name} 密度应 > 0.5", m.density > 0.5)
            assertTrue("${m.name} 密度应 < 2.0", m.density < 2.0)
            assertTrue("${m.name} 缺喷嘴温度", m.nozzle.isNotBlank())
            assertTrue("${m.name} 缺热床温度", m.bed.isNotBlank())
        }
    }

    @Test
    fun `品牌与颜色预设都非空且色值合法`() {
        assertTrue(FilamentPresets.BRANDS.size >= 10)
        assertTrue(FilamentPresets.COLORS.size >= 12)
        FilamentPresets.COLORS.forEach { c ->
            assertTrue("${c.name} 色值格式不对：${c.hex}", Regex("^#[0-9A-Fa-f]{6}$").matches(c.hex))
        }
    }

    @Test
    fun `按名称查材料忽略大小写`() {
        assertEquals("PETG", FilamentPresets.material("petg")?.name)
        assertEquals("PLA", FilamentPresets.material(" Pla ")?.name)
        assertNull(FilamentPresets.material("不存在的材料"))
        assertNull(FilamentPresets.material(""))
    }

    @Test
    fun `从自由文本猜材料时优先长名`() {
        // 这条最容易错：如果按表顺序找，PLA-CF 会先命中 PLA
        assertEquals("PLA-CF", FilamentPresets.guessMaterial("PLA-CF 黑色 1.75")?.name)
        assertEquals("PETG-CF", FilamentPresets.guessMaterial("petg-cf")?.name)
        assertEquals("PETG", FilamentPresets.guessMaterial("PETG 透明 1kg")?.name)
        assertEquals("ABS", FilamentPresets.guessMaterial("abs 白")?.name)
        assertEquals("ASA", FilamentPresets.guessMaterial("ASA 耐候")?.name)
        assertNull(FilamentPresets.guessMaterial("碳纤维管"))
    }

    @Test
    fun `从自由文本猜颜色`() {
        assertEquals("黑", FilamentPresets.guessColor("PLA 黑色 1.75")?.name)
        assertEquals("透明", FilamentPresets.guessColor("PETG 透明")?.name)
        assertNull(FilamentPresets.guessColor("PLA 1.75"))
    }

    @Test
    fun `剩余长度换算量级正确`() {
        // 1kg、密度 1.24、线径 1.75 的 PLA，实际约 330 米
        val pla = FilamentPresets.lengthMeters(1000.0, 1.24, 1.75)
        assertNotNull(pla)
        assertTrue("1kg PLA 应约 330 米，实际 $pla", pla!! in 300.0..360.0)

        // 同样的质量，2.85 线径截面积是 1.75 的 2.65 倍，长度应显著变短
        val thick = FilamentPresets.lengthMeters(1000.0, 1.24, 2.85)!!
        assertTrue("2.85 应明显短于 1.75", thick < pla / 2)

        assertNull(FilamentPresets.lengthMeters(0.0, 1.24, 1.75))
        assertNull(FilamentPresets.lengthMeters(100.0, 0.0, 1.75))
        assertNull(FilamentPresets.lengthMeters(100.0, 1.24, 0.0))
    }

    @Test
    fun `规格选项覆盖常见盘重`() {
        assertTrue(FilamentPresets.DIAMETERS.contains("1.75"))
        assertTrue(FilamentPresets.DIAMETERS.contains("2.85"))
        assertTrue(FilamentPresets.NET_WEIGHTS.contains("1000"))
        assertTrue(FilamentPresets.NET_WEIGHTS.contains("500"))
        assertTrue(FilamentPresets.SPOOL_WEIGHTS.contains("0"))
    }
}
