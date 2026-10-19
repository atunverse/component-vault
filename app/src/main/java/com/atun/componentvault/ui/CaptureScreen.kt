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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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

@Composable
fun CaptureScreen() {
    var input by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Enricher.Candidate?>(null) }
    var savedName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val candidates = remember(input) {
        if (input.isBlank()) emptyList() else Enricher.localSuggest(input)
    }

    LaunchedEffect(input) { picked = null }

    LaunchedEffect(savedName) {
        if (savedName.isNotEmpty()) {
            delay(2600)
            savedName = ""
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text("快速录入", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "只输型号或代号，参数、封装、标签自动补全",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("型号 / 代号") },
            placeholder = { Text("SS34 / 103 / 10k 0603 / STM32F103C8T6") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "示例：103 → 10nF / 10kΩ　·　4R7 → 4.7Ω　·　01C → 10kΩ　·　10K 0805 → 10kΩ",
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

        Spacer(Modifier.height(16.dp))

        if (picked == null && candidates.isNotEmpty()) {
            Text(
                "补全结果（点选后确认）",
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
                defaultMpn = input.uppercase(),
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

@Composable
private fun CandidateCard(c: Enricher.Candidate, onClick: () -> Unit) {
    val g = c.guess
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${(g.confidence * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        c.source,
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
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            v,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
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
                    color = Color(0xFFB45309)
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
    var mpn by remember(candidate) { mutableStateOf(defaultMpn) }
    var qtyText by remember(candidate) { mutableStateOf("10") }
    var location by remember(candidate) { mutableStateOf("") }
    var priceText by remember(candidate) { mutableStateOf("") }
    var note by remember(candidate) { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("入库确认", style = MaterialTheme.typography.titleMedium)
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
