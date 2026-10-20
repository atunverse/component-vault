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
import androidx.compose.material3.FloatingActionButton
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
 * 只放三个分类和一个「我的」：耗材 / 五金 / 元件 是日常最高频的动作
 * （「看看那盘 PETG 还剩多少」「找个 M3×8 螺钉」）。
 *
 * **「快录」不放底部**：它不是「查看某一类库存」，而是一个随时可触发的动作，
 * 塞进 Tab 里既占位置又容易被误当成第四个分类。改成右下角的加号（FAB），
 * 三个分类页都能直接点，位置固定、拇指够得到，和「浏览」明确区分开。
 */
private data class TabSpec(val label: String, val icon: ImageVector, val domain: String?)

private val TABS = listOf(
    TabSpec("耗材", Icons.Filled.ShoppingCart, Domains.FILAMENT),
    TabSpec("五金", Icons.Filled.Build, Domains.HARDWARE),
    TabSpec("元件", Icons.Filled.List, Domains.ELECTRONICS),
    TabSpec("我的", Icons.Filled.Person, null)
)

/** 二级页：从「我的」进入，盖在内容区上，返回键可退出 */
private enum class Overlay { NONE, BOM, LAN }

@Composable
fun VaultRoot() {
    // 默认停在「元件」：绝大多数 DIY 库存是电子件，也和老用户的使用习惯一致
    var tab by rememberSaveable { mutableIntStateOf(2) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }

    // 快录是独立页面（不是 Tab）：打开时连底部导航一起让位，录入界面更干净
    var capture by rememberSaveable { mutableStateOf(false) }

    // 搜索词提到根节点。三个分类页共用一个搜索框状态，
    // 所以「在哪个页面搜」不影响「搜什么」——这就是统一检索的含义。
    var query by rememberSaveable { mutableStateOf("") }

    // 分类页进到详情/编辑时不显示加号
    var stockListVisible by remember { mutableStateOf(true) }

    // 两个返回栈互斥，注册顺序不影响结果
    BackHandler(enabled = capture) { capture = false }
    BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!capture) {
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
        },
        floatingActionButton = {
            // 快录页里不显示（页内已有返回）；二级页里也不显示，避免和「返回」抢注意力
            val listPage = TABS[tab].domain == null || stockListVisible
            if (!capture && overlay == Overlay.NONE && listPage) {
                FloatingActionButton(
                    onClick = { capture = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "快速录入")
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                capture -> CaptureScreen(
                    // 从「五金」页点加号进来，就默认停在五金 —— 少按一次
                    initialDomain = TABS[tab].domain ?: Domains.ELECTRONICS,
                    onBack = { capture = false }
                )
                overlay == Overlay.BOM -> BomScreen(onBack = { overlay = Overlay.NONE })
                overlay == Overlay.LAN -> LanScreen(onBack = { overlay = Overlay.NONE })
                else -> {
                    val domain = TABS[tab].domain
                    if (domain != null) {
                        StockScreen(
                            domain = domain,
                            query = query,
                            onQuery = { query = it },
                            onListVisible = { stockListVisible = it }
                        )
                    } else {
                        MeScreen(
                            onOpenBom = { overlay = Overlay.BOM },
                            onOpenLan = { overlay = Overlay.LAN }
                        )
                    }
                }
            }
        }
    }
}
