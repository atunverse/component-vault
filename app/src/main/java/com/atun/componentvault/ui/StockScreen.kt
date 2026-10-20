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
import androidx.compose.runtime.LaunchedEffect
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
import com.atun.componentvault.data.FilamentInfo
import com.atun.componentvault.data.Part
import com.atun.componentvault.data.fmtQty
import com.atun.componentvault.enrich.SearchTerms

/** 库存 Tab 内的三级页面栈。只有一个 Long 参数，不需要引 navigation-compose */
private sealed interface Route {
    data object List : Route
    data class Detail(val id: Long) : Route
    data class Edit(val id: Long) : Route
}

/**
 * 单个物料域的库存页。
 *
 * 底部导航的三个分类页共用这一个实现，只换 [domain]：
 * 电子元件 / 五金工具 / 3D 打印耗材。搜索词由根节点持有，
 * 所以三个页面的搜索框位置、内容、行为完全一致 —— 也就是「同一位置检索所有分类」。
 */
@Composable
fun StockScreen(
    domain: String,
    query: String,
    onQuery: (String) -> Unit,
    /**
     * 当前是否停在列表页。根节点用它在列表页显示右下角加号、进详情页时收起 ——
     * 详情页右下角浮一个「录入」按钮只会挡住流水与删除按钮。
     */
    onListVisible: (Boolean) -> Unit = {}
) {
    var route by remember { mutableStateOf<Route>(Route.List) }
    var adjusting by remember { mutableStateOf<Part?>(null) }

    LaunchedEffect(route) { onListVisible(route == Route.List) }

    BackHandler(enabled = route != Route.List) {
        route = when (val r = route) {
            is Route.Edit -> Route.Detail(r.id)
            else -> Route.List
        }
    }

    when (val r = route) {
        Route.List -> StockList(
            domain = domain,
            query = query,
            onQuery = onQuery,
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
private fun StockList(
    domain: String,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (Part) -> Unit,
    onAdjust: (Part) -> Unit
) {
    val flow = remember { Graph.repo.observeParts() }
    val parts by flow.collectAsStateWithLifecycle(initialValue = emptyList())

    var category by remember(domain) { mutableStateOf("全部") }
    var onlyLow by remember { mutableStateOf(false) }

    val searching = query.isNotBlank()

    // 搜到的结果跨域；没搜索时只看当前分类页的域
    val scoped = remember(parts, domain, searching) {
        if (searching) parts else parts.filter { it.domain == domain }
    }

    val categories = remember(scoped, domain) {
        listOf("全部") + scoped.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }

    val filtered = remember(scoped, query, category, onlyLow, searching) {
        // 空格分开的多个词按 AND 处理：`PLA 黑` 能同时命中「PLA 线材」的名称与「黑色」的规格。
        // 之前是整串 contains，带空格就永远搜不到，等于把搜索框变成了「只认连续子串」。
        val terms = SearchTerms.split(query)
        scoped.filter { p ->
            (searching || category == "全部" || p.category == category) &&
                (!onlyLow || p.isLow || p.isOut) &&
                SearchTerms.matchesAny(
                    listOf(
                        p.mpn, p.name, p.category, p.packageName, p.tags,
                        p.internalCode, p.lcscCode, p.location, p.spec, p.brand, p.note
                    ),
                    terms
                )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部留出 96dp：右下角悬浮着录入按钮，最后一条不能被它压住
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            StockHeader(
                title = if (searching) "全库搜索" else Domains.pageTitle(domain),
                kinds = scoped.size,
                qtyLabel = if (domain == Domains.FILAMENT && !searching) "总剩余" else "总数量",
                qty = scoped.sumOf { it.qty },
                low = scoped.count { it.isLow },
                out = scoped.count { it.isOut }
            )
        }

        // 搜索框位置固定在统计卡下方，三个分类页一模一样
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "搜全库：型号 / 名称 / 规格 / 标签 / 仓位",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }

        if (searching) {
            item {
                Text(
                    "在全部 ${parts.size} 件物料里找到 ${filtered.size} 条",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            if (domain == Domains.FILAMENT) {
                item { FilamentSummary(parts.filter { it.domain == Domains.FILAMENT }) }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(categories) { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c, fontSize = 10.5.sp) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = onlyLow,
                            onClick = { onlyLow = !onlyLow },
                            label = { Text("只看预警", fontSize = 10.5.sp) }
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item { EmptyState(query, parts.isEmpty(), domain) }
        } else {
            items(filtered, key = { it.id }) { p ->
                PartRow(
                    p = p,
                    showDomain = searching,
                    onOpen = { onOpen(p) },
                    onAdjust = { onAdjust(p) }
                )
            }
        }
    }
}

@Composable
private fun StockHeader(
    title: String,
    kinds: Int,
    qtyLabel: String,
    qty: Double,
    low: Int,
    out: Int
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                StatCell("品种", kinds.toString())
                StatCell(qtyLabel, fmtQty(qty))
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
private fun PartRow(
    p: Part,
    showDomain: Boolean,
    onOpen: () -> Unit,
    onAdjust: () -> Unit
) {
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
    val subtitle = if (p.domain == Domains.FILAMENT) {
        listOf(FilamentInfo.subtitle(p), p.location.ifBlank { "未标仓位" }).joinToString(" · ")
    } else {
        listOf(p.name, p.packageName, p.lcscCode).filter { it.isNotBlank() }.joinToString(" · ")
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
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showDomain) {
                        Text(
                            Domains.label(p.domain),
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (p.domain == Domains.FILAMENT) {
                        ColorDot(FilamentInfo.colorHex(p), 9.dp)
                        Spacer(Modifier.width(5.dp))
                        val pct = FilamentInfo.percent(p)
                        Text(
                            if (pct != null) "剩余 ${(pct * 100).toInt()}%" else "剩余 ${fmtQty(p.qty)}g",
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            p.location.ifBlank { "未标仓位" },
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
private fun EmptyState(query: String, empty: Boolean, domain: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            when {
                empty -> "库存还是空的，去「快录」加第一件物料"
                query.isNotBlank() -> "没有匹配「$query」的物料"
                domain == Domains.FILAMENT -> "还没有耗材，去「快录」选「3D 耗材」加一盘"
                else -> "当前筛选下没有物料"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
