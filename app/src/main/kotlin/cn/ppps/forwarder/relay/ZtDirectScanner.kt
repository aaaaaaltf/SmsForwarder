package cn.ppps.forwarder.relay

import cn.ppps.forwarder.utils.Log
import org.json.JSONArray
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ★ ZeroTier直连扫描器（被控端侧，2026-08-04新增）
 *
 * 中继服务关闭时，被控端无法收到 ztdirect0000 触发命令。
 * 本扫描器与PC被控端逻辑一致：周期调用 ZeroTier Central API 获取在线成员IP，
 * 逐个探测 56789 端口（手机控制端 DirectHostServer 监听端口，接受连接后会下发 CMD_GET_VERSION），
 * 探测到控制端即回调触发主动直连，完全脱离中继服务。
 *
 * 仅在无有效直连时扫描；直连建立后自动休眠等待，直连断开后恢复扫描。
 */
class ZtDirectScanner(
    /** 当前是否已有有效直连（有则暂停扫描） */
    private val isDirectActive: () -> Boolean,
    /** 探测到手机控制端时回调（触发主动直连） */
    private val onDirectFound: (phoneIp: String, port: Int) -> Unit,
) {
    private val TAG = "ZtDirectScanner"

    /** ZeroTier网络配置（与PC被控端 modules/zerotier_authorizer.py 一致） */
    private val ZT_NETWORK_ID = "d3ecf5726d21a61b"
    private val ZT_API_TOKEN = "JCDygwwhpTft8MCw4vhcIRHM86vkjuIM"
    private val ZT_API_URL = "https://api.zerotier.com/api/v1/network/$ZT_NETWORK_ID/member"

    /** 手机控制端监听端口 */
    private val CONTROL_PORT = 56789

    /** 扫描间隔 */
    private val SCAN_INTERVAL_MS = 15000L

    /** 成员缓存时长 */
    private val CACHE_MS = 60000L

    /** 同一IP触发直连的最小间隔（避免连接未建立期间反复重建） */
    private val RE_TRIGGER_MIN_MS = 60000L

    @Volatile
    private var running = false
    private var thread: Thread? = null

    // 在线成员IP缓存（60秒）
    private var memberCache: List<String>? = null
    private var memberCacheTime = 0L

    // 最近一次触发直连的IP与时间（防重复触发）
    private var lastTriggerIp: String? = null
    private var lastTriggerTime = 0L

    fun start() {
        if (running) return
        running = true
        thread = Thread({ scanLoop() }, "ZtDirectScan").apply { isDaemon = true }.also { it.start() }
        Log.i(TAG, "★ 已启动ZeroTier直连扫描（每15秒探测手机控制端${CONTROL_PORT}端口）")
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun scanLoop() {
        while (running) {
            try {
                // 已有有效直连则休眠等待
                if (isDirectActive()) {
                    sleepInterruptible(SCAN_INTERVAL_MS)
                    continue
                }
                // ① ZeroTier成员扫描（异地场景）
                scanZtMembers()
                if (!running) break
                // ② 局域网网段扫描（同WiFi场景，比ZT虚拟网更快更稳定）
                scanLanSubnet()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "扫描异常: ${e.message}")
            }
            sleepInterruptible(SCAN_INTERVAL_MS)
        }
    }

    /** ZeroTier成员IP扫描：Central API拉成员 → 探测56789 */
    private fun scanZtMembers() {
        val members = getOnlineMemberIps()
        val own = RelayServerHandler.getOwnZtIps()
        val now = System.currentTimeMillis()
        for (ip in members) {
            if (!running) break
            if (ip in own) continue
            if (probeControl(ip)) {
                if (!tryTrigger(ip, now)) break
                break
            }
        }
    }

    /** 局域网网段扫描：探测本机所在 /24 网段内所有IP的56789（并发，短超时） */
    private fun scanLanSubnet() {
        val lanBase = getLanIp()
            ?: return
        val prefix = lanBase.substringBeforeLast('.')
        if (prefix.length < 7 || prefix == lanBase) return
        // ★ 过滤本机所有IPv4（局域网+ZT），防止把自己的56789当成控制端（会切走真实控制端的直连）
        val ownIps = getAllLocalIps()
        val pool = Executors.newFixedThreadPool(16)
        val found = ConcurrentLinkedQueue<String>()
        for (octet in 1..254) {
            if (!running) break
            val ip = "$prefix.$octet"
            if (ip in ownIps) continue
            pool.execute {
                if (!running) return@execute
                if (probeControlFast(ip)) found.add(ip)
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        val now = System.currentTimeMillis()
        for (ip in found) {
            if (!running) break
            if (tryTrigger(ip, now)) break
        }
    }

    /** 冷却检查并触发直连，返回是否已触发 */
    private fun tryTrigger(ip: String, now: Long): Boolean {
        if (ip == lastTriggerIp && now - lastTriggerTime < RE_TRIGGER_MIN_MS) {
            Log.d(TAG, "跳过重复触发 $ip:$CONTROL_PORT（冷却期内）")
            return false
        }
        lastTriggerIp = ip
        lastTriggerTime = now
        Log.i(TAG, "★ 扫描发现手机控制端 $ip:$CONTROL_PORT，建立直连")
        onDirectFound(ip, CONTROL_PORT)
        return true
    }

    /** 获取本机局域网IPv4（排除回环与ZeroTier 172.2x 网段） */
    private fun getLanIp(): String? {
        return try {
            java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                java.util.Collections.list(ni.inetAddresses).forEach { addr ->
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: return@forEach
                        if (!ip.startsWith("172.2")) {
                            return ip
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "获取局域网IP失败: ${e.message}")
            null
        }
    }

    /** 获取本机所有IPv4地址（含局域网+ZeroTier虚拟网），用于扫描时过滤本机 */
    private fun getAllLocalIps(): Set<String> {
        val result = LinkedHashSet<String>()
        try {
            java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                java.util.Collections.list(ni.inetAddresses).forEach { addr ->
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: return@forEach
                        result.add(ip)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取本机IP失败: ${e.message}")
        }
        return result
    }

    /** 拉取ZeroTier在线成员IP（60秒缓存），失败返回空列表 */
    private fun getOnlineMemberIps(): List<String> {
        val now = System.currentTimeMillis()
        memberCache?.let {
            if (now - memberCacheTime < CACHE_MS) return it
        }
        val result = mutableListOf<String>()
        try {
            val conn = URL(ZT_API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "bearer $ZT_API_TOKEN")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val stream: InputStream = conn.inputStream
                val text = stream.bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    // ★ Central API 的 online 字段对离线节点可能为 null，不依赖它（与PC被控端一致），
                    // 只过滤授权成员，探测失败（超时/拒绝）的 IP 自然跳过
                    val config = obj.optJSONObject("config") ?: continue
                    if (!config.optBoolean("authorized", false)) continue
                    val ips = config.optJSONArray("ipAssignments") ?: continue
                    for (j in 0 until ips.length()) {
                        val ip = ips.optString(j)
                        if (ip.isNotBlank()) result.add(ip)
                    }
                }
            } else {
                Log.w(TAG, "获取ZT成员失败: HTTP ${conn.responseCode}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "获取ZT成员异常: ${e.message}")
        }
        memberCache = result
        memberCacheTime = now
        if (result.isNotEmpty()) {
            Log.i(TAG, "ZT在线成员: ${result.size} 个 ${result}")
        }
        return result
    }

    /**
     * 探测目标IP:56789是否为手机控制端：
     * 连接成功 + 2秒内收到数据（控制端接受连接后会立即下发CMD_GET_VERSION帧）即确认。
     */
    private fun probeControl(ip: String): Boolean {
        var s: Socket? = null
        return try {
            s = Socket()
            s!!.tcpNoDelay = true
            s!!.connect(InetSocketAddress(ip, CONTROL_PORT), 3000)
            s!!.soTimeout = 2000
            val buf = ByteArray(64)
            val n = s!!.getInputStream().read(buf)
            n > 0
        } catch (e: Exception) {
            false
        } finally {
            try {
                s?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 局域网快速探测：短超时（800ms连接 + 1s确认），用于 /24 网段并发扫描 */
    private fun probeControlFast(ip: String): Boolean {
        var s: Socket? = null
        return try {
            s = Socket()
            s!!.tcpNoDelay = true
            s!!.connect(InetSocketAddress(ip, CONTROL_PORT), 800)
            s!!.soTimeout = 1000
            val buf = ByteArray(64)
            val n = s!!.getInputStream().read(buf)
            n > 0
        } catch (e: Exception) {
            false
        } finally {
            try {
                s?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun sleepInterruptible(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
