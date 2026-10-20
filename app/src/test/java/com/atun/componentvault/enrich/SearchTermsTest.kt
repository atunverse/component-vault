package com.atun.componentvault.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关键词拆分与匹配的回归测试。
 *
 * 这组用例锁的是「搜索支持空格」这条需求：带空格的关键词既不能被输入框吃掉，
 * 也不能在检索层被当成一整串去比对。
 */
class SearchTermsTest {

    // ------------------------------------------------------------ 拆词

    @Test
    fun `按空格拆词并丢掉空串`() {
        assertEquals(listOf("STM32", "F103C8T6"), SearchTerms.split("STM32 F103C8T6"))
        assertEquals(listOf("PLA", "1.75"), SearchTerms.split("  PLA   1.75  "))
    }

    @Test
    fun `全角空格也能拆`() {
        // 中文输入法很容易打出 U+3000，之前它被当成普通字符，整串比对必然失败
        assertEquals(listOf("M3", "8"), SearchTerms.split("M3\u30008"))
    }

    @Test
    fun `空输入拆出空表`() {
        assertTrue(SearchTerms.split("").isEmpty())
        assertTrue(SearchTerms.split("   ").isEmpty())
    }

    // ------------------------------------------------------------ 主检索词

    @Test
    fun `单个词原样返回主检索词`() {
        assertEquals("AMS1117-3.3", SearchTerms.primary(listOf("AMS1117-3.3")))
        assertEquals("", SearchTerms.primary(emptyList()))
    }

    @Test
    fun `多词时挑含数字的那个当主检索词`() {
        // 立创检索对具体料号最准，对「贴片」「电阻」这类品类词最不准
        assertEquals("F103C8T6", SearchTerms.primary(listOf("STM32", "F103C8T6")))
        assertEquals("C8734", SearchTerms.primary(listOf("STM32", "C8734")))
    }

    @Test
    fun `都不含数字时取最长的词`() {
        assertEquals("AMS1117", SearchTerms.primary(listOf("AMS", "AMS1117")))
    }

    // ------------------------------------------------------------ 匹配

    @Test
    fun `所有词都出现才算命中`() {
        val text = "AMS1117-3.3 稳压器 SOT-223"
        assertTrue(SearchTerms.matches(text, listOf("AMS1117", "SOT-223")))
        assertFalse(SearchTerms.matches(text, listOf("AMS1117", "SOP-8")))
    }

    @Test
    fun `空词表视为命中`() {
        assertTrue(SearchTerms.matches("任意内容", emptyList()))
    }

    @Test
    fun `跨字段命中 空格不再要求连续子串`() {
        // 库存里名称是「PLA 线材」、规格是「黑色」，用户敲 `PLA 黑` 应该能搜到
        val fields = listOf("PLA 线材", "", "黑色 1kg", "eSUN")
        assertTrue(SearchTerms.matchesAny(fields, listOf("PLA", "黑")))
    }

    @Test
    fun `少一个词就不命中`() {
        val fields = listOf("PLA 线材", "黑色 1kg")
        assertFalse(SearchTerms.matchesAny(fields, listOf("PLA", "PETG")))
    }

    // ------------------------------------------------------------ 候选过滤

    @Test
    fun `单词输入不过滤候选`() {
        val items = listOf("AMS1117-3.3", "LM1117-3.3")
        assertEquals(items, SearchTerms.filterOrKeep(items, listOf("1117")) { it })
    }

    @Test
    fun `过滤后为空时退回原始候选`() {
        // 宁可多给几条让人自己看，也不要因为多打一个词就把正确结果全筛掉
        val items = listOf("AMS1117-3.3 SOT-223")
        val kept = SearchTerms.filterOrKeep(items, listOf("AMS1117", "不存在的词")) { it }
        assertEquals(items, kept)
    }

    @Test
    fun `过滤后非空时只留命中的`() {
        val items = listOf("AMS1117-3.3 SOT-223", "LM317 TO-220")
        val kept = SearchTerms.filterOrKeep(items, listOf("AMS1117", "SOT-223")) { it }
        assertEquals(listOf("AMS1117-3.3 SOT-223"), kept)
    }
}
