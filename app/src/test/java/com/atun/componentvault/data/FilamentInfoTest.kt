package com.atun.componentvault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 耗材字段读取与推算。
 *
 * 这些用例守的是「参数优先、预设兜底」这条规则：用户只选了材料没填密度时，
 * 详情页照样得能算出剩余长度，否则新录入的盘看起来就是一块空白。
 */
class FilamentInfoTest {

    private fun spool(
        category: String = "PETG",
        packageName: String = "1.75mm",
        params: String = """{"材质":"PETG","直径":"1.75","颜色":"透明","净重":"1000","空盘重":"220"}""",
        qty: Double = 734.0
    ) = Part(
        internalCode = "CV-000001",
        mpn = "PETG 1.75 透明",
        name = "PETG 线材",
        category = category,
        domain = Domains.FILAMENT,
        packageName = packageName,
        paramsJson = params,
        qty = qty,
        unit = "g"
    )

    @Test
    fun `从参数读基础字段`() {
        val p = spool()
        assertEquals("PETG", FilamentInfo.material(p))
        assertEquals("透明", FilamentInfo.colorName(p))
        assertEquals(1000.0, FilamentInfo.netWeight(p)!!, 0.001)
        assertEquals(220.0, FilamentInfo.spoolWeight(p)!!, 0.001)
        assertEquals(1.75, FilamentInfo.diameterMm(p)!!, 0.001)
    }

    @Test
    fun `密度与温度缺失时回落到材料预设`() {
        val p = spool(category = "PLA", params = """{"颜色":"黑"}""")
        // 没填密度 → 用 PLA 预设 1.24
        assertEquals(1.24, FilamentInfo.density(p)!!, 0.001)
        assertEquals("195-215", FilamentInfo.nozzle(p))
        assertEquals("55-65", FilamentInfo.bed(p))
    }

    @Test
    fun `颜色色值可以由颜色名推出`() {
        val p = spool(params = """{"颜色":"黑"}""")
        assertEquals("#1A1A1A", FilamentInfo.colorHex(p))

        val custom = spool(params = """{"颜色":"墨绿","颜色值":"#123456"}""")
        assertEquals("#123456", FilamentInfo.colorHex(custom))
    }

    @Test
    fun `剩余比例按标称净重换算`() {
        val p = spool(qty = 734.0)
        assertEquals(0.734f, FilamentInfo.percent(p)!!, 0.001f)

        // 超装（实际比标称多）也不能超过 100%
        assertEquals(1.0f, FilamentInfo.percent(p.copy(qty = 1500.0))!!, 0.001f)
        // 净重未知时不画进度条
        assertNull(FilamentInfo.percent(spool(params = """{"材质":"PETG"}""")))
    }

    @Test
    fun `剩余长度按密度与线径换算`() {
        val p = spool(qty = 1000.0)
        val meters = FilamentInfo.remainingMeters(p)
        assertNotNull(meters)
        // PETG 1.27 g/cm³、1.75mm、1kg ≈ 327 米
        assertTrue("实际 $meters", meters!! in 300.0..350.0)
    }

    @Test
    fun `开封天数解析与容错`() {
        val today = LocalDate.of(2026, 10, 20)
        val p = spool(params = """{"材质":"PETG","开封日期":"2026-10-10"}""")
        assertEquals(10L, FilamentInfo.openedDays(p, today)!!)

        val noDate = spool(params = """{"材质":"PETG"}""")
        assertNull(FilamentInfo.openedDays(noDate, today))

        val bad = spool(params = """{"材质":"PETG","开封日期":"十月十号"}""")
        assertNull(FilamentInfo.openedDays(bad, today))
    }

    @Test
    fun `材料名规范化收敛到预设写法`() {
        assertEquals("PETG", FilamentInfo.normalizeMaterial("petg"))
        assertEquals("PLA-CF", FilamentInfo.normalizeMaterial("PLA-CF"))
        // 预设里没有的保留原样，不硬套
        assertEquals("某种新材料", FilamentInfo.normalizeMaterial(" 某种新材料 "))
    }

    @Test
    fun `列表副标题包含材料颜色与剩余量`() {
        val s = FilamentInfo.subtitle(spool())
        assertTrue(s, s.contains("PETG"))
        assertTrue(s, s.contains("透明"))
        assertTrue(s, s.contains("734"))
        assertTrue(s, s.contains("1000"))
    }

    @Test
    fun `参数文本里的数字可以带单位`() {
        assertEquals(1000.0, FilamentInfo.firstNumber("1000g")!!, 0.001)
        assertEquals(1.75, FilamentInfo.firstNumber("1.75mm")!!, 0.001)
        assertEquals(200.0, FilamentInfo.firstNumber("200-220")!!, 0.001)
        assertNull(FilamentInfo.firstNumber(""))
    }
}
