package com.atun.componentvault

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 闪退兜底：把未捕获异常写进外部私有目录，手机上用文件管理器就能看到。
 *
 * 为什么需要它：这类自用工具出问题时，用户能提供的信息通常只有"打开就闪退"，
 * 没有 adb、不会抓 logcat，等于没法定位。堆栈落盘后，用户直接把文件发过来即可。
 *
 * 落盘位置：/sdcard/Android/data/com.atun.componentvault/files/last-crash.txt
 * 同时写入 filesDir，外部目录不可用时兜底。
 *
 * 注意：这里必须**吞掉自身所有异常**。日志逻辑再抛错会造成二次崩溃，
 * 反而丢失原始堆栈，掩盖真问题。
 */
object CrashGuard {

    const val FILE_NAME = "last-crash.txt"

    /** 只在 Application 创建时调用一次 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** 读取上次崩溃日志，没有则返回 null */
    fun readLast(context: Context): String? {
        val f = fileOf(context)
        return runCatching { if (f.exists() && f.length() > 0) f.readText() else null }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { fileOf(context).delete() }
    }

    /** 暴露给界面展示，方便用户找到文件 */
    fun fileOf(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    private fun save(context: Context, thread: Thread, error: Throwable) {
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("时间: $when_")
            pw.println("线程: ${thread.name}")
            pw.println("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            pw.println("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("---")
            error.printStackTrace(pw)
        }
        val text = sw.toString()

        // 双写：外部目录便于用户取出，内部目录保证一定能写成功
        runCatching { fileOf(context).writeText(text) }
        runCatching { File(context.filesDir, FILE_NAME).writeText(text) }
    }
}
