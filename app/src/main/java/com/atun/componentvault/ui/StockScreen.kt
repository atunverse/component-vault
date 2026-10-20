package com.atun.componentvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.atun.componentvault.data.Domains
import com.atun.componentvault.data.Part
import com.atun.componentvault.data.fmtQty

/** 库存 Tab 内的三级页面栈。只有一个 Long 参数，不需要引 navigation-compose */
private sealed interface Route {
    data object List : Route
    data class Detail(val id: Long) : Route
    data class Edit(val id: Long) : Route
}

@Composable
fun StockScreen() {
    var route by remember { mutableStateOf<Route>(Route.List) }
    var adjusting by remember { mutableStateOf<Part?>(null) }

    BackHandler(enabled = route != Route.List) {
        route = when (val r = route) {
            is Route.Edit -> Route.Detail(r.id)
            else -> Route.List
        }
    }

    when (val r = route) {
        Route.List -> StockList(
            onOpen = { route = Route.Detail(it.id) },
            onAdjust = { adjusting = it }
        )

        is Route.Detail -> PartDetailScreen(
            partId = r.id,
            onBack = { route = Route.List },
            onEdit = { route = Route.Edit(r.id) }
        )

        is Route.Edit -> PartEditScreen(
            partId = r.id,
            onBack = { route = Route.Detail(r.id) },
            onSaved = { route = Route.Detail(r.id) }
        )
    }

    adjusting?.let { part ->
        StockAdjustDialog(part = part, onDismiss = { adjusting = null })
    }
}

@Composable
private fun StockList(onOpen: (Part) -> Unit, onAdjust: (Part) -> Unit) {
    val flow = remember { Graph.repo.observeParts() }
    val parts by flow.collectAsStateWithLifecycle(initialValue = emptyList())

    var query by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("全部") }
    var category by remember { mutableStateOf("全部") }
    var onlyLow by remember { mutableStateOf(false) }

    // 切物料域时分类筛选会复位，否则会出现「五金域 + 电阻分类」的零结果死局
    val categories = remember(parts, domain) {
        listOf("全部") + parts
            .filter { domain == "全部" || it.domain == domain }
            .map { it.category }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val filtered = remember(parts, query, domain, category, onlyLow) {
        val q = query.trim().lowercase()
        parts.filter { p ->
            (domain == "全部" || p.domain == domain) &&
                (category == "全部" || p.category == category) &&
                (!onlyLow || p.isLow || p.isOut) &&
                (q.isEmpty() || listOf(
                    p.mpn, p.name, p.category, p.packageName, p.tags,
                    p.internalCode, p.lcscCode, p.location, p.spec, p.brand
                ).any { it.lowercase().contains(q) })
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 20.dp),
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
                    Text("搜型号 / 规格 / 标签 / 仓位", style = MaterialTheme.typography.bodySmall)
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item {
                    FilterChip(
                        selected = domain == "全部",
                        onClick = { domain = "全部"; category = "全部" },
                        label = { Text("全部", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                items(Domains.ALL) { d ->
                    FilterChip(
                        selected = domain == d,
                        onClick = { domain = d; category = "全部" },
                        label = { Text(Domains.label(d), style = MaterialTheme.typography.labelSmall) }
                    )
                }
                item {
                    FilterChip(
                        selected = onlyLow,
                        onClick = { onlyLow = !onlyLow },
                        label = { Text("只看预警", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
        if (categories.size > 1) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c, fontSize = 10.5.sp) }
                        )
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item { EmptyState(query, parts.isEmpty()) }
        } else {
            items(filtered, key = { it.id }) { p ->
                PartRow(
                    p = p,
                    onOpen = { onOpen(p) },
                    onAdjust = { onAdjust(p) }
                )
            }
        }
    }
}

@Composable
private fun StockHeader(kinds: Int, qty: Double, low: Int, out: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "我的物料库",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                StatCell("品种", kinds.toString())
                StatCell("总数量", fmtQty(qty))
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

/**
 * 列表行。**两个独立热区**：
 *  - 整行 → 详情页
 *  - 右侧数量药丸 → 出入库
 *
 * Compose 里子节点的 clickable 会先消费指针事件，父节点不会再触发，
 * 所以不需要手写 consume。但数量区必须给足尺寸（这里 56×44dp），
 * 否则手指要瞄着点。
 */
@Composable
private fun PartRow(p: Part, onOpen: () -> Unit, onAdjust: () -> Unit) {
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
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PartIcon(part = p, size = 46.dp, corner = 9.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = p.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOf(p.name, p.packageName, p.lcscCode)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Domains.label(p.domain),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        p.location.ifBlank { "未标仓位" },
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(6.dp))

            // 数量药丸：独立热区，点它只进出库
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 56.dp, minHeight = 44.dp)
                    .background(qtyBg, RoundedCornerShape(10.dp))
                    .clickable(onClick = onAdjust)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        p.qtyText,
                        color = qtyColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                    Text(
                        p.unit,
                        color = qtyColor.copy(alpha = 0.8f),
                        fontSize = 8.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(query: String, empty: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            when {
                empty -> "库存还是空的，去「快录」加第一件物料"
                query.isNotBlank() -> "没有匹配「$query」的物料"
                else -> "当前筛选下没有物料"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
