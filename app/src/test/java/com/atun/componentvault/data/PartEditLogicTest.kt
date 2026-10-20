package com.atun.componentvault.data

import com.atun.componentvault.ui.paramsToJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑操作的纯逻辑：差异描述 + 参数序列化。
 *
 * 这里守的是「改了什么必须留痕」这条底线 —— 详情页的流水靠它呈现，
 * 一旦 diffNote 写出空串，改仓位就变成一条没有信息的 EDIT 记录。
 */
class PartEditLogicTest {

    private fun part(
        location: String = "A-01-1",
        category: String = "电阻",
        domain: String = Domains.ELECTRONICS,
        mpn: String = "10k",
        low: Double = 10.0,
        unit: String = "个",
        price: Double = 0.01
    ) = Part(
        id = 1,
        internalCode = "CV-000001",
        mpn = mpn,
        category = category,
        domain = domain,
        qty = 50.0,
        location = location,
        lowThreshold = low,
        unit = unit,
        price = price
    )

    @Test
    fun `只改仓位也要有可读的备注`() {
        val note = diffNote(part(), part(location = "B-02-1"))
        assertEquals("仓位 A-01-1→B-02-1", note)
    }

    @Test
    fun `仓位清空时显示为空而不是空白`() {
        val note = diffNote(part(location = "A-01-1"), part(location = ""))
        assertTrue(note.contains("仓位 A-01-1→空"))
    }

    @Test
    fun `多项修改按顺序拼接`() {
        val note = diffNote(
            part(),
            part(location = "B-02-1", low = 100.0, unit = "包")
        )
        assertTrue(note.contains("仓位"))
        assertTrue(note.contains("阈值 10→100"))
        assertTrue(note.contains("单位 个→包"))
    }

    @Test
    fun `没有改动时给出兜底文案而不是空串`() {
        assertEquals("资料修改", diffNote(part(), part()))
    }

    @Test
    fun `参数按键值对序列化且忽略空值`() {
        val json = paramsToJson("螺纹=M3\n长度=8mm\n\n随便一句话\n空=\n=x")
        assertEquals("""{"螺纹":"M3","长度":"8mm"}""", json)
    }

    @Test
    fun `参数为空时给出空对象而不是空串`() {
        assertEquals("{}", paramsToJson(""))
        assertEquals("{}", paramsToJson("没有等号的行"))
    }

    @Test
    fun `引号会被转义`() {
        val json = paramsToJson("备注=说\"是\"")
        // 期望 {"备注":"说\\"是\\""} 这种形式，反斜杠必须在
        assertTrue(json.contains("\\\""))
        assertTrue(json.startsWith("{\"备注\":\""))
    }

    @Test
    fun `整数数量不显示小数点`() {
        assertEquals("50", fmtQty(50.0))
        assertEquals("0", fmtQty(0.0))
    }

    @Test
    fun `小数数量保留两位以内`() {
        assertEquals("3.5", fmtQty(3.5))
        assertEquals("734.25", fmtQty(734.25))
    }

    @Test
    fun `工具类阈值为零时不报警`() {
        val tool = part(low = 0.0)
        assertFalse(tool.isLow)
        assertFalse(tool.isOut)
    }

    @Test
    fun `耗材剩余比例按净重计算并封顶`() {
        val f = part(low = 200.0, unit = "g").copy(qty = 734.0)
        assertEquals(0.734f, f.filamentPercent(1000.0)!!, 0.001f)
        assertEquals(1f, f.copy(qty = 2000.0).filamentPercent(1000.0)!!, 0.001f)
        assertNull(f.filamentPercent(null))
    }
}
