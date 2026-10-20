package com.atun.componentvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atun.componentvault.data.FilamentInfo
import com.atun.componentvault.data.FilamentKeys
import com.atun.componentvault.data.Part
import com.atun.componentvault.data.fmtQty
import com.atun.componentvault.enrich.FilamentPresets
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

/**
 * 耗材表单的可编辑快照。
 *
 * 全部用 String 存：输入框里正在敲的内容不一定是合法数字（"1.7" / "" / "1000g"），
 * 转 Double 放在最后一步，避免输入过程中数字被吞。
 */
data class FilamentDraft(
    val material: String = "PLA",
    val diameter: String = "1.75",
    val colorName: String = "",
    val colorHex: String = "",
    val netWeight: String = "1000",
    val spoolWeight: String = "",
    val density: String = "",
    val nozzle: String = "",
    val bed: String = "",
    val lot: String = "",
    val openedAt: String = ""
) {

    /** 把耗材字段合并进已有参数表。本表单管理的键先清空再写，其余键原样保留 */
    fun mergeInto(base: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(base)
        out.remove(FilamentKeys.MATERIAL)
        out.remove(FilamentKeys.DIAMETER)
        out.remove(FilamentKeys.COLOR)
        out.remove(FilamentKeys.COLOR_HEX)
        out.remove(FilamentKeys.NET_WEIGHT)
        out.remove(FilamentKeys.SPOOL_WEIGHT)
        out.remove(FilamentKeys.DENSITY)
        out.remove(FilamentKeys.NOZZLE)
        out.remove(FilamentKeys.BED)
        out.remove(FilamentKeys.LOT)
        out.remove(FilamentKeys.OPENED_AT)
        fun put(k: String, v: String) {
            if (v.isNotBlank()) out[k] = v.trim()
        }
        put(FilamentKeys.MATERIAL, material)
        put(FilamentKeys.DIAMETER, diameter)
        put(FilamentKeys.COLOR, colorName)
        put(FilamentKeys.COLOR_HEX, colorHex)
        put(FilamentKeys.NET_WEIGHT, netWeight)
        put(FilamentKeys.SPOOL_WEIGHT, spoolWeight)
        put(FilamentKeys.DENSITY, density)
        put(FilamentKeys.NOZZLE, nozzle)
        put(FilamentKeys.BED, bed)
        put(FilamentKeys.LOT, lot)
        put(FilamentKeys.OPENED_AT, openedAt)
        return out
    }

    companion object {
        fun of(p: Part): FilamentDraft {
            val m = FilamentInfo.material(p)
            return FilamentDraft(
                material = m,
                diameter = FilamentInfo.diameterMm(p)?.let { fmtQty(it) } ?: "1.75",
                colorName = FilamentInfo.colorName(p),
                colorHex = FilamentInfo.colorHex(p),
                netWeight = FilamentInfo.get(p, FilamentKeys.NET_WEIGHT)
                    .ifBlank { FilamentInfo.netWeight(p)?.let { fmtQty(it) } ?: "" },
                spoolWeight = FilamentInfo.get(p, FilamentKeys.SPOOL_WEIGHT),
                density = FilamentInfo.get(p, FilamentKeys.DENSITY),
                nozzle = FilamentInfo.get(p, FilamentKeys.NOZZLE),
                bed = FilamentInfo.get(p, FilamentKeys.BED),
                lot = FilamentInfo.get(p, FilamentKeys.LOT),
                openedAt = FilamentInfo.openedAt(p)
            )
        }
    }
}

/** 参数表 → JSON 字符串。写库统一走这里，避免各处拼串拼出不合法的 JSON */
fun paramsToJsonString(map: Map<String, String>): String {
    val obj = JsonObject(map.mapValues { JsonPrimitive(it.value) })
    return obj.toString()
}

/**
 * 耗材参数编辑器。
 *
 * 交互取舍：材料 23 种、品牌 14 个、颜色 17 个，全部铺成 Chip 会把页面撑爆，
 * 所以材料/品牌/颜色走「点一行 → 弹选择框（带说明/色块）」，规格类（直径、净重、空盘重）
 * 走横向 Chip —— 那几项本来就只值 2~6 个候选，直接点更快。
 */
@Composable
fun FilamentEditor(
    draft: FilamentDraft,
    onChange: (FilamentDraft) -> Unit,
    brand: String,
    onBrandChange: (String) -> Unit
) {
    var showMaterials by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showBrands by remember { mutableStateOf(false) }

    val preset = FilamentPresets.material(draft.material)

    Column(Modifier.fillMaxWidth()) {
        // ---------------------------------------------------------- 材料
        PickerRow(
            label = "材料类型",
            value = draft.material.ifBlank { "未选择" },
            hint = preset?.let { "密度 ${it.density} · 喷嘴 ${it.nozzle}℃ · 热床 ${it.bed}℃" }
                ?: "选择后自动带出密度与温度"
        ) { showMaterials = true }

        // ---------------------------------------------------------- 品牌
        PickerRow(
            label = "品牌",
            value = brand.ifBlank { "未填写" },
            hint = "预设 14 个常用品牌，也可直接输入"
        ) { showBrands = true }

        // ---------------------------------------------------------- 颜色
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ColorDot(draft.colorHex, 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("颜色", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    draft.colorName.ifBlank { "未选择" },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "选择",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showColors = true }.padding(6.dp)
            )
        }

        // ---------------------------------------------------------- 线径
        Text("线径（mm）", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilamentPresets.DIAMETERS.forEach { d ->
                FilterChip(
                    selected = draft.diameter == d,
                    onClick = { onChange(draft.copy(diameter = d)) },
                    label = { Text(d, fontSize = 11.sp) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // ---------------------------------------------------------- 净重
        Text("标称净重（g）", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilamentPresets.NET_WEIGHTS.forEach { w ->
                FilterChip(
                    selected = draft.netWeight == w,
                    onClick = { onChange(draft.copy(netWeight = w)) },
                    label = { Text(w, fontSize = 11.sp) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // ---------------------------------------------------------- 空盘重
        Text("空盘重（g）", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilamentPresets.SPOOL_WEIGHTS.forEach { w ->
                val label = if (w == "0") "无/纸盘" else w
                FilterChip(
                    selected = draft.spoolWeight == w,
                    onClick = { onChange(draft.copy(spoolWeight = w)) },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // ---------------------------------------------------------- 温度/密度
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                MiniField("密度 g/cm³", draft.density) { onChange(draft.copy(density = it)) }
            }
            Box(Modifier.weight(1f)) {
                MiniField("喷嘴 ℃", draft.nozzle) { onChange(draft.copy(nozzle = it)) }
            }
            Box(Modifier.weight(1f)) {
                MiniField("热床 ℃", draft.bed) { onChange(draft.copy(bed = it)) }
            }
        }
        if (preset != null) {
            Text(
                "预设仅供起点：同材料不同品牌差 ±20℃，以盘上标签为准",
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                MiniField("批次号", draft.lot) { onChange(draft.copy(lot = it)) }
            }
            Box(Modifier.weight(1f)) {
                MiniField("开封日期 yyyy-MM-dd", draft.openedAt) { onChange(draft.copy(openedAt = it)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                onChange(draft.copy(openedAt = LocalDate.now().toString()))
            }) { Text("标记今天开封", fontSize = 11.sp) }
            if (draft.openedAt.isNotBlank()) {
                TextButton(onClick = { onChange(draft.copy(openedAt = "")) }) {
                    Text("清除", fontSize = 11.sp)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 弹窗

    if (showMaterials) {
        OptionSheet(
            title = "选择材料",
            onDismiss = { showMaterials = false }
        ) {
            items(FilamentPresets.MATERIALS) { m ->
                OptionRow(
                    title = m.name,
                    subtitle = "${m.trait}　｜　${m.usage}" +
                        if (m.enclosure) "　｜　建议封箱" else "",
                    trailing = "${m.density}　${m.nozzle}℃",
                    selected = draft.material == m.name
                ) {
                    // 换材料时把密度/温度一起换成新预设，除非用户自己改过
                    onChange(
                        draft.copy(
                            material = m.name,
                            density = fmtQty(m.density),
                            nozzle = m.nozzle,
                            bed = m.bed
                        )
                    )
                    showMaterials = false
                }
            }
        }
    }

    if (showBrands) {
        OptionSheet(title = "选择品牌", onDismiss = { showBrands = false }) {
            item {
                OutlinedTextField(
                    value = brand,
                    onValueChange = onBrandChange,
                    label = { Text("手动输入品牌", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                )
            }
            items(FilamentPresets.BRANDS) { b ->
                OptionRow(title = b, subtitle = "", trailing = "", selected = brand == b) {
                    onBrandChange(b)
                    showBrands = false
                }
            }
        }
    }

    if (showColors) {
        OptionSheet(title = "选择颜色", onDismiss = { showColors = false }) {
            items(FilamentPresets.COLORS) { c ->
                OptionRow(
                    title = c.name,
                    subtitle = c.hex,
                    trailing = "",
                    selected = draft.colorName == c.name,
                    leading = { ColorDot(c.hex, 18.dp) }
                ) {
                    onChange(draft.copy(colorName = c.name, colorHex = c.hex))
                    showColors = false
                }
            }
        }
    }
}

/**
 * 详情页的耗材卡：剩余比例、剩余长度、温度、净重/空盘重、开封天数。
 * 这张卡只在 domain=FILAMENT 时出现。
 */
@Composable
fun FilamentCard(p: Part) {
    val pct = FilamentInfo.percent(p)
    val meters = FilamentInfo.remainingMeters(p)
    val net = FilamentInfo.netWeight(p)
    val spool = FilamentInfo.spoolWeight(p)
    val days = FilamentInfo.openedDays(p)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(FilamentInfo.colorHex(p), 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "耗材",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    FilamentInfo.material(p),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))

            if (pct != null) {
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    color = if (pct < 0.2f) Color(0xFFD97706) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "剩余 ${(pct * 100).toInt()}%　（${fmtQty(p.qty)}g / 标称 ${fmtQty(net ?: 0.0)}g）",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            FilamentRow("线径", FilamentInfo.diameterMm(p)?.let { "${fmtQty(it)} mm" } ?: "—")
            meters?.let { FilamentRow("剩余长度", "约 ${fmtQty(Math.round(it * 10) / 10.0)} 米") }
            FilamentRow("喷嘴 / 热床", "${FilamentInfo.nozzle(p).ifBlank { "—" }}℃ / ${FilamentInfo.bed(p).ifBlank { "—" }}℃")
            FilamentRow("密度", FilamentInfo.density(p)?.let { "$it g/cm³" } ?: "—")
            if (spool != null && spool > 0) FilamentRow("空盘重", "${fmtQty(spool)} g")
            FilamentInfo.lot(p).takeIf { it.isNotBlank() }?.let { FilamentRow("批次号", it) }
            if (days != null) FilamentRow("已开封", "$days 天（${FilamentInfo.openedAt(p)}）")
        }
    }
}

@Composable
private fun FilamentRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(86.dp)
        )
        Text(value, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * 耗材页顶部的按材料汇总。Spoolman 是按材料型号分组的，
 * 手机上一屏就能看完，所以这里只做「材料 → 盘数 / 总剩余」的紧凑汇总。
 */
@Composable
fun FilamentSummary(parts: List<Part>) {
    if (parts.isEmpty()) return
    val groups = parts.groupBy { FilamentInfo.material(it) }
        .toList()
        .sortedByDescending { (_, list) -> list.sumOf { it.qty } }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("按材料汇总", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            groups.forEach { (material, list) ->
                val total = list.sumOf { it.qty }
                val net = list.sumOf { FilamentInfo.netWeight(it) ?: 0.0 }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        material,
                        fontSize = 11.5.sp,
                        modifier = Modifier.width(96.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${list.size} 盘",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp)
                    )
                    Text(
                        "${fmtQty(total)}" + if (net > 0) " / ${fmtQty(net)} g" else " g",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (net > 0) {
                        Text(
                            "${(total / net * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 小部件

@Composable
fun ColorDot(hex: String, size: androidx.compose.ui.unit.Dp) {
    val c = parseHexColor(hex) ?: MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(c)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}

fun parseHexColor(hex: String): Color? {
    if (!hex.startsWith("#") || hex.length < 7) return null
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

@Composable
private fun PickerRow(label: String, value: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                hint,
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text("选择", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun MiniField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

/** 通用选择弹窗：内容由调用方用 LazyColumn 的 item/items 填充 */
@Composable
private fun OptionSheet(
    title: String,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), content = content)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    trailing: String,
    selected: Boolean,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                trailing,
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
