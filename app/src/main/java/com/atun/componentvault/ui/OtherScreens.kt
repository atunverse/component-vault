package com.atun.componentvault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atun.componentvault.BuildConfig
import com.atun.componentvault.CrashGuard
import com.atun.componentvault.Graph
import com.atun.componentvault.data.Domains
import com.atun.componentvault.data.IconStore
import com.atun.componentvault.data.VaultRepository
import com.atun.componentvault.data.Part
import com.atun.componentvault.data.fmtQty
import com.atun.componentvault.net.LanServerService
import com.atun.componentvault.enrich.LcscResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

// ---------------------------------------------------------------- BOM 分析

private data class BomLine(val index: Int, val keyword: String, val need: Double, val raw: String)

private data class BomMatch(
    val line: BomLine,
    val part: Part?,
    /** OK / LOW / MISSING */
    val state: String
)

@Composable
fun BomScreen(onBack: () -> Unit = {}) {
    var parts by remember { mutableStateOf<List<Part>>(emptyList()) }
    var text by remember {
        mutableStateOf("型号,数量\n10kΩ,50\n100nF,200\nSS34,20\nTPS5430DDA,2\nAO3400,30")
    }
    var result by remember { mutableStateOf<List<BomMatch>?>(null) }

    LaunchedEffect(Unit) {
        Graph.repo.observeParts().collect { parts = it }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        SubScreenBar("BOM 比对", onBack)
        Text("BOM 分析", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "粘贴 BOM（每行「型号,数量」），自动比对现有库存并标出缺失",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it; result = null },
            modifier = Modifier.fillMaxWidth().height(150.dp),
            label = { Text("BOM 内容") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { result = analyze(text, parts) },
                modifier = Modifier.weight(1f)
            ) { Text("分析库存") }
            OutlinedButton(
                onClick = { text = ""; result = null },
                modifier = Modifier.weight(0.6f)
            ) { Text("清空") }
        }

        result?.let { list ->
            val ok = list.count { it.state == "OK" }
            val low = list.count { it.state == "LOW" }
            val miss = list.count { it.state == "MISSING" }

            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("齐备 $ok", fontSize = 12.sp, color = Color(0xFF065F46), fontWeight = FontWeight.SemiBold)
                    Text("不足 $low", fontSize = 12.sp, color = Color(0xFFB45309), fontWeight = FontWeight.SemiBold)
                    Text("缺失 $miss", fontSize = 12.sp, color = Color(0xFFB91C1C), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            list.forEach { m ->
                BomRow(m)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun BomRow(m: BomMatch) {
    val (label, color, bg) = when (m.state) {
        "OK" -> Triple("齐备", Color(0xFF059669), Color(0xFFD1FAE5))
        "LOW" -> Triple("不足", Color(0xFFD97706), Color(0xFFFEF3C7))
        else -> Triple("缺失", Color(0xFFDC2626), Color(0xFFFEE2E2))
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(44.dp)
                    .background(bg, RoundedCornerShape(6.dp))
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 10.5.sp, color = color, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.line.keyword,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    m.part?.let { "${it.name} · ${it.location.ifBlank { "未见仓位" }}" } ?: "库中无此器件",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("需 ${fmtQty(m.line.need)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "存 ${fmtQty(m.part?.qty ?: 0.0)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
    }
}

private fun analyze(text: String, parts: List<Part>): List<BomMatch> {
    val lines = text.lines()
    val out = mutableListOf<BomMatch>()
    var idx = 0
    for (raw in lines) {
        val t = raw.trim()
        if (t.isEmpty()) continue
        // 跳过表头
        if (t.contains("型号") && t.contains("数量")) continue
        val cells = t.split(',', '\t', '|', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (cells.isEmpty()) continue
        val keyword = cells.first()
        val need = cells.drop(1).mapNotNull { it.toDoubleOrNull() }.lastOrNull() ?: 1.0
        idx++
        val hit = parts.firstOrNull {
            it.mpn.equals(keyword, ignoreCase = true)
        } ?: parts.firstOrNull {
            it.mpn.isNotBlank() && (
                it.mpn.contains(keyword, ignoreCase = true) ||
                    keyword.contains(it.mpn, ignoreCase = true)
                )
        } ?: parts.firstOrNull {
            it.name.contains(keyword, ignoreCase = true)
        }

        val state = when {
            hit == null -> "MISSING"
            hit.qty >= need -> "OK"
            else -> "LOW"
        }
        out += BomMatch(BomLine(idx, keyword, need, raw), hit, state)
    }
    return out
}

// ---------------------------------------------------------------- 局域网

@Composable
fun LanScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(LanServerService.isRunning) }
    var portText by remember { mutableStateOf(LanServerService.DEFAULT_PORT.toString()) }
    var copied by remember { mutableStateOf(false) }

    val ip = remember { LanServerService.localIp() }
    val url = "http://$ip:${portText.toIntOrNull() ?: LanServerService.DEFAULT_PORT}"

    LaunchedEffect(Unit) {
        while (true) {
            running = LanServerService.isRunning
            delay(1200)
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        SubScreenBar("局域网", onBack)
        Text("局域网管理", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "手机作为服务端，电脑在同一 Wi-Fi 下用浏览器管理库存",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .background(
                                if (running) Color(0xFFD1FAE5) else Color(0xFFE2E8F0),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            if (running) "运行中" else "已停止",
                            fontSize = 10.5.sp,
                            color = if (running) Color(0xFF059669) else Color(0xFF64748B),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("本机地址", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    url,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "电脑浏览器打开此地址即可管理；若打不开，多为路由器开启了 AP 隔离或电脑不在同一网段",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { s -> portText = s.filter { it.isDigit() }.take(5) },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp)
                    )
                    Button(
                        onClick = {
                            if (running) {
                                LanServerService.stop(ctx)
                                running = false
                            } else {
                                LanServerService.start(
                                    ctx,
                                    portText.toIntOrNull() ?: LanServerService.DEFAULT_PORT
                                )
                                running = true
                            }
                        },
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Text(if (running) "停止服务" else "启动服务")
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", url))
                    copied = true
                }) {
                    Text(if (copied) "已复制" else "复制地址", fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Tips(0)
    }
}

@Composable
private fun Tips(kind: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("连不上时按顺序排查", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            listOf(
                "① 手机和电脑是否连的同一个 Wi-Fi（手机热点也行，但手机要关数据流量）",
                "② 路由器是否开启「AP 隔离 / 访客网络隔离」，开启后设备互相不可见",
                "③ 电脑防火墙是否拦了该端口，可临时关闭测试",
                "④ 手机息屏后服务是否被系统杀掉，建议加电池白名单"
            ).forEach {
                Text(it, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- 我的

@Composable
fun MeScreen(onOpenBom: () -> Unit = {}, onOpenLan: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf(LcscResolver.cacheSize()) }
    var crashText by remember { mutableStateOf(CrashGuard.readLast(ctx)) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text("我的", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        // BOM 与局域网从底部导航收进来：它们是偶尔用的工具，不是日常主路径
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(6.dp)) {
                ToolEntry("BOM 比对", "粘贴清单，自动算出缺多少、够不够") { onOpenBom() }
                ToolEntry("局域网管理", "电脑浏览器改仓位、导出数据") { onOpenLan() }
            }
        }
        Spacer(Modifier.height(14.dp))

        crashText?.let { crash ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "上次启动时异常退出",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        crash.lineSequence().filter { it.isNotBlank() }.take(6).joinToString("\n"),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "文件：" + CrashGuard.fileOf(ctx).absolutePath,
                        fontSize = 8.5.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row {
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "ComponentVault 崩溃日志")
                                putExtra(Intent.EXTRA_TEXT, crash)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { ctx.startActivity(send) }
                        }) { Text("发送日志", fontSize = 11.sp) }
                        TextButton(onClick = {
                            CrashGuard.clear(ctx)
                            crashText = null
                        }) { Text("清除", fontSize = 11.sp) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("立创元件库", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "无需 API Key。输入型号、关键词或立创号即检索，返回规格参数、封装、立创号，并生成可直达商品页的链接。",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            testing = true
                            testMsg = null
                            scope.launch {
                                when (val r = LcscResolver.resolve("STM32F103C8T6", forceRefresh = true)) {
                                    is LcscResolver.Result.Hits -> {
                                        testOk = true
                                        val h = r.hits.firstOrNull()
                                        testMsg = "正常 · 命中 ${r.hits.size} 条 · 示例 ${h?.lcscCode ?: "-"}"
                                    }
                                    is LcscResolver.Result.Empty -> {
                                        testOk = false
                                        testMsg = "接口可达，但无结果"
                                    }
                                    is LcscResolver.Result.Failed -> {
                                        testOk = false
                                        testMsg = "失败：${r.reason}"
                                    }
                                }
                                cacheSize = LcscResolver.cacheSize()
                                testing = false
                            }
                        },
                        enabled = !testing
                    ) { Text(if (testing) "测试中…" else "测试连接") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        testMsg ?: "未测试",
                        fontSize = 10.5.sp,
                        color = when {
                            testMsg == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            testOk -> Color(0xFF059669)
                            else -> Color(0xFFB45309)
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "缓存 $cacheSize 组关键词",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        LcscResolver.clearCache()
                        cacheSize = 0
                        testMsg = "缓存已清空"
                        testOk = true
                    }) { Text("清除缓存", fontSize = 11.sp) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        LcscRefreshCard()

        Spacer(Modifier.height(14.dp))
        IconMaintenanceCard()

        Spacer(Modifier.height(14.dp))

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                InfoRow("应用版本", BuildConfig.VERSION_NAME)
                InfoRow("数据存储", "本机 Room 数据库，不上传")
                InfoRow("补全数据源", "立创元件库 + 内置型号库 + 规则解析")
                InfoRow("商品页链接", "item.szlcsc.com/{商品ID}.html")
                InfoRow("局域网端口", LanServerService.DEFAULT_PORT.toString())
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "本应用为自用库存工具，器件参数为参考值，关键设计请以原厂数据手册为准。",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 立创信息批量刷新。
 *
 * 解决的是「老库存没有立创号」这件事：没有号 → 抓不到图、没有商品页、没有手册、没有单价。
 * 这个卡片用型号（没型号就用名称）把它们逐条重新查一遍，查到就把号和图一起补上。
 * 已经有立创号的器件不参与，避免覆盖用户核对过的对应关系。
 */
@Composable
private fun LcscRefreshCard() {
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf(-1) }
    var prog by remember { mutableStateOf<VaultRepository.RefreshBatch?>(null) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        missing = Graph.repo.allParts().count {
            (it.domain == Domains.ELECTRONICS || it.domain == Domains.HARDWARE) &&
                it.lcscCode.isBlank()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("立创信息", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "按型号或名称重新检索，命中后补上立创号、商品页、封装、单价、手册与图片。" +
                    "只查还没有立创号的器件。",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            InfoRow(
                "待补立创号",
                when {
                    missing < 0 -> "统计中…"
                    missing == 0 -> "全部都有"
                    else -> "$missing 件"
                }
            )

            val p = prog
            if (p != null && p.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "已查 ${p.done} / ${p.total} · 命中 ${p.hit}",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (p.lastMessage.isNotBlank()) {
                    Text(
                        p.lastMessage,
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (p != null && !p.running) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "完成：命中 ${p.hit} / ${p.total}",
                    fontSize = 10.5.sp,
                    color = if (p.hit > 0) Color(0xFF059669) else Color(0xFFD97706)
                )
                if (p.lastMessage.isNotBlank()) {
                    Text(
                        p.lastMessage,
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                enabled = !running && (missing > 0),
                onClick = {
                    running = true
                    scope.launch {
                        val r = Graph.repo.refreshMissingLcsc { prog = it }
                        prog = r
                        running = false
                        missing = Graph.repo.allParts().count {
                            (it.domain == Domains.ELECTRONICS || it.domain == Domains.HARDWARE) &&
                                it.lcscCode.isBlank()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "检索中…" else "批量按型号检索")
            }
        }
    }
}

/**
 * 图标与存储维护。
 *
 * 存在的理由：v0.2.1 及更早录入的器件没有图标字段，
 * 升级后需要一次性把能抓的图补上；另外换图/删件会留下孤儿文件。
 */
@Composable
private fun IconMaintenanceCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(0) }
    var missing by remember { mutableStateOf(0) }
    var bytes by remember { mutableStateOf(0L) }
    var prog by remember { mutableStateOf<VaultRepository.BackfillProgress?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    suspend fun refresh() {
        count = Graph.repo.countIcons()
        missing = Graph.repo.missingIconCount()
        bytes = IconStore.usedBytes(ctx)
    }
    LaunchedEffect(Unit) { refresh() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("图标与存储", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "图标一律在入库时下载并缩成 192px 存本机，列表渲染读本地文件，断网也能看。",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            InfoRow("已缓存图标", "$count 张")
            InfoRow("占用空间", String.format("%.1f MB", bytes / 1024.0 / 1024.0))
            InfoRow("待补图标", "$missing 个")

            val p = prog
            if (p != null && p.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "补图中 ${p.done} / ${p.total} · 成功 ${p.ok}",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            msg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 10.5.sp, color = Color(0xFF059669))
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running && missing > 0,
                    onClick = {
                        running = true
                        msg = null
                        scope.launch {
                            val r = Graph.repo.backfillIcons { progress -> prog = progress }
                            msg = if (r.total == 0) "没有需要补图的器件"
                            else "完成：成功 ${r.ok} / ${r.total}"
                            running = false
                            refresh()
                        }
                    }
                ) { Text(if (running) "补图中…" else "为存量器件补图标") }
                OutlinedButton(
                    enabled = !running,
                    onClick = {
                        scope.launch {
                            val n = Graph.repo.purgeOrphanIcons()
                            msg = "已清理 $n 个无主图标文件"
                            refresh()
                        }
                    }
                ) { Text("清理无主图标", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(value, fontSize = 11.5.sp)
    }
}

/** 二级页顶部返回行 */
@Composable
private fun SubScreenBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("← 返回", fontSize = 12.sp) }
        Text(
            title,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 「我的」页里的工具入口 */
@Composable
private fun ToolEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(
                subtitle,
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
