package com.atun.componentvault.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.atun.componentvault.Graph
import com.atun.componentvault.data.Domains
import com.atun.componentvault.data.FilamentInfo
import com.atun.componentvault.data.IconStore
import com.atun.componentvault.data.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图标内存缓存。
 *
 * 不用 Coil 的理由：只渲染本地固定尺寸的小图（192px JPEG，约 8KB），
 * 引入一个 300KB+ 的图片库换来的能力（网络加载、占位动画、变换）这里一个都用不上。
 * 40 行 LruCache 就够，而且完全不涉及 content:// 的生命周期问题。
 */
object IconCache {

    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun load(ctx: Context, fileName: String): ImageBitmap? {
        if (fileName.isBlank()) return null
        val f = IconStore.fileOf(ctx, fileName)
        if (!f.exists()) return null
        // 缓存键带上文件修改时间：同一张图被重新下载覆盖后文件名不变，
        // 只按文件名缓存会一直显示旧图（v0.3.0「换图标没反应」就是这么来的）。
        val key = fileName + "#" + f.lastModified()
        cache.get(key)?.let { return it }
        // 已经落盘成 <=192px 的 JPEG，仍用 inSampleSize 兜底，防止历史遗留大图
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        val img = bmp.asImageBitmap()
        cache.put(key, img)
        return img
    }

    /** 缓存键带文件修改时间，按文件名删不到了，直接清空 —— 图标总量只有几百张 */
    fun invalidate(fileName: String) {
        if (fileName.isNotBlank()) cache.evictAll()
    }
}

/**
 * 统一图标渲染。
 *
 * - 电子/五金/工具：有图显示图，没图退回「封装文字占位」，与 v0.2.1 一致。
 * - 3D 耗材：**颜色就是主视觉**。没拍照片时整块铺耗材色（多色即渐变），中间压材料短名；
 *   有照片时也留一圈耗材色做外框 —— 一排耗材在列表里靠颜色区分，比靠名字快得多。
 */
@Composable
fun PartIcon(
    part: Part,
    size: Dp,
    corner: Dp = 9.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = part.iconPath,
        key2 = part.updatedAt
    ) {
        value = if (part.iconPath.isBlank()) null
        else withContext(Dispatchers.IO) { IconCache.load(ctx, part.iconPath) }
    }

    val isFilament = part.domain == Domains.FILAMENT
    val hex = if (isFilament) FilamentInfo.colorHexes(part).joinToString(",") else ""
    val palette = if (isFilament) parseHexColors(hex) else emptyList()
    // 耗材颜色要跟着参数变，而 produceState 的 key 只看图标文件；这里直接读 part，
    // 改颜色后整棵重组即可生效（颜色不落盘，不需要缓存）
    val hasColor = palette.isNotEmpty()
    val shape = RoundedCornerShape(corner)

    val base = modifier
        .size(size)
        // 白色 / 米白这类浅色耗材在白卡片上会「融」进背景，靠这条细边定住轮廓
        .then(
            if (hasColor) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            else Modifier
        )
        .clip(shape)
        .then(
            when {
                hasColor -> Modifier.background(colorBrush(hex, MaterialTheme.colorScheme.surfaceVariant))
                img != null -> Modifier.background(Color.White)
                else -> Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
            }
        )

    Box(
        modifier = if (onClick != null) base.clickable { onClick() } else base,
        contentAlignment = Alignment.Center
    ) {
        when {
            // 耗材 + 照片：色框留给颜色，照片放在白底内衬上，白底方图不会糊在彩色里
            img != null && hasColor -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(size * 0.075f)
                    .clip(RoundedCornerShape(corner * 0.55f))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = img!!,
                    contentDescription = part.displayTitle,
                    modifier = Modifier.fillMaxSize().padding(1.dp),
                    contentScale = ContentScale.Fit
                )
            }
            img != null -> Image(
                bitmap = img!!,
                contentDescription = part.displayTitle,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.Fit
            )
            isFilament -> FilamentGlyph(
                text = FilamentInfo.shortMaterial(part),
                hex = hex,
                sizeDp = size.value
            )
            else -> Text(
                text = part.packageName.substringBefore(' ').take(6).ifBlank { "—" },
                fontSize = if (part.packageName.length > 6) 9.sp else 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 纯色块上的材料名。
 *
 * 字色按色块平均亮度取反色（见 [onColorText]），底衬再取字色的反色做半透明胶囊 ——
 * 这样黑料白字、白料黑字、彩虹渐变也都读得清，不需要给每种颜色单独配色。
 */
@Composable
private fun FilamentGlyph(text: String, hex: String, sizeDp: Float) {
    val fg = onColorText(hex)
    val plate = if (fg == Color.White) Color.Black.copy(alpha = 0.24f)
    else Color.White.copy(alpha = 0.6f)
    Text(
        text = text,
        fontSize = (sizeDp * 0.17f).coerceIn(9f, 18f).sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(plate)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/**
 * 换图标：拍照 / 相册 / 从立创重下 / 清除。
 *
 * 拍照走系统相机（ACTION_IMAGE_CAPTURE → TakePicture），**不需要 CAMERA 权限**：
 * 声明了 CAMERA 反而会被要求运行时授权，而系统相机 App 自己已经有权限了。
 * 但必须用 FileProvider 给出 file:// 的替代 Uri，否则 Android 7+ 抛 FileUriExposedException。
 */
@Composable
fun IconPickerDialog(part: Part, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var shotFile by remember { mutableStateOf<File?>(null) }

    fun finish(ok: Boolean) {
        IconCache.invalidate(part.iconPath)
        IconCache.invalidate(IconStore.nameFor(part.id))
        busy = false
        if (ok) onDismiss()
    }

    fun importFrom(file: File, source: String) {
        scope.launch {
            val ok = IconStore.fromFile(ctx, part.id, file)
            if (ok) {
                Graph.repo.updateIcon(part.id, IconStore.nameFor(part.id), source, "")
            }
            runCatching { file.delete() }
            finish(ok)
        }
    }

    fun importFromUri(uri: Uri) {
        scope.launch {
            val tmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(cacheCameraDir(ctx), "pick_${System.currentTimeMillis()}.jpg")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                    f
                }.getOrNull()
            }
            if (tmp == null) {
                busy = false
                return@launch
            }
            importFrom(tmp, "album")
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shotFile
        if (ok && f != null) importFrom(f, "photo") else busy = false
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) importFromUri(uri) else busy = false }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("设置图标") },
        text = {
            Column {
                Text(
                    if (busy) "正在处理图片…" else "立创有收录的器件建议用官方图，保持与商城一致；没有收录的可以拍实物",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                ActionRow("拍照", "对着实物拍一张，自动裁成方形小图") {
                    val f = File(cacheCameraDir(ctx), "shot_${System.currentTimeMillis()}.jpg")
                    shotFile = f
                    busy = true
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    takePicture.launch(uri)
                }
                ActionRow("从相册选", "用已有的照片") {
                    busy = true
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                ActionRow("从立创重新下载", "按立创号重新抓一次官方图") {
                    busy = true
                    scope.launch {
                        val ok = Graph.repo.fetchIcon(part.id)
                        finish(ok)
                    }
                }
                if (part.hasIcon) {
                    ActionRow("清除图标", "回到封装文字占位") {
                        busy = true
                        scope.launch {
                            IconStore.delete(ctx, part.iconPath)
                            Graph.repo.updateIcon(part.id, "", "", "")
                            finish(true)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } }
    )
}

@Composable
private fun ActionRow(title: String, desc: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(desc, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun cacheCameraDir(ctx: Context): File =
    File(ctx.cacheDir, "camera").apply { if (!exists()) mkdirs() }
