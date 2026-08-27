package cn.ppps.forwarder.relay

import cn.ppps.forwarder.utils.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ★ Tailscale 直连扫描器（被控端侧，2026-08-04新增，2026-08-16 改造为 Tailscale）
 *
 * 中继服务关闭时，被控端无法收到 ztdirect0000 触发命令。
 * 本扫描器与PC被控端逻辑一致：周期获取 Tailscale 在线成员IP（localapi /status），
 * 逐个探测 56789 端口（手机控制端 DirectHostServer 监听端口，接受连接后会下发 CMD_GET_VERSION），
 * 探测到控制端即回调触发主动直连，完全脱离中继服务。
 *
 * ★ 2026-08-16 修复：多控制端场景（华为+红米控制端并存）下，被控端必须持续扫描所有
 *   在线成员，对每台【未连接】的控制端分别建立直连。原逻辑"存在任一有效直连即整体休眠"
 *   会导致被控端只连上第一台控制端后，其余控制端（如华为控制端）永远等不到被控端连入。
 */
class TailscaleDirectScanner(
    /** 当前是否已有有效直连（★ 2026-08-16 不再作为整体休眠依据，仅保留接口兼容） */
    private val isDirectActive: () -> Boolean,
    /** 探测到手机控制端时回调（触发主动直连） */
    private val onDirectFound: (phoneIp: String, port: Int) -> Unit,
    /** 已连接的控制端IP集合（扫描时跳过，避免重复建连；多控制端各自独立连接） */
    private val connectedIps: () -> Set<String> = { emptySet() },
    /**
     * ★ 2026-08-27 省电：是否处于"有人在用"状态（亮屏 或 已有控制端连接）。
     *   返回 true 时保持原有的 15 秒全速扫描；返回 false（灭屏且无人连接的纯待机）时自动降频。
     *   默认恒为 true，即未接入该判据时行为与改造前完全一致。
     */
    private val isBusy: () -> Boolean = { true },
) {
    private val TAG = "TailscaleDirectScanner"

    /** 手机控制端监听端口 */
    private val CONTROL_PORT = 56789

    /** 扫描间隔（忙时：亮屏或有控制端连接，与改造前一致） */
    private val SCAN_INTERVAL_MS = 15000L

    /**
     * ★ 省电：待机（灭屏且无控制端连接）时的扫描间隔。
     *   依据：待机期间每 15 秒一轮的扫描里，仅 /24 局域网探测就要新建 16 线程并发发起 254 次 TCP
     *   连接（TailscaleDirectScanner.scanLanSubnet），每轮都让 Wi-Fi 射频与 CPU 从空闲中被拉起来；
     *   而"控制端在用户没用手机的时候来连直连"本身就是低频事件，60 秒的发现延迟对体验无感，
     *   却把待机的射频唤醒次数降到 1/4。
     */
    private val SCAN_INTERVAL_IDLE_MS = 60000L

    /**
     * ★ 省电：待机时每 N 轮才做一次 /24 局域网全段扫描（60s × 4 = 4 分钟）。
     *   Tailscale 成员扫描很便宜（一次 localapi 查询 + 逐个探测在线成员），保持每轮执行，
     *   保证异地直连（真正依赖 Tailscale 的场景）发现速度不受影响。
     */
    private val LAN_SWEEP_EVERY_IDLE_ROUNDS = 4

    /** 同一IP触发直连的最小间隔（避免连接未建立期间反复重建） */
    private val RE_TRIGGER_MIN_MS = 60000L

    /**
     * ★ 2026-08-27 省电：/24 并发探测线程数，原为每轮新建的 16。
     *   池子复用后 8 条即可在 800ms 超时下于 ~26 秒内扫完 254 个地址（与旧实现同量级），
     *   但常驻线程与调度开销减半。
     */
    private val LAN_PROBE_THREADS = 8

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** ★ 省电：/24 扫描用的线程池改为整轮生命周期复用，避免每 15 秒新建/销毁 16 个线程 */
    private var probePool: java.util.concurrent.ExecutorService? = null

    /** 已完成的扫描轮数（用于待机时按轮降频 /24 扫描） */
    private var scanRound = 0

    // 最近一次触发直连的IP与时间（防重复触发）
    private var lastTriggerIp: String? = null
    private var lastTriggerTime = 0L

    fun start() {
        if (running) return
        running = true
        if (probePool == null) {
            probePool = Executors.newFixedThreadPool(LAN_PROBE_THREADS) { r ->
                Thread(r, "LanProbe").apply { isDaemon = true }
            }
        }
        thread = Thread({ scanLoop() }, "TailscaleDirectScan").apply { isDaemon = true }.also { it.start() }
        Log.i(TAG, "★ 已启动Tailscale直连扫描（忙时每${SCAN_INTERVAL_MS / 1000}秒、待机时每${SCAN_INTERVAL_IDLE_MS / 1000}秒探测手机控制端${CONTROL_PORT}端口）")
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try {
            probePool?.shutdownNow()
        } catch (_: Exception) {
        }
        probePool = null
    }

    private fun scanLoop() {
        while (running) {
            val busy = try { isBusy() } catch (_: Throwable) { true }
            scanRound++
            // ★ 待机时 /24 全段扫描降频到每 LAN_SWEEP_EVERY_IDLE_ROUNDS 轮一次（Tailscale 成员扫描保持每轮）
            val doLanSweep = busy || scanRound % LAN_SWEEP_EVERY_IDLE_ROUNDS == 0
            try {
                // ★ 2026-08-16 修复：不再因"存在任一直连"整体休眠。
                //   多控制端场景（华为+红米控制端并存）下，被控端需持续探测所有在线控制端，
                //   对每台未连接的控制端分别建立直连；已连接的控制端由 connectedIps 跳过。
                // ① Tailscale 成员扫描（异地场景）
                scanTsMembers()
                if (!running) break
                // ② 局域网网段扫描（同WiFi场景，比Tailscale虚拟网更快更稳定）
                if (doLanSweep) {
                    scanLanSubnet()
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "扫描异常: ${e.message}")
            }
            sleepInterruptible(if (busy) SCAN_INTERVAL_MS else SCAN_INTERVAL_IDLE_MS)
        }
    }

    /** Tailscale 成员IP扫描：localapi /status 拉成员 → 探测56789 */
    private fun scanTsMembers() {
        val members = getOnlineMemberIps()
        val own = RelayServerHandler.getOwnTsIps()
        val connected = connectedIps()
        val now = System.currentTimeMillis()
        for (ip in members) {
            if (!running) break
            if (ip in own) continue
            // ★ 已连接的控制端跳过（多控制端各自独立建连，避免重复探测已连接的IP）
            if (ip in connected) continue
            // ★ 2026-08-25 修复：遍历所有成员而非命中一个即break——否则同机自连节点/冷却期会
            //   卡住扫描，导致真实外部控制端（如对方手机56789）永不触发直连。
            if (probeControl(ip)) {
                tryTrigger(ip, now)
            }
        }
    }

    /** 局域网网段扫描：探测本机所在 /24 网段内所有IP的56789（并发，短超时） */
    private fun scanLanSubnet() {
        val lanBase = getLanIp()
            ?: return
        val prefix = lanBase.substringBeforeLast('.')
        if (prefix.length < 7 || prefix == lanBase) return
        // ★ 过滤本机所有IPv4（局域网+Tailscale），防止把自己的56789当成控制端（会切走真实控制端的直连）
        val ownIps = getAllLocalIps()
        // ★ 2026-08-27 省电：线程池由 start()/stop() 创建与销毁，此处复用；
        //   旧实现每轮 Executors.newFixedThreadPool(16) + shutdown()，等于每分钟新建/销毁约 64 个线程。
        val pool = probePool ?: Executors.newFixedThreadPool(LAN_PROBE_THREADS).also { probePool = it }
        val found = ConcurrentLinkedQueue<String>()
        // ★ 等待方式改为计数闩锁：线程池生命周期已长于单次扫描，不能再 awaitTermination(pool)
        val latch = java.util.concurrent.CountDownLatch(254)
        for (octet in 1..254) {
            if (!running) break
            val ip = "$prefix.$octet"
            if (ip in ownIps) {
                latch.countDown()
                continue
            }
            pool.execute {
                try {
                    if (!running) return@execute
                    if (probeControlFast(ip)) found.add(ip)
                } finally {
                    latch.countDown()
                }
            }
        }
        try {
            latch.await(30, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        val now = System.currentTimeMillis()
        val connected = connectedIps()
        for (ip in found) {
            if (!running) break
            // ★ 已连接的控制端跳过
            if (ip in connected) continue
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

    /** 获取本机局域网IPv4（排除回环与 Tailscale 100.64-100.127 虚拟网段） */
    private fun getLanIp(): String? {
        return try {
            java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                java.util.Collections.list(ni.inetAddresses).forEach { addr ->
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: return@forEach
                        if (!isTailscaleCgnat(ip)) {
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

    /** 判断是否为 Tailscale CGNAT 段（100.64.0.0/10 = 100.64-100.127.x.x） */
    private fun isTailscaleCgnat(ip: String): Boolean {
        val p = ip.split(".")
        if (p.size != 4) return false
        if (p[0] != "100") return false
        return p[1].toIntOrNull()?.let { it in 64..127 } ?: false
    }

    /** 获取本机所有IPv4地址（含局域网+虚拟网），用于扫描时过滤本机 */
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

    /** 拉取 Tailscale 在线成员IP（localapi /status，免缓存：TailscaleManager 自带解析） */
    private fun getOnlineMemberIps(): List<String> {
        val result = try {
            cn.ppps.forwarder.tailscale.TailscaleManager.getOnlineMemberIps()
        } catch (t: Throwable) {
            emptyList()
        }
        if (result.isNotEmpty()) {
            Log.i(TAG, "Tailscale在线成员: ${result.size} 个 ${result}")
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
