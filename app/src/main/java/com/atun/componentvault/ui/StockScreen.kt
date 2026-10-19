package com.atun.componentvault.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atun.componentvault.Graph
import com.atun.componentvault.data.Part
import kotlinx.coroutines.launch

@Composable
fun StockScreen() {
    val flow = remember { Graph.repo.observeParts() }
    val parts by flow.collectAsStateWithLifecycle(initialValue = emptyList())

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    var adjusting by remember { mutableStateOf<Part?>(null) }

    val categories = remember(parts) {
        listOf("全部") + parts.map { it.category }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val filtered = remember(parts, query, category) {
        val q = query.trim().lowercase()
        parts.filter { p ->
            (category == "全部" || p.category == category) &&
                (q.isEmpty() || listOf(
                    p.mpn, p.name, p.category, p.packageName, p.tags,
                    p.internalCode, p.lcscCode, p.location
                ).any { it.lowercase().contains(q) })
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 14.dp, bottom = 20.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            StockHeader(
                kinds = parts.size,
                qty = parts.sumOf { it.qty },
                low = parts.count { it.isLow },
                out = parts.count { it.isOut }
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("搜型号 / 封装 / 标签 / 仓位", style = MaterialTheme.typography.bodySmall)
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = c },
                        label = { Text(c, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item { EmptyState(query) }
        } else {
            items(filtered, key = { it.id }) { p ->
                PartRow(p) { adjusting = p }
            }
        }
    }

    adjusting?.let { part ->
        StockAdjustDialog(part = part, onDismiss = { adjusting = null })
    }
}

@Composable
private fun StockHeader(kinds: Int, qty: Int, low: Int, out: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "元器件库存",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                StatCell("品种", kinds.toString())
                StatCell("总件数", qty.toString())
                StatCell("低库存", low.toString(), highlight = low > 0)
                StatCell("缺货", out.toString(), highlight = out > 0)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, highlight: Boolean = false) {
    Column {
        Text(
            value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) Color(0xFFFDE68A) else MaterialTheme.colorScheme.onPrimary
        )
        Text(
            label,
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun PartRow(p: Part, onClick: () -> Unit) {
    val qtyColor = when {
        p.isOut -> Color(0xFFDC2626)
        p.isLow -> Color(0xFFD97706)
        else -> Color(0xFF059669)
    }
    val qtyBg = when {
        p.isOut -> Color(0xFFFEE2E2)
        p.isLow -> Color(0xFFFEF3C7)
        else -> Color(0xFFD1FAE5)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(9.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = p.packageName.substringBefore(' ').take(6).ifBlank { "—" },
                    fontSize = if (p.packageName.length > 6) 9.sp else 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = p.mpn.ifBlank { p.internalCode },
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOf(p.name, p.packageName).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (p.tags.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = p.tagList.take(3).joinToString("  ") { "#$it" },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .background(qtyBg, RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        p.qty.toString(),
                        color = qtyColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    p.location.ifBlank { "未标仓位" },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyState(query: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (query.isBlank()) "库存还是空的，去「快录」加第一个器件" else "没有匹配「$query」的器件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StockAdjustDialog(part: Part, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("1") }
    var inbound by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整库存 · ${part.mpn.ifBlank { part.internalCode }}") },
        text = {
            Column {
                Text(
                    "当前 ${part.qty} 件，仓位 ${part.location.ifBlank { "未标" }}",
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
                    onValueChange = { s -> amount = s.filter { it.isDigit() }.take(6) },
                    label = { Text("数量") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = amount.toIntOrNull() ?: 0
                if (n <= 0) {
                    error = "请输入大于 0 的数量"
                    return@TextButton
                }
                val delta = if (inbound) n else -n
                scope.launch {
                    val r = Graph.repo.moveStock(
                        partId = part.id,
                        type = if (inbound) "IN" else "OUT",
                        delta = delta,
                        note = "手动调整"
                    )
                    if (r.ok) onDismiss() else error = r.message
                }
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
