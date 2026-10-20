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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atun.componentvault.Graph
import com.atun.componentvault.data.Domains
import com.atun.componentvault.data.Part
import com.atun.componentvault.enrich.Enricher
import com.atun.componentvault.enrich.MaterialParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val AMBER = Color(0xFFB45309)

/**
 * 入库前的统一种子。电子件来自立创候选，五金/耗材来自本地规范名解析，
 * 两者最后都收敛成同一个表单 —— 用户在不同品类下的操作路径保持一致。
 */
private data class CreateSeed(
    val domain: String,
    val mpn: String = "",
    val name: String = "",
    val category: String = "",
    val packageName: String = "",
    val spec: String = "",
    val brand: String = "",
    val unit: String = "个",
    val params: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val lcscCode: String = "",
    val productId: Long = 0,
    val datasheetUrl: String = "",
    val iconUrl: String = "",
    val price: Double = 0.0
)

@Composable
fun CaptureScreen(initialDomain: String = Domains.ELECTRONICS) {
    var domain by remember { mutableStateOf(initialDomain) }
    var input by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf<CreateSeed?>(null) }
    var savedName by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf<Enricher.Remote?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 五金/耗材：本地规则毫秒级出结果，不依赖网络
    val draft = remember(input, domain) {
        if (domain == Domains.HARDWARE || domain == Domains.FILAMENT) MaterialParser.parse(input) else null
    }
    // 电子件：本地推断 + 联网
    val localCandidates = remember(input, domain) {
        if (domain != Domains.ELECTRONICS || input.isBlank()) emptyList()
        else Enricher.localSuggest(input)
    }

    LaunchedEffect(input, domain) {
        seed = null
        remote = null
        loading = false
        val q = input.trim()
        if (q.length < 2) return@LaunchedEffect

        when (domain) {
            Domains.ELECTRONICS -> {
                loading = true
                delay(350)
                remote = Enricher.lcscSuggest(q)
                loading = false
            }
            // 五金：拿规范名去查，目的是捞图片/价格/商品页，不是为了给候选
            Domains.HARDWARE -> {
                val kw = MaterialParser.parse(q)?.searchKeyword
                if (!kw.isNullOrBlank()) {
                    loading = true
                    delay(350)
                    remote = Enricher.lcscSuggest(kw)
                    loading = false
                }
            }
            else -> Unit
        }
    }

    LaunchedEffect(savedName) {
        if (savedName.isNotEmpty()) {
            delay(2600)
            savedName = ""
        }
    }

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
            "电子件查立创；五金按标准件规则翻译；耗材按克管理。立创没有的可以直接手填。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        // 物料域切换
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Domains.ALL.forEach { d ->
                FilterChip(
                    selected = domain == d,
                    onClick = { domain = d; input = ""; seed = null },
                    label = { Text(Domains.label(d), fontSize = 11.sp) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    when (domain) {
                        Domains.ELECTRONICS -> "型号 / 代号 / 立创号"
                        Domains.HARDWARE -> "规格代号，如 M3*8 / M3螺母 / 铜柱 M3*6+6"
                        Domains.FILAMENT -> "耗材，如 PLA 1.75 黑 / PETG 2.85 透明 1kg"
                        else -> "名称 / 型号"
                    }
                )
            },
            placeholder = {
                Text(
                    when (domain) {
                        Domains.ELECTRONICS -> "STM32F103C8T6 / SS34 / C8734"
                        Domains.HARDWARE -> "M3*8"
                        Domains.FILAMENT -> "PLA 1.75 黑"
                        else -> "任意物品"
                    }
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(8.dp))
        Text(
            when (domain) {
                Domains.ELECTRONICS -> "联网查立创元件库，返回规格参数、封装、立创号与商品页链接　·　断网时退回本地规则"
                Domains.HARDWARE -> "先按标准件规则翻译成规范名（M3*8 → DIN912 M3×8），再拿规范名查立创，命中率远高于口语关键词"
                Domains.FILAMENT -> "立创不收录 3D 打印耗材，纯本地按克管理；入库后记「打印消耗」即可"
                else -> "完全手工录入，适合任何立创没有收录的东西"
            },
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

        Spacer(Modifier.height(12.dp))

        // 五金 / 耗材：规范名草稿卡
        if (draft != null) {
            val hit = remoteItems.firstOrNull()
            DraftCard(draft = draft, hit = hit) {
                seed = draft.toSeed(hit)
            }
            Spacer(Modifier.height(10.dp))
        }

        // 联网状态
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
                    "正在查立创…",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            remote is Enricher.Remote.Failed -> Text(
                "立创检索失败（${(remote as Enricher.Remote.Failed).reason}），可用本地规则继续录入",
                fontSize = 10.5.sp,
                color = AMBER,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            remote is Enricher.Remote.Empty && domain == Domains.ELECTRONICS -> Text(
                "立创未收录「${input.trim()}」，可换关键词或直接手填",
                fontSize = 10.5.sp,
                color = AMBER,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            remote is Enricher.Remote.Hits && domain == Domains.ELECTRONICS -> {
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

        if (seed == null && domain == Domains.ELECTRONICS && candidates.isNotEmpty()) {
            Text(
                "点选候选，再确认数量入库",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            candidates.forEach { c ->
                CandidateCard(c) { seed = c.toSeed() }
                Spacer(Modifier.height(8.dp))
            }
        }

        // 其他/手填入口
        if (seed == null && input.isNotBlank() && draft == null &&
            (domain != Domains.ELECTRONICS || candidates.isEmpty())
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    seed = CreateSeed(
                        domain = domain,
                        mpn = input.trim().uppercase(),
                        unit = Domains.defaultUnit(domain)
                    )
                }
            ) {
                Text(
                    "直接手填「${input.trim()}」",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        seed?.let { s ->
            CreateForm(seed = s, onSave = { part ->
                scope.launch {
                    Graph.repo.create(part)
                    savedName = part.mpn.ifBlank { part.internalCode }
                    input = ""
                    seed = null
                }
            })
        }
    }
}

/** MaterialParser 规范名草稿 → 入库种子。hit 是立创按规范名检索到的同款，用来补图/价/链接 */
private fun MaterialParser.Draft.toSeed(hit: Enricher.Candidate?): CreateSeed {
    val params = LinkedHashMap<String, String>(params)
    hit?.guess?.params?.let { p -> p.forEach { (k, v) -> if (!params.containsKey(k)) params[k] = v } }
    return CreateSeed(
        domain = domain,
        mpn = hit?.mpn?.ifBlank { mpn } ?: mpn,
        name = name,
        category = category,
        packageName = hit?.guess?.packageName?.ifBlank { packageName } ?: packageName,
        spec = spec,
        brand = hit?.brand ?: brand,
        unit = hit?.unit?.ifBlank { unit } ?: unit,
        params = params,
        tags = (tags + (hit?.guess?.tags ?: emptyList())).distinct(),
        summary = summary,
        lcscCode = hit?.lcscCode ?: "",
        productId = hit?.productId ?: 0,
        datasheetUrl = hit?.datasheetUrl ?: "",
        iconUrl = hit?.imageUrl ?: "",
        price = hit?.unitPrice ?: 0.0
    )
}

private fun Enricher.Candidate.toSeed(): CreateSeed = CreateSeed(
    domain = Domains.ELECTRONICS,
    mpn = mpn,
    name = guess.name,
    category = guess.category,
    packageName = guess.packageName,
    spec = "",
    brand = brand,
    unit = unit.ifBlank { "个" },
    params = guess.params,
    tags = guess.tags,
    summary = guess.summary,
    lcscCode = lcscCode,
    productId = productId,
    datasheetUrl = datasheetUrl,
    iconUrl = imageUrl,
    price = unitPrice
)

@Composable
private fun DraftCard(
    draft: MaterialParser.Draft,
    hit: Enricher.Candidate?,
    onClick: () -> Unit
) {
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
                        draft.mpn,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOf(draft.name, draft.packageName, draft.unit)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Box(
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        Domains.label(draft.domain),
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (draft.params.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    draft.params.entries.joinToString("　") { "${it.key} ${it.value}" },
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (draft.spec.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    draft.spec,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(draft.basis, fontSize = 9.5.sp, color = AMBER)

            if (hit != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "立创命中 ${hit.lcscCode}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (hit.unitPrice > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "¥${hit.unitPrice}/${hit.unit.ifBlank { "个" }}",
                            fontSize = 10.sp,
                            color = Color(0xFF059669)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (hit.hasLcscLink) {
                        Text(
                            "商品页 ↗",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { openUrl(ctx, hit.productUrl) }
                                .padding(3.dp)
                        )
                    }
                }
            } else if (draft.searchKeyword.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "立创未命中，可直接入库（不影响使用）",
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "点此确认数量入库 →",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

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
                        Text("现货 ${c.stock}", fontSize = 10.sp, color = Color(0xFF059669))
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
                Text("参数为规则推断值，请核对后再入库", fontSize = 9.5.sp, color = AMBER)
            }
        }
    }
}

@Composable
private fun CreateForm(seed: CreateSeed, onSave: (Part) -> Unit) {
    val ctx = LocalContext.current
    var mpn by remember(seed) { mutableStateOf(seed.mpn) }
    var qtyText by remember(seed) { mutableStateOf(if (seed.domain == Domains.FILAMENT) "1000" else "10") }
    var location by remember(seed) { mutableStateOf("") }
    var unit by remember(seed) { mutableStateOf(seed.unit) }
    var lowText by remember(seed) {
        mutableStateOf(if (seed.domain == Domains.TOOL) "0" else "10")
    }
    var priceText by remember(seed) {
        mutableStateOf(
            if (seed.price > 0) String.format("%.4f", seed.price).trimEnd('0').trimEnd('.') else ""
        )
    }
    var note by remember(seed) { mutableStateOf("") }
    var brandText by remember(seed) { mutableStateOf(seed.brand) }

    // 耗材走专用表单：材料/颜色/净重/空盘重都从预设里选，避免手打十几个键值对
    var filament by remember(seed) {
        mutableStateOf(
            FilamentDraft.of(
                Part(
                    internalCode = "",
                    name = seed.name,
                    category = seed.category,
                    domain = seed.domain,
                    packageName = seed.packageName,
                    paramsJson = paramsToJsonString(seed.params)
                )
            )
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("入库确认", style = MaterialTheme.typography.titleMedium)

            if (seed.lcscCode.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "立创 ${seed.lcscCode}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (seed.productId > 0) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "商品页 ↗",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    openUrl(ctx, com.atun.componentvault.enrich.LcscLink.best(seed.productId, seed.lcscCode))
                                }
                                .padding(vertical = 2.dp)
                        )
                    }
                    if (seed.datasheetUrl.isNotBlank()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "手册 ↗",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { openUrl(ctx, seed.datasheetUrl) }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = mpn,
                onValueChange = { mpn = it },
                label = { Text("型号 / 规范名（可留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = seed.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = seed.packageName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (seed.domain == Domains.ELECTRONICS) "封装" else "规格") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { s -> qtyText = s.filter { it.isDigit() || it == '.' }.take(8) },
                    label = { Text(if (seed.domain == Domains.FILAMENT) "剩余（g）" else "数量") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("单位") },
                    singleLine = true,
                    modifier = Modifier.weight(0.7f)
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("仓位") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Domains.units(seed.domain).forEach { u ->
                    FilterChip(
                        selected = unit == u,
                        onClick = { unit = u },
                        label = { Text(u, fontSize = 10.5.sp) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { s -> priceText = s.filter { it.isDigit() || it == '.' } },
                    label = { Text("单价（元/$unit）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lowText,
                    onValueChange = { s -> lowText = s.filter { it.isDigit() || it == '.' } },
                    label = { Text("预警阈值") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (seed.domain == Domains.FILAMENT) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "耗材参数",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilamentEditor(
                    draft = filament,
                    onChange = { filament = it },
                    brand = brandText,
                    onBrandChange = { brandText = it }
                )
            } else if (seed.params.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "识别到的参数",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    seed.params.entries.joinToString("　") { "${it.key} ${it.value}" },
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val isFilament = seed.domain == Domains.FILAMENT
                    val finalParams =
                        if (isFilament) filament.mergeInto(seed.params) else seed.params
                    val paramsJson = paramsToJsonString(finalParams)
                    onSave(
                        Part(
                            internalCode = "",
                            mpn = mpn.trim(),
                            name = seed.name,
                            category = if (isFilament) filament.material else seed.category,
                            domain = seed.domain,
                            packageName = if (isFilament) "${filament.diameter}mm" else seed.packageName,
                            spec = seed.spec,
                            brand = brandText.trim(),
                            lcscCode = seed.lcscCode,
                            lcscProductId = seed.productId,
                            datasheetUrl = seed.datasheetUrl,
                            paramsJson = paramsJson,
                            tags = seed.tags.joinToString(","),
                            summary = seed.summary,
                            qty = qtyText.toDoubleOrNull() ?: 0.0,
                            unit = if (isFilament) "g"
                            else unit.trim().ifBlank { Domains.defaultUnit(seed.domain) },
                            lowThreshold = lowText.toDoubleOrNull() ?: 0.0,
                            location = location.trim(),
                            price = priceText.toDoubleOrNull() ?: 0.0,
                            note = note.trim(),
                            iconUrl = seed.iconUrl
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("确认入库") }
        }
    }
}
