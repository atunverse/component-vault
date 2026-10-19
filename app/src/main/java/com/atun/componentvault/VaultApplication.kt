package com.atun.componentvault

import android.app.Application
import android.content.Context
import com.atun.componentvault.data.AppDb
import com.atun.componentvault.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** 极简手工依赖注入，避免为一个单模块应用引入 Hilt 带来的编译复杂度 */
object Graph {

    @Volatile
    private var repository: VaultRepository? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repo: VaultRepository
        get() = repository ?: error("Graph 尚未初始化")

    fun init(context: Context) {
        if (repository != null) return
        synchronized(this) {
            if (repository != null) return
            val db = AppDb.get(context)
            val r = VaultRepository(db)
            repository = r
            scope.launch { r.seedIfEmpty() }
        }
    }
}
