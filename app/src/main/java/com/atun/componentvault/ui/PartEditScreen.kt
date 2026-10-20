package com.atun.componentvault.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 编辑已入库器件。
 *
 * 两个刻意的限制：
 *  1. **数量不可在此修改** —— 必须走出入库，否则「改数量」这一步会绕过流水，账面失去可追溯性。
 *  2. 保存走 updateEditable，不是整行覆盖 —— 编辑期间电脑端可能已经出库，
 *     数量等受保护字段以库中最新值为准（见 VaultRepository.updateEditable）。
 */
@Composable
fun PartEditScreen(partId: Long, onBack: () -> Unit, onSaved: () -> Unit = onBack) {
    val scope = rememberCoroutineScope()
    val flow = remember(partId) { Graph.repo.observePart(partId) }
    val part by flow.collectAsStateWithLifecycle(initialValue = null)

    if (part == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("载入中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val p = part!!

    var domain by remember(p.id) { mutableStateOf(p.domain) }
    var mpn by remember(p.id) { mutableStateOf(p.mpn) }
    var name by remember(p.id) { mutableStateOf(p.name) }
    var category by remember(p.id) { mutableStateOf(p.category) }
    var packageName by remember(p.id) { mutableStateOf(p.packageName) }
    var spec by remember(p.id) { mutableStateOf(p.spec) }
    var brand by remember(p.id) { mutableStateOf(p.brand) }
    var unit by remember(p.id) { mutableStateOf(p.unit) }
    var location by remember(p.id) { mutableStateOf(p.location) }
    var lowText by remember(p.id) { mutableStateOf(fmtQty(p.lowThreshold)) }
    var priceText by remember(p.id) { mutableStateOf(if (p.price > 0) p.price.toString() else "") }
    var tags by remember(p.id) { mutableStateOf(p.tags) }
    var summary by remember(p.id) { mutableStateOf(p.summary) }
    var note by remember(p.id) { mutableStateOf(p.note) }
    var lcscCode by remember(p.id) { mutableStateOf(p.lcscCode) }
    var datasheet by remember(p.id) { mutableStateOf(p.datasheetUrl) }
    var paramsText by remember(p.id) {
        mutableStateOf(
            runCatching {
                Json.parseToJsonElement(p.paramsJson).jsonObject.entries
                    .joinToString("\n") { "${it.key}=${it.value.jsonPrimitive.content}" }
            }.getOrDefault("")
        )
    }
    var filament by remember(p.id) { mutableStateOf(FilamentDraft.of(p)) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "编辑资料",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "数量 ${p.qtyText}${p.unit}",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                "数量不在这里改 —— 请用「入库 / 出库」，这样每一次变动都会留流水。",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // 物料域
            SectionLabel("物料域")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Domains.ALL.forEach { d ->
                    FilterChip(
                        selected = domain == d,
                        onClick = {
                            domain = d
                            // 切域时把单位跟着换成该域的默认值，避免「螺丝按 g」
                            if (unit == Domains.defaultUnit(p.domain) || unit.isBlank()) {
                                unit = Domains.defaultUnit(d)
                            }
                        },
                        label = { Text(Domains.label(d), fontSize = 11.sp) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // 耗材：材料/颜色/净重/空盘重/温度全部走预设，键名与 FilamentInfo 对齐
            if (domain == Domains.FILAMENT) {
                SectionLabel("耗材参数")
                FilamentEditor(
                    draft = filament,
                    onChange = { filament = it },
                    brand = brand,
                    onBrandChange = { brand = it }
                )
                Spacer(Modifier.height(10.dp))
            }

            Field("型号 / 规范名", mpn) { mpn = it }
            Field("名称", name) { name = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field("分类", category) { category = it } }
                Box(Modifier.weight(1f)) { Field("封装 / 规格", packageName) { packageName = it } }
            }
            Field("规格说明", spec) { spec = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field("品牌 / 材质", brand) { brand = it } }
                Box(Modifier.weight(1f)) { Field("单位", unit) { unit = it } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Domains.units(domain).forEach { u ->
                    FilterChip(
                        selected = unit == u,
                        onClick = { unit = u },
                        label = { Text(u, fontSize = 10.5.sp) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // 仓位 —— 用户最想改的就是这个
            SectionLabel("存放位置")
            Field("仓位", location) { location = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    Field("低库存阈值（0=不预警）", lowText, digitsOnly = false) { s ->
                        lowText = s.filter { it.isDigit() || it == '.' }
                    }
                }
                Box(Modifier.weight(1f)) {
                    Field("单价（元/${p.unit}）", priceText, digitsOnly = false) { s ->
                        priceText = s.filter { it.isDigit() || it == '.' }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            SectionLabel("立创与手册")
            Field("立创号", lcscCode) { lcscCode = it.trim().uppercase() }
            Field("数据手册 URL", datasheet) { datasheet = it.trim() }
            Spacer(Modifier.height(10.dp))

            if (domain != Domains.FILAMENT) {
                SectionLabel("参数（每行「键=值」）")
                OutlinedTextField(
                    value = paramsText,
                    onValueChange = { paramsText = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(10.dp))
            }

            Field("标签（逗号分隔）", tags) { tags = it }
            Field("一句话简介", summary) { summary = it }
            Field("备注", note) { note = it }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !saving,
                onClick = {
                    val low = lowText.toDoubleOrNull() ?: 0.0
                    val price = priceText.toDoubleOrNull() ?: 0.0
                    val isFilament = domain == Domains.FILAMENT
                    // 耗材：以库里原有参数为底，再覆盖表单管理的那些键，其它自建参数不丢
                    val json = if (isFilament) {
                        paramsToJsonString(filament.mergeInto(FilamentInfo.params(p)))
                    } else {
                        paramsToJson(paramsText)
                    }
                    saving = true
                    scope.launch {
                        val ok = Graph.repo.updateEditable(
                            p.copy(
                                domain = domain,
                                mpn = mpn.trim(),
                                name = name.trim(),
                                category = if (isFilament) filament.material else category.trim(),
                                packageName = if (isFilament) "${filament.diameter}mm" else packageName.trim(),
                                spec = spec.trim(),
                                brand = brand.trim(),
                                unit = unit.trim().ifBlank { "个" },
                                location = location.trim(),
                                lowThreshold = low,
                                price = price,
                                tags = tags.trim(),
                                summary = summary.trim(),
                                note = note.trim(),
                                lcscCode = lcscCode.trim(),
                                datasheetUrl = datasheet.trim(),
                                paramsJson = json
                            )
                        )
                        saving = false
                        if (ok) onSaved() else error = "保存失败：器件已不存在"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saving) "保存中…" else "保存修改") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    digitsOnly: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp)
    )
}

/** 「键=值」逐行文本 → JSON 对象字符串。空值丢弃，不写进库 */
internal fun paramsToJson(text: String): String {
    val pairs = text.lines().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null
        else {
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }
    }
    return if (pairs.isEmpty()) "{}"
    else pairs.joinToString(separator = ",", prefix = "{", postfix = "}") { (k, v) ->
        "\"" + esc(k) + "\":\"" + esc(v) + "\""
    }
}

private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
