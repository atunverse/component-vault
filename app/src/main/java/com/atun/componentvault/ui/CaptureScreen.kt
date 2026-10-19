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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atun.componentvault.Graph
import com.atun.componentvault.data.Part
import com.atun.componentvault.enrich.Enricher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val AMBER = Color(0xFFB45309)

@Composable
fun CaptureScreen() {
    var input by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Enricher.Candidate?>(null) }
    var savedName by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf<Enricher.Remote?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 本地推断：同步、毫秒级，先用它撑住界面
    val localCandidates = remember(input) {
        if (input.isBlank()) emptyList() else Enricher.localSuggest(input)
    }

    // 联网检索：防抖 350ms，输入框每变一次就取消上一次
    LaunchedEffect(input) {
        picked = null
        remote = null
        val q = input.trim()
        if (q.length < 2) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(350)
        remote = Enricher.lcscSuggest(q)
        loading = false
    }

    LaunchedEffect(savedName) {
        if (savedName.isNotEmpty()) {
            delay(2600)
            savedName = ""
        }
    }

    // 立创结果优先；本地推断只补充"立创没有的型号"
    val remoteItems = (remote as? Enricher.Remote.Hits)?.items.orEmpty()
    val candidates = remember(remoteItems, localCandidates) {
        if (remoteItems.isEmpty()) localCandidates
        else remoteItems + localCandidates.filter { lc ->
            remoteItems.none { it.guess.name.equals(lc.guess.name, ignoreCase = true) }
        }
    }.take(6)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text("快速录入", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "只输型号或代号，参数、封装、立创号自动补全",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("型号 / 代号 / 立创号") },
            placeholder = { Text("STM32F103C8T6 / SS34 / C8734 / 10k 0603") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "联网查立创元件库，返回规格参数、封装、立创号与商品页链接　·　断网时退回本地规则",
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (savedName.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = Color(0xFFD1FAE5),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "已入库：$savedName",
                    modifier = Modifier.padding(12.dp),
                    color = Color(0xFF065F46),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 联网状态条：明确告诉用户"数据是哪来的"，这比一个转圈动画重要
        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在查立创商城…",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            remote is Enricher.Remote.Failed -> Text(
                "立创检索失败（${(remote as Enricher.Remote.Failed).reason}），以下为本地推断结果",
                fontSize = 10.5.sp,
                color = AMBER,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            remote is Enricher.Remote.Empty -> Text(
                "立创未收录「${input.trim()}」，可换关键词或直接手填",
                fontSize = 10.5.sp,
                color = AMBER,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            remote is Enricher.Remote.Hits -> {
                val h = remote as Enricher.Remote.Hits
                val pkgs = h.items.map { it.guess.packageName }.filter { it.isNotBlank() }.distinct()
                Text(
                    if (pkgs.size > 1) "立创命中 ${h.items.size} 个候选 · ${pkgs.size} 种封装，请确认后选择"
                    else "立创命中 ${h.items.size} 个候选" + if (h.fromCache) "（缓存）" else "",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        if (picked == null && candidates.isNotEmpty()) {
            Text(
                "点选候选，再确认数量入库",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            candidates.forEach { c ->
                CandidateCard(c) { picked = c }
                Spacer(Modifier.height(8.dp))
            }
        }

        picked?.let { c ->
            EditForm(
                candidate = c,
                defaultMpn = if (c.mpn().isNotBlank()) c.mpn() else input.uppercase(),
                onSave = { part ->
                    scope.launch {
                        Graph.repo.create(part)
                        savedName = part.mpn.ifBlank { part.internalCode }
                        input = ""
                        picked = null
                    }
                }
            )
        }
    }
}

private fun Enricher.Candidate.mpn(): String = guess.name

@Composable
private fun CandidateCard(c: Enricher.Candidate, onClick: () -> Unit) {
    val g = c.guess
    val ctx = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        g.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOf(g.category, g.packageName).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${(g.confidence * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (c.guessed) AMBER else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        c.matchKind.ifBlank { c.source },
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (g.params.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                g.params.entries.take(4).forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            k,
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(72.dp),
                            maxLines = 1
                        )
                        Text(
                            v,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
            }

            // 立创号 + 商品页跳转：这一行是本方案的核心交付
            if (c.lcscCode.isNotBlank() || c.hasLcscLink) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.lcscCode.isNotBlank()) {
                        Text(
                            c.lcscCode,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    RoundedCornerShape(5.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    if (c.stock > 0) {
                        Text(
                            "现货 ${c.stock}",
                            fontSize = 10.sp,
                            color = Color(0xFF059669)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (c.hasLcscLink) {
                        Text(
                            "立创商城 ↗",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { openUrl(ctx, c.productUrl) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (g.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    g.tags.joinToString("  ") { "#$it" },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (c.guessed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "参数为规则推断值，请核对后再入库",
                    fontSize = 9.5.sp,
                    color = AMBER
                )
            }
        }
    }
}

@Composable
private fun EditForm(
    candidate: Enricher.Candidate,
    defaultMpn: String,
    onSave: (Part) -> Unit
) {
    val g = candidate.guess
    val ctx = LocalContext.current
    var mpn by remember(candidate) { mutableStateOf(defaultMpn) }
    var qtyText by remember(candidate) { mutableStateOf("10") }
    var location by remember(candidate) { mutableStateOf("") }
    var priceText by remember(candidate) {
        mutableStateOf(
            if (candidate.unitPrice > 0) String.format("%.4f", candidate.unitPrice).trimEnd('0').trimEnd('.')
            else ""
        )
    }
    var note by remember(candidate) { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("入库确认", style = MaterialTheme.typography.titleMedium)

            if (candidate.hasLcscLink) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "立创 ${candidate.lcscCode}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "打开商品页 ↗",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { openUrl(ctx, candidate.productUrl) }
                            .padding(vertical = 2.dp)
                    )
                    if (candidate.datasheetUrl.isNotBlank()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "数据手册 ↗",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { openUrl(ctx, candidate.datasheetUrl) }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = mpn,
                onValueChange = { mpn = it },
                label = { Text("型号（可留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = g.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = g.packageName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("封装") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { s -> qtyText = s.filter { it.isDigit() }.take(6) },
                    label = { Text("数量") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("仓位") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { s -> priceText = s.filter { it.isDigit() || it == '.' } },
                    label = { Text("单价（元）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (g.params.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "识别到的参数",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    g.params.entries.joinToString("　") { "${it.key} ${it.value}" },
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val paramsJson = buildJsonObject {
                        g.params.forEach { (k, v) -> put(k, v) }
                    }.toString()
                    onSave(
                        Part(
                            internalCode = "",
                            mpn = mpn.trim(),
                            name = g.name,
                            category = g.category,
                            packageName = g.packageName,
                            brand = candidate.brand,
                            lcscCode = candidate.lcscCode,
                            lcscProductId = candidate.productId,
                            datasheetUrl = candidate.datasheetUrl,
                            paramsJson = paramsJson,
                            tags = g.tags.joinToString(","),
                            summary = g.summary,
                            qty = qtyText.toIntOrNull() ?: 0,
                            lowThreshold = 10,
                            location = location.trim(),
                            price = priceText.toDoubleOrNull() ?: 0.0,
                            note = note.trim()
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("确认入库") }
        }
    }
}
