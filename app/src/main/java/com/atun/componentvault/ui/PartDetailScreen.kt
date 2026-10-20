package com.atun.componentvault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atun.componentvault.Graph
import com.atun.componentvault.data.Domains
import com.atun.componentvault.data.Part
import com.atun.componentvault.data.Txn
import com.atun.componentvault.data.VaultRepository
import com.atun.componentvault.data.fmtQty
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 元器件详情页。
 *
 * 数据用 observeById 订阅单条记录 —— 在详情页里入库、改资料、换图标之后，
 * 页面上的数字自动刷新，不需要手动回传刷新信号。
 */
@Composable
fun PartDetailScreen(partId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember(partId) { Graph.repo.observePart(partId) }
    val part by flow.collectAsStateWithLifecycle(initialValue = null)

    var adjusting by remember { mutableStateOf(false) }
    var pickingIcon by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var txns by remember { mutableStateOf<List<Txn>>(emptyList()) }
    var refreshTick by remember { mutableStateOf(0) }
    var lcscBusy by remember { mutableStateOf(false) }
    var lcscMsg by remember { mutableStateOf<String?>(null) }
    var lcscOk by remember { mutableStateOf(false) }
    // 模糊命中时立创返回的疑似项：让用户点一个，不用自己再去立创网站翻
    var lcscOptions by remember { mutableStateOf<List<VaultRepository.LcscOption>>(emptyList()) }
    var pickingCode by remember { mutableStateOf("") }

    val p = part
    LaunchedEffect(partId, p?.qty, p?.updatedAt, refreshTick) {
        txns = Graph.repo.txnsOf(partId)
    }
    // 换器件时把上一件的检索反馈清掉，否则会让人以为新器件也查过了
    LaunchedEffect(partId) {
        lcscMsg = null
        lcscOptions = emptyList()
        lcscOk = false
    }

    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("载入中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------- 顶栏
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                p.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onEdit) { Text("编辑") }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 24.dp)
        ) {
            // ------------------------------------------------------ 信息卡
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp)) {
                    PartIcon(
                        part = p,
                        size = 112.dp,
                        corner = 12.dp,
                        onClick = { pickingIcon = true }
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.mpn.ifBlank { "（无型号）" },
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            p.name.ifBlank { "未命名" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Pill(Domains.label(p.domain), MaterialTheme.colorScheme.primary)
                            if (p.category.isNotBlank()) Pill(p.category, MaterialTheme.colorScheme.secondary)
                            if (p.packageName.isNotBlank()) Pill(p.packageName, Color(0xFF64748B))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOf(p.internalCode, p.brand).filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------------ 库存
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    p.qtyText,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        p.isOut -> Color(0xFFDC2626)
                                        p.isLow -> Color(0xFFD97706)
                                        else -> Color(0xFF059669)
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    p.unit,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Text(
                                when {
                                    p.isOut -> "已缺货"
                                    p.isLow -> "低于预警值 ${p.lowText}${p.unit}"
                                    p.lowThreshold <= 0.0 -> "未设预警"
                                    else -> "库存正常"
                                },
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                p.location.ifBlank { "未标仓位" },
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (p.price > 0) {
                                Text(
                                    "单价 ¥${p.price} · 小计 ¥${"%.2f".format(p.amount)}",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { adjusting = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("入库 / 出库") }
                        OutlinedButton(
                            onClick = { pickingIcon = true },
                            modifier = Modifier.weight(0.8f)
                        ) { Text(if (p.hasIcon) "换图标" else "设图标") }
                    }
                }
            }

            if (p.domain == Domains.FILAMENT) {
                Spacer(Modifier.height(12.dp))
                FilamentCard(p)
            }

            // ------------------------------------------------------ 链接
            val productUrl = if (p.hasLcscAny) Graph.repo.productUrl(p) else ""
            val canRefreshLcsc = p.domain == Domains.ELECTRONICS || p.domain == Domains.HARDWARE
            if (productUrl.isNotBlank() || p.datasheetUrl.isNotBlank() ||
                p.lcscCode.isNotBlank() || canRefreshLcsc
            ) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        SectionTitle("外部链接")
                        if (productUrl.isNotBlank()) {
                            LinkRow(
                                "立创商城",
                                if (p.hasLcscProduct) "商品页 · ${p.lcscCode.ifBlank { p.lcscProductId.toString() }}"
                                else "按 ${p.lcscCode} 搜索（非直达）"
                            ) { openUrl(ctx, productUrl) }
                        }
                        if (p.datasheetUrl.isNotBlank()) {
                            LinkRow("数据手册", p.datasheetUrl) { openUrl(ctx, p.datasheetUrl) }
                        }
                        if (p.lcscCode.isNotBlank()) {
                            LinkRow("复制立创号", p.lcscCode) { copyText(ctx, p.lcscCode) }
                        }
                        if (productUrl.isNotBlank()) {
                            LinkRow("复制商品链接", "分享给电脑端用") { copyText(ctx, productUrl) }
                        }
                        // 老库存往往没有立创号，图标/价格/手册也就一直空着。
                        // 这一行是它们的修复入口：用型号或名称重新去立创查一遍。
                        if (p.domain == Domains.ELECTRONICS || p.domain == Domains.HARDWARE) {
                            LinkRow(
                                "刷新立创信息",
                                when {
                                    lcscBusy -> "正在按型号检索…"
                                    lcscMsg != null -> lcscMsg!!
                                    p.lcscCode.isBlank() -> "没有立创号？按「${p.mpn.ifBlank { p.name }}」查一次"
                                    else -> "重新拉取封装 / 价格 / 手册 / 图片"
                                }
                            ) {
                                if (!lcscBusy) {
                                    lcscBusy = true
                                    lcscMsg = null
                                    lcscOptions = emptyList()
                                    scope.launch {
                                        val r = Graph.repo.refreshLcsc(p.id)
                                        lcscOk = r.ok
                                        lcscMsg = r.message
                                        lcscOptions = if (r.ok) emptyList() else r.options
                                        lcscBusy = false
                                        refreshTick++
                                    }
                                }
                            }
                            if (lcscMsg != null) {
                                Text(
                                    lcscMsg!!,
                                    fontSize = 9.5.sp,
                                    color = if (lcscOk) Color(0xFF059669) else Color(0xFFD97706),
                                    modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                                )
                            }
                            if (lcscOptions.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                lcscOptions.forEach { opt ->
                                    LcscOptionRow(
                                        opt = opt,
                                        busy = pickingCode == opt.lcscCode
                                    ) {
                                        if (pickingCode.isBlank() && !lcscBusy) {
                                            pickingCode = opt.lcscCode
                                            scope.launch {
                                                val r = Graph.repo.applyLcscOption(p.id, opt.lcscCode)
                                                lcscOk = r.ok
                                                lcscMsg = r.message
                                                if (r.ok) lcscOptions = emptyList()
                                                pickingCode = ""
                                                refreshTick++
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        }
                        // 五金件命中率不稳，给一个能自己去找的入口
                        if (p.domain == Domains.HARDWARE) {
                            LinkRow("立创工业品", "去 MRO 站搜同名规格") {
                                openUrl(ctx, "https://mro.szlcsc.com/")
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------ 参数
            val params = remember(p.paramsJson) { parseParams(p.paramsJson) }
            if (params.isNotEmpty() || p.spec.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        SectionTitle("规格参数")
                        if (p.spec.isNotBlank()) {
                            Text(
                                p.spec,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        params.forEach { (k, v) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(
                                    k,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(96.dp)
                                )
                                Text(v, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------ 标签
            if (p.tagList.isNotEmpty() || p.summary.isNotBlank() || p.note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (p.tagList.isNotEmpty()) {
                            SectionTitle("标签")
                            Text(
                                p.tagList.joinToString("  ") { "#$it" },
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        if (p.summary.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                p.summary,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (p.note.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            SectionTitle("备注")
                            Text(p.note, fontSize = 11.5.sp)
                        }
                    }
                }
            }

            // ------------------------------------------------------ 流水
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    SectionTitle("库存流水 · 最近 ${txns.size} 条")
                    if (txns.isEmpty()) {
                        Text(
                            "暂无记录",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        txns.forEach { t ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.width(78.dp)) {
                                    Text(t.typeLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        timeText(t.createdAt),
                                        fontSize = 9.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    t.deltaText,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        t.type == "EDIT" -> MaterialTheme.colorScheme.onSurfaceVariant
                                        t.delta > 0 -> Color(0xFF059669)
                                        else -> Color(0xFFDC2626)
                                    },
                                    modifier = Modifier.width(58.dp)
                                )
                                Text(
                                    if (t.type == "EDIT") "" else "${fmtQty(t.before)} → ${fmtQty(t.after)}",
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(84.dp)
                                )
                                Text(
                                    t.note,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            TextButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("删除该器件", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "v0.3.0 · 点击图标可拍照/从相册换图",
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    if (adjusting) {
        StockAdjustDialog(part = p, onDismiss = { adjusting = false })
    }
    if (pickingIcon) {
        IconPickerDialog(part = p, onDismiss = { pickingIcon = false; refreshTick++ })
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除器件") },
            text = {
                Text(
                    "将删除「${p.displayTitle}」及其 ${txns.size} 条流水记录，不可恢复。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        Graph.repo.deleteWithIcon(p)
                        onBack()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 9.5.sp, color = color)
    }
}

/**
 * 一条立创疑似项。整行可点 —— 手机上点小字不算手感，整行热区才够。
 * 副标题按「封装 · 品牌 · 立创号 · 单价」拼，用户判断依据主要看封装对不对。
 */
@Composable
private fun LcscOptionRow(
    opt: VaultRepository.LcscOption,
    busy: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    opt.mpn.ifBlank { opt.lcscCode },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = listOfNotNull(
                    opt.packageName.takeIf { it.isNotBlank() },
                    opt.brand.takeIf { it.isNotBlank() },
                    opt.lcscCode.takeIf { it.isNotBlank() },
                    if (opt.price > 0.0) "¥${fmtQty(opt.price)}" else null
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (busy) "应用…" else "选它",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                subtitle,
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text("↗", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 出入库弹窗。从 StockScreen 提到这里，让列表和详情页共用一份。
 */
@Composable
fun StockAdjustDialog(part: Part, onDismiss: () -> Unit) {
    var amount by remember(part.id) { mutableStateOf("1") }
    var inbound by remember(part.id) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${if (inbound) "入库" else "出库"} · ${part.displayTitle}", maxLines = 1) },
        text = {
            Column {
                if (part.hasLcscCodeForDisplay()) {
                    Text(
                        "立创 ${part.lcscCode}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "当前 ${part.qtyText} ${part.unit}，仓位 ${part.location.ifBlank { "未标" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = inbound,
                        onClick = { inbound = true },
                        label = { Text("入库", style = MaterialTheme.typography.labelSmall) }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = !inbound,
                        onClick = { inbound = false },
                        label = { Text("出库", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { s ->
                        amount = s.filter { it.isDigit() || it == '.' }.take(8)
                    },
                    label = { Text("数量（${part.unit}）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (part.domain == Domains.FILAMENT) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "耗材按克计，可直接填 120.5",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val n = amount.toDoubleOrNull() ?: 0.0
                if (n <= 0.0) {
                    error = "请输入大于 0 的数量"
                    return@TextButton
                }
                busy = true
                val delta = if (inbound) n else -n
                scope.launch {
                    val r = Graph.repo.moveStock(
                        partId = part.id,
                        type = if (inbound) "IN" else "OUT",
                        delta = delta,
                        note = if (inbound) "手动入库" else "手动出库"
                    )
                    busy = false
                    if (r.ok) onDismiss() else error = r.message
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun Part.hasLcscCodeForDisplay(): Boolean = lcscCode.isNotBlank()

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("vault", text))
}

private fun parseParams(json: String): List<Pair<String, String>> = runCatching {
    Json.parseToJsonElement(json).jsonObject.entries.map { it.key to it.value.jsonPrimitive.content }
}.getOrDefault(emptyList())

private fun timeText(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts))
