package com.atun.componentvault.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.atun.componentvault.Graph
import com.atun.componentvault.R
import com.atun.componentvault.data.Part
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网服务。手机开热点或和电脑同一路由即可访问，电脑端零安装。
 *
 * 用前台 Service 承载，是因为 Android 会在应用退到后台后冻结普通协程任务，
 * 服务一停电脑端就断连 —— 这是这类「手机当服务端」方案最常见的坑。
 */
class LanServerService : Service() {

    private var engine: ApplicationEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        startForeground(NOTIFICATION_ID, buildNotification(port))
        if (engine == null) {
            runCatching { startServer(port) }
                .onSuccess {
                    isRunning = true
                    portValue = port
                }
                .onFailure {
                    isRunning = false
                    stopSelf()
                }
        }
        return START_STICKY
    }

    private fun startServer(port: Int) {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            module()
        }.also { it.start(wait = false) }
    }

    override fun onDestroy() {
        runCatching { engine?.stop(500, 1500) }
        engine = null
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Ktor 路由

    private fun Application.module() {
        routing {
            get("/") {
                call.respondText(ADMIN_HTML, ContentType.Text.Html)
            }

            get("/api/ping") {
                val body = buildJsonObject {
                    put("ok", true)
                    put("app", "ComponentVault")
                    put("version", "0.1.0")
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            }

            get("/api/parts") {
                val parts = Graph.repo.allParts()
                val arr = buildJsonArray { parts.forEach { add(partJson(it)) } }
                call.respondText(arr.toString(), ContentType.Application.Json)
            }

            get("/api/stats") {
                val s = Graph.repo.stats()
                val body = buildJsonObject {
                    put("kinds", s.totalKinds)
                    put("qty", s.totalQty)
                    put("low", s.lowKinds)
                    put("out", s.outKinds)
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            }

            get("/api/parts/{id}/txn") {
                val id = call.parameters["id"]?.toLongOrNull() ?: 0L
                val list = Graph.repo.txnsOf(id)
                val arr = buildJsonArray {
                    list.forEach { t ->
                        add(buildJsonObject {
                            put("id", t.id)
                            put("type", t.type)
                            put("delta", t.delta)
                            put("before", t.before)
                            put("after", t.after)
                            put("note", t.note)
                            put("createdAt", t.createdAt)
                        })
                    }
                }
                call.respondText(arr.toString(), ContentType.Application.Json)
            }

            post("/api/parts/{id}/move") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respondText(
                        """{"ok":false,"message":"id 无效"}""",
                        ContentType.Application.Json
                    )
                    return@post
                }
                val raw = call.receiveText()
                val obj = runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrNull()
                val delta = obj?.get("delta")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val type = obj?.get("type")?.jsonPrimitive?.content ?: if (delta >= 0) "IN" else "OUT"
                val note = obj?.get("note")?.jsonPrimitive?.content ?: ""
                val key = obj?.get("key")?.jsonPrimitive?.content ?: ""

                val r = Graph.repo.moveStock(
                    partId = id,
                    type = type,
                    delta = delta,
                    ref = "LAN",
                    note = note,
                    idempotencyKey = key
                )
                val body = buildJsonObject {
                    put("ok", r.ok)
                    put("qty", r.qty)
                    put("message", r.message)
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            }
        }
    }

    private fun partJson(p: Part): JsonObject = buildJsonObject {
        put("id", p.id)
        put("code", p.internalCode)
        put("mpn", p.mpn)
        put("name", p.name)
        put("category", p.category)
        put("pkg", p.packageName)
        put("qty", p.qty)
        put("low", p.lowThreshold)
        put("location", p.location)
        put("tags", p.tags)
        put("summary", p.summary)
        put("params", p.paramsJson)
    }

    // ------------------------------------------------------------ 通知

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.lan_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = getString(R.string.lan_channel_desc)
            nm?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val ip = localIp()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("局域网服务运行中")
            .setContentText("http://$ip:$port")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "lan_server"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 8080

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var portValue: Int = DEFAULT_PORT
            private set

        fun start(context: Context, port: Int = DEFAULT_PORT) {
            val intent = Intent(context, LanServerService::class.java)
                .putExtra(EXTRA_PORT, port)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LanServerService::class.java))
            isRunning = false
        }

        /** 取当前 Wi-Fi / 热点网卡上的 IPv4 地址 */
        fun localIp(): String {
            return try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress ?: "127.0.0.1"
            } catch (e: Exception) {
                "127.0.0.1"
            }
        }
    }

    private val ADMIN_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>元器件库存 · 电脑端</title>
<style>
  *{box-sizing:border-box}
  body{margin:0;font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;background:#f5f7fa;color:#0f172a}
  header{background:#1d4ed8;color:#fff;padding:14px 20px;display:flex;align-items:center;gap:14px}
  header h1{font-size:16px;margin:0;font-weight:600}
  .sub{font-size:12px;opacity:.8}
  main{padding:18px 20px}
  table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden;box-shadow:0 1px 3px rgba(15,23,42,.08)}
  th,td{padding:9px 12px;text-align:left;font-size:13px;border-bottom:1px solid #eef1f6}
  th{background:#f8fafc;font-weight:600;color:#475569;font-size:12px}
  tr:last-child td{border-bottom:none}
  .mono{font-family:ui-monospace,Consolas,monospace}
  .pill{display:inline-block;padding:1px 7px;border-radius:99px;font-size:11px;background:#eef2ff;color:#3730a3}
  .out{color:#dc2626;font-weight:600}
  .low{color:#d97706;font-weight:600}
  .ok{color:#059669}
  button{border:1px solid #cbd5e1;background:#fff;border-radius:6px;padding:2px 8px;cursor:pointer;font-size:12px}
  button:hover{background:#f1f5f9}
  input.qty{width:46px;text-align:center;border:1px solid #cbd5e1;border-radius:6px;padding:2px 4px;font-size:12px}
</style>
</head>
<body>
<header>
  <h1>元器件库存 · 电脑端管理台</h1>
  <span class="sub" id="stat">加载中…</span>
</header>
<main>
  <table>
    <thead><tr>
      <th>编号</th><th>型号</th><th>名称</th><th>封装</th><th>分类</th><th>库存</th><th>仓位</th><th>调整</th>
    </tr></thead>
    <tbody id="rows"></tbody>
  </table>
</main>
<script>
function esc(s){return String(s==null?'':s).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c]})}
function cls(p){ if(p.qty<=0) return 'out'; if(p.qty<=p.low) return 'low'; return 'ok'; }
function load(){
  fetch('/api/parts').then(function(r){return r.json()}).then(function(list){
    var h='';
    list.forEach(function(p){
      h+='<tr><td class="mono" style="color:#64748b">'+esc(p.code)+'</td>'
       + '<td class="mono" style="font-weight:600">'+esc(p.mpn)+'</td>'
       + '<td>'+esc(p.name)+'</td>'
       + '<td class="mono">'+esc(p.pkg)+'</td>'
       + '<td><span class="pill">'+esc(p.category)+'</span></td>'
       + '<td class="'+cls(p)+'">'+p.qty+'</td>'
       + '<td class="mono">'+esc(p.location)+'</td>'
       + '<td><button onclick="move('+p.id+',-1)">-1</button> '
       + '<input class="qty" id="q'+p.id+'" value="1"> '
       + '<button onclick="move('+p.id+',1)">+</button></td></tr>';
    });
    document.getElementById('rows').innerHTML=h;
  });
  fetch('/api/stats').then(function(r){return r.json()}).then(function(s){
    document.getElementById('stat').textContent=s.kinds+' 种 · 共 '+s.qty+' 件 · 低库存 '+s.low+' · 缺货 '+s.out;
  });
}
function move(id,sign){
  var el=document.getElementById('q'+id);
  var n=parseInt(el.value||'1',10); if(isNaN(n)||n<=0) n=1;
  var key='web-'+id+'-'+Date.now()+'-'+Math.random().toString(36).slice(2,8);
  fetch('/api/parts/'+id+'/move',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({delta:sign*n,type:sign>0?'IN':'OUT',note:'电脑端',key:key})})
   .then(function(r){return r.json()}).then(function(){load()});
}
load();
setInterval(load,15000);
</script>
</body>
</html>
"""
}
