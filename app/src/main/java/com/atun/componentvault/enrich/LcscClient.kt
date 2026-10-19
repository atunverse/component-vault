package com.atun.componentvault.enrich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 立创元件库检索客户端。
 *
 * 选型说明：
 *  - 用 HttpURLConnection 而不是引入 OkHttp —— 只为一个 GET 请求加 700KB 依赖不值。
 *  - kotlinx.serialization 解析，字段缺失有默认值，接口改版不会整页崩。
 *  - 手动设 UA：实测不设 UA 时部分节点会拒绝；Referer 指向 EDA 编辑器，与真实调用方一致。
 *  - 不设 Accept-Encoding，让系统的 HttpURLConnection 自动协商并解 gzip（手动设置会关掉自动解压）。
 *
 * 失败一律抛 [LcscException]，由 Resolver 决定降级路径，不做静默吞异常。
 */
class LcscClient(
    private val baseUrl: String = DEFAULT_BASE,
    private val timeoutMs: Int = 12_000
) {

    companion object {
        const val DEFAULT_BASE = "https://pro.lceda.cn/api/eda/product/search"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    class LcscException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun buildUrl(keyword: String, page: Int, pageSize: Int): String {
        val kw = URLEncoder.encode(keyword.trim(), "UTF-8")
        return "$baseUrl?keyword=$kw&currentPage=$page&pageSize=$pageSize"
    }

    /** 原始结果（未排序、未裁剪） */
    suspend fun searchRaw(keyword: String, page: Int = 1, pageSize: Int = 20): LcscResult =
        withContext(Dispatchers.IO) {
            val url = buildUrl(keyword, page, pageSize)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Referer", "https://pro.lceda.cn/editor")
                instanceFollowRedirects = true
            }
            try {
                val status = conn.responseCode
                if (status != 200) {
                    throw LcscException("HTTP $status")
                }
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                if (body.isBlank()) throw LcscException("空响应")
                val resp = try {
                    lcscJson.decodeFromString<LcscResponse>(body)
                } catch (e: Exception) {
                    // 返回 HTML（被拦/域名变更）时给出可诊断的信息
                    throw LcscException("响应不是 JSON：${body.take(80)}", e)
                }
                if (resp.code != 200 || !resp.ok) {
                    throw LcscException("接口返回 code=${resp.code} msg=${resp.msg}")
                }
                resp.result ?: LcscResult()
            } catch (e: LcscException) {
                throw e
            } catch (e: Exception) {
                throw LcscException(e.message ?: "网络错误", e)
            } finally {
                conn.disconnect()
            }
        }

    /** 排序后的候选，直接给 UI */
    suspend fun search(keyword: String, pageSize: Int = 20, limit: Int = 8): List<LcscHit> {
        if (keyword.isBlank()) return emptyList()
        val raw = searchRaw(keyword, 1, pageSize)
        return LcscMatcher.rank(raw.productList, keyword, limit = limit)
    }
}
