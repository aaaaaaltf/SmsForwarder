package cn.ppps.forwarder.tailscale

import android.content.Intent
import android.net.VpnService
import cn.ppps.forwarder.utils.Log
import libtailscale.IPNService
import libtailscale.ParcelFileDescriptor
import libtailscale.VPNServiceBuilder
import java.util.UUID

/**
 * ★ Tailscale VPN 服务（2026-08-16 集成）
 * 实现 libtailscale.IPNService：Go 后端需要建立 VPN 时调用
 * Libtailscale.requestVPN(this)，本服务作为 Android VpnService 建立 tun 通道。
 * 首次需用户授权（VpnService.prepare），授权一次后自动建立。
 */
class TailscaleVpnService : VpnService(), IPNService {

    companion object {
        private const val TAG = "TailscaleVpn"
        const val ACTION_START_VPN = "cn.ppps.forwarder.tailscale.START_VPN"

        @Volatile
        private var instance: TailscaleVpnService? = null

        /** 当前是否已建立 VPN（tun 通道） */
        @Volatile
        var vpnEstablished = false
            private set

        fun runningService(): TailscaleVpnService? = instance

        /** companion 内部设置 VPN 状态（供嵌套 Builder 使用，setter 为 private） */
        fun setVpnEstablished(value: Boolean) {
            vpnEstablished = value
        }

        /** ★ 关闭 VPN 通道（中继正常时调用，节省资源；Go 后端保留以便快速重建直连） */
        fun shutdown() {
            try {
                instance?.let { svc ->
                    libtailscale.Libtailscale.serviceDisconnect(svc)
                    svc.stopSelf()
                }
            } catch (t: Throwable) {
                cn.ppps.forwarder.utils.Log.w(TAG, "关闭 Tailscale VPN 失败: ${t.message}")
            }
            vpnEstablished = false
        }
    }

    private val randomID: String = UUID.randomUUID().toString()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "TailscaleVpnService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ★ 2026-08-16 修复：startForegroundService 启动的服务必须在 5 秒内调用 startForeground()，
        //   否则抛 ForegroundServiceDidNotStartInTimeException 崩溃。VpnService 建立 tun 前先置为前台。
        startForegroundCompat()
        when (intent?.action) {
            ACTION_START_VPN -> {
                Log.i(TAG, "启动 Tailscale VPN")
                TailscaleManager.onVpnRequested()
                libtailscale.Libtailscale.requestVPN(this)
            }
        }
        return START_STICKY
    }

    /** 兼容各 API 级别的前台通知（VpnService 的通知系统会自动隐藏，仅用于满足 FGS 时限要求） */
    private fun startForegroundCompat() {
        try {
            val id = 0x5453 // "TS"
            val notification: android.app.Notification = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.Notification.Builder(this, "tailscale_vpn")
                    .setContentTitle("Tailscale VPN")
                    .setContentText("Tailscale 正在运行")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
                    .setContentTitle("Tailscale VPN")
                    .setContentText("Tailscale 正在运行")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .build()
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.createNotificationChannel(android.app.NotificationChannel("tailscale_vpn", "Tailscale VPN", android.app.NotificationManager.IMPORTANCE_MIN))
            }
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // ★ 2026-08-26 Android 14+ 必须传 foregroundServiceTypes；specialUse 对应 Manifest 中声明的类型
                startForeground(id, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(id, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground 失败: ${t.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ★★★ 2026-08-16 修复TUN写入失败（wg: Failed to write packets to TUN device）：
        //   App进程被系统重建/服务重启后，VpnService被销毁但Go后端仍持有旧tun fd继续写入→I/O error。
        //   onDestroy必须主动通知Go后端断开VPN并释放fd，避免旧fd悬挂导致后续直连数据面异常。
        try {
            if (instance === this) {
                libtailscale.Libtailscale.serviceDisconnect(this)
                instance = null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onDestroy serviceDisconnect 失败: ${t.message}")
        }
        vpnEstablished = false
        Log.i(TAG, "TailscaleVpnService onDestroy")
    }

    override fun onRevoke() {
        super.onRevoke()
        vpnEstablished = false
        TailscaleManager.notifyVpnRevoked()
        Log.w(TAG, "VPN 授权被撤销")
    }

    // ==================== libtailscale.IPNService ====================

    override fun id(): String = randomID

    override fun protect(fd: Int): Boolean {
        return try {
            super.protect(fd)
        } catch (t: Throwable) {
            Log.w(TAG, "protect($fd)失败: ${t.message}")
            false
        }
    }

    override fun newBuilder(): VPNServiceBuilder = TailscaleVpnBuilder(Builder())

    override fun close() {
        // 由 Go 侧在断开时调用
        Log.i(TAG, "IPNService.close() 由 Go 后端调用")
    }

    override fun disconnectVPN() {
        stopSelf()
    }

    override fun updateVpnStatus(status: Boolean) {
        vpnEstablished = status
        Log.i(TAG, "updateVpnStatus: $status")
        // ★ 2026-08-16 竞态修复：VPN 建立后若中继已正常，立即关闭 VPN（节省资源）
        if (status) {
            TailscaleManager.onVpnEstablished()
        }
    }
}

/** VPNServiceBuilder 实现：桥接 Go 的 VPN 配置到 Android VpnService.Builder */
private class TailscaleVpnBuilder(private val builder: VpnService.Builder) : VPNServiceBuilder {

    @Throws(Exception::class)
    override fun addAddress(ip: String?, prefixLen: Int) {
        ip?.let { builder.addAddress(it, prefixLen) }
    }

    @Throws(Exception::class)
    override fun addDNSServer(dns: String?) {
        dns?.let { builder.addDnsServer(it) }
    }

    @Throws(Exception::class)
    override fun addRoute(ip: String?, prefixLen: Int) {
        ip?.let { builder.addRoute(it, prefixLen) }
    }

    @Throws(Exception::class)
    override fun addSearchDomain(domain: String?) {
        domain?.let { builder.addSearchDomain(it) }
    }

    @Throws(Exception::class)
    override fun excludeRoute(ip: String?, prefixLen: Int) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            try {
                val inetAddress = java.net.InetAddress.getByName(ip)
                builder.excludeRoute(android.net.IpPrefix(inetAddress, prefixLen))
            } catch (_: Throwable) {
            }
        }
    }

    @Throws(Exception::class)
    override fun setMTU(mtu: Int) {
        builder.setMtu(mtu)
    }

    @Throws(Exception::class)
    override fun establish(): ParcelFileDescriptor? {
        val fd = builder.establish() ?: return null
        TailscaleVpnService.setVpnEstablished(true)
        return TailscaleParcelFd(fd)
    }
}

/** ParcelFileDescriptor 实现 */
private class TailscaleParcelFd(private val fd: android.os.ParcelFileDescriptor) :
    ParcelFileDescriptor {

    @Throws(Exception::class)
    override fun detach(): Int = fd.detachFd()
}


