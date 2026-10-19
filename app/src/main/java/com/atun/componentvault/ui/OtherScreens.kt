package com.atun.componentvault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atun.componentvault.BuildConfig
import com.atun.componentvault.Graph
import com.atun.componentvault.data.Part
import com.atun.componentvault.net.LanServerService
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- BOM 分析

private data class BomLine(val index: Int, val keyword: String, val need: Int, val raw: String)

private data class BomMatch(
    val line: BomLine,
    val part: Part?,
    /** OK / LOW / MISSING */
    val state: String
)

@Composable
fun BomScreen() {
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
                Text("需 ${m.line.need}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "存 ${m.part?.qty ?: 0}",
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
        val need = cells.drop(1).mapNotNull { it.toIntOrNull() }.lastOrNull() ?: 1
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
fun LanScreen() {
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
fun MeScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("vault", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("lcsc_key", "") ?: "") }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) {
            delay(1600)
            saved = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text("我的", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(14.dp))

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("立创商城开放平台", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "填入 Key 后可联网补全参数与数据手册；留空则只用本地规则解析。额度 1000 次/日、200 次/分。",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        prefs.edit().putString("lcsc_key", apiKey).apply()
                        saved = true
                    }) { Text(if (saved) "已保存" else "保存") }
                    OutlinedButton(onClick = {
                        apiKey = ""
                        prefs.edit().remove("lcsc_key").apply()
                    }) { Text("清除") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                InfoRow("应用版本", BuildConfig.VERSION_NAME)
                InfoRow("数据存储", "本机 Room 数据库，不上传")
                InfoRow("补全数据源", "内置型号库 + 规则解析" + if (apiKey.isNotBlank()) " + 立创 API" else "")
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
