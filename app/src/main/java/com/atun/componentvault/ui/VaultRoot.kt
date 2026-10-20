package com.atun.componentvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.atun.componentvault.data.Domains

/**
 * 底部导航。
 *
 * 三大类物料（耗材 / 五金 / 元件）各占一个 Tab —— 这是用户日常最高频的动作：
 * 「看看我那盘 PETG 还剩多少」「找个 M3×8 的螺钉」。
 * 快录与我的保留，BOM 和局域网收进「我的」二级页：
 * 那两项是偶尔用的工具，放在底部会把 5 个位置挤成 6 个，标签在小屏上会折行。
 */
private data class TabSpec(val label: String, val icon: ImageVector, val domain: String?)

private val TABS = listOf(
    TabSpec("耗材", Icons.Filled.ShoppingCart, Domains.FILAMENT),
    TabSpec("五金", Icons.Filled.Build, Domains.HARDWARE),
    TabSpec("元件", Icons.Filled.List, Domains.ELECTRONICS),
    TabSpec("快录", Icons.Filled.Add, null),
    TabSpec("我的", Icons.Filled.Person, null)
)

/** 二级页：从「我的」进入，盖在内容区上，返回键可退出 */
private enum class Overlay { NONE, BOM, LAN }

@Composable
fun VaultRoot() {
    // 默认停在「元件」：绝大多数 DIY 库存是电子件，也和老用户的使用习惯一致
    var tab by rememberSaveable { mutableIntStateOf(2) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }

    // 搜索词提到根节点。三个分类页共用一个搜索框状态，
    // 所以「在哪个页面搜」不影响「搜什么」——这就是统一检索的含义。
    var query by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                TABS.forEachIndexed { index, spec ->
                    NavigationBarItem(
                        selected = tab == index && overlay == Overlay.NONE,
                        onClick = {
                            overlay = Overlay.NONE
                            // 切分类等于换视图：把搜索词清掉，否则三个页面看起来一模一样
                            if (tab != index) query = ""
                            tab = index
                        },
                        icon = { Icon(spec.icon, contentDescription = spec.label) },
                        label = { Text(spec.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                overlay == Overlay.BOM -> BomScreen(onBack = { overlay = Overlay.NONE })
                overlay == Overlay.LAN -> LanScreen(onBack = { overlay = Overlay.NONE })
                else -> {
                    val domain = TABS[tab].domain
                    when {
                        domain != null -> StockScreen(
                            domain = domain,
                            query = query,
                            onQuery = { query = it }
                        )
                        tab == 3 -> CaptureScreen(initialDomain = Domains.ELECTRONICS)
                        else -> MeScreen(
                            onOpenBom = { overlay = Overlay.BOM },
                            onOpenLan = { overlay = Overlay.LAN }
                        )
                    }
                }
            }
        }
    }
}
