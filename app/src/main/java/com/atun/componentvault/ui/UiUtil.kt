package com.atun.componentvault.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 打开外部链接。库存 App 里唯一的"跳出"动作：
 * 立创商品页、数据手册 PDF。
 *
 * 用 ACTION_VIEW 交给系统处理，不内置 WebView —— 立创页面依赖完整浏览器环境，
 * 内嵌 WebView 反而容易白屏。
 */
fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Exception) {
        // 没有可用浏览器时静默失败，不打断录入流程
    }
}
