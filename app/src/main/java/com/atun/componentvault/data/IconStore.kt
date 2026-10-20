package com.atun.componentvault.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地图标仓库。
 *
 * 设计原则：**入库时就把图下载并落盘**，列表渲染只读本地文件。
 * 不在列表里加载远程 URL —— 那样断网全白、滚动抖动，和「离线可用的库存工具」这个前提冲突。
 *
 * 体积：立创原图 224×224 约 10KB，缩到 192 后约 8KB。500 个器件约 4MB。
 *
 * 两个必须处理的问题（都是实测踩过的）：
 *  1. 大图解码 OOM —— 手机相机 12MP 直接 decode 是 48MB，低端机必崩。用两段式解码。
 *  2. EXIF 旋转 —— 相机把方向记在 EXIF 里，像素本身是横的。不解码时旋转，
 *     缩略图就是躺着的。
 */
object IconStore {

    private const val DIR_NAME = "icons"
    /** 长边上限。列表用 44dp，3 倍屏 132px，192 有富余 */
    private const val MAX_EDGE = 192
    /** 源图小于这个体积且是 JPEG 时，原样落盘，不重压（零画质损失） */
    private const val SMALL_BYTES = 24_000

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun nameFor(partId: Long): String = "p$partId.jpg"

    fun fileOf(ctx: Context, name: String): File = File(dir(ctx), name)

    fun exists(ctx: Context, name: String): Boolean =
        name.isNotBlank() && fileOf(ctx, name).let { it.exists() && it.length() > 0 }

    /** 总占用字节，用于「我的」页展示 */
    fun usedBytes(ctx: Context): Long =
        dir(ctx).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** 下载立创商品图并落盘。失败返回 false，不抛异常（图标只是增强，不能阻塞入库） */
    suspend fun fromUrl(ctx: Context, partId: Long, url: String): Boolean =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext false
            val raw = runCatching { download(url) }.getOrNull() ?: return@withContext false
            val bytes = runCatching {
                val (w, h) = bounds(raw)
                if (w in 1..MAX_EDGE && h in 1..MAX_EDGE && raw.size <= SMALL_BYTES) raw
                else transcode(raw, rotateDeg = 0) ?: raw
            }.getOrNull() ?: return@withContext false
            write(ctx, partId, bytes)
        }

    /** 拍照/相册导入：一定会重新编码（要应用 EXIF 旋转 + 缩到 192） */
    suspend fun fromFile(ctx: Context, partId: Long, src: File): Boolean =
        withContext(Dispatchers.IO) {
            if (!src.exists() || src.length() == 0L) return@withContext false
            val bytes = runCatching {
                val raw = src.readBytes()
                transcode(raw, rotateDeg = readRotation(src))
            }.getOrNull() ?: return@withContext false
            write(ctx, partId, bytes)
        }

    fun delete(ctx: Context, name: String) {
        if (name.isBlank()) return
        runCatching { fileOf(ctx, name).delete() }
    }

    /** 清理数据库里已无引用的图标文件，返回删除个数 */
    fun purgeOrphans(ctx: Context, alive: Set<String>): Int {
        var n = 0
        dir(ctx).listFiles()?.forEach { f ->
            if (f.isFile && f.name !in alive) {
                if (runCatching { f.delete() }.getOrDefault(false)) n++
            }
        }
        return n
    }

    // ---------------------------------------------------------------- 内部

    private fun write(ctx: Context, partId: Long, bytes: ByteArray): Boolean = runCatching {
        val target = fileOf(ctx, nameFor(partId))
        // 先写临时文件再 rename：中途崩溃不会留下半张图导致解码失败
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes) }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }.getOrDefault(false)

    private fun download(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            // 实测：立创图床 alimg.szlcsc.com 不校验 UA/Referer，给个 UA 只是为了礼貌
            setRequestProperty("User-Agent", "ComponentVault/0.3")
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readBytes() }.takeIf { it.size > 512 }
        } finally {
            conn.disconnect()
        }
    }

    /** 只读尺寸，不解码像素 */
    private fun bounds(b: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return o.outWidth to o.outHeight
    }

    /**
     * 解码 → 按 2 的幂粗采样 → 精确缩放 → 应用旋转 → 铺白底 → JPEG。
     * 铺白底是必须的：JPEG 没有透明通道，PNG 直接转会变成黑底。
     */
    private fun transcode(raw: ByteArray, rotateDeg: Int): ByteArray? {
        val (w, h) = bounds(raw)
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (w / sample > MAX_EDGE * 2 || h / sample > MAX_EDGE * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null

        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )

        val matrix = Matrix().apply { if (rotateDeg != 0) postRotate(rotateDeg.toFloat()) }
        val rotated = if (rotateDeg == 0) scaled else Bitmap.createBitmap(
            scaled, 0, 0, scaled.width, scaled.height, matrix, true
        )

        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(rotated, 0f, 0f, null)
        }
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        if (out !== rotated) out.recycle()
        if (rotated !== scaled) rotated.recycle()
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return bos.toByteArray()
    }

    /** 读 EXIF 方向并换算成顺时针旋转角度。用系统 ExifInterface（API 24+ 支持 InputStream） */
    private fun readRotation(f: File): Int = runCatching {
        val deg = f.inputStream().use { ExifInterface(it) }
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        when (deg) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
