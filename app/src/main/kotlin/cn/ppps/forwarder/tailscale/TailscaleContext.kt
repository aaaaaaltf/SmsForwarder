package cn.ppps.forwarder.tailscale

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Build
import cn.ppps.forwarder.utils.Log
import libtailscale.AppContext
import org.json.JSONArray
import java.net.NetworkInterface
import java.util.Collections

/**
 * ★ libtailscale.AppContext 实现（2026-08-16 Tailscale 集成）
 * 提供 Android 平台能力给 Go 后端：加密偏好存储、网络接口JSON、系统信息等。
 * 偏好存储使用普通 SharedPreferences（app 位于 device-protected storage，足够安全）。
 */
class TailscaleContext(private val ctx: Context) : AppContext {

    companion object {
        private const val TAG = "TailscaleCtx"
        private const val PREFS_NAME = "tailscale_state"
        private const val STATESTORE_PREFIX = "statestore-"

        /** 单例实例（App.onCreate 时创建） */
        @Volatile
        var instance: TailscaleContext? = null
            private set

        fun init(ctx: Context) {
            if (instance == null) {
                instance = TailscaleContext(ctx.applicationContext)
            }
        }

        fun get(): TailscaleContext = checkNotNull(instance) { "TailscaleContext not initialized" }
    }

    private val sp: SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun log(tag: String, logLine: String) {
        Log.i(tag, logLine)
    }

    @Throws(Exception::class)
    override fun encryptToPref(prefKey: String?, plaintext: String?) {
        sp.edit().putString(prefKey, plaintext).commit()
    }

    @Throws(Exception::class)
    override fun decryptFromPref(prefKey: String?): String? {
        return sp.getString(prefKey, null)
    }

    override fun getStateStoreKeysJSON(): String {
        val keys = sp.all.keys
            .filter { it.startsWith(STATESTORE_PREFIX) }
            .map { it.removePrefix(STATESTORE_PREFIX) }
        return JSONArray(keys).toString()
    }

    @Throws(Exception::class)
    override fun getOSVersion(): String = Build.VERSION.RELEASE

    @Throws(Exception::class)
    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()

    @Throws(Exception::class)
    override fun getDeviceName(): String {
        val model = Build.MODEL ?: "android"
        return "android-$model"
    }

    override fun getInstallSource(): String = "unknown"

    override fun shouldUseGoogleDNSFallback(): Boolean = true

    @Throws(Exception::class)
    override fun isChromeOS(): Boolean =
        ctx.packageManager.hasSystemFeature("android.hardware.type.pc")

    @Throws(Exception::class)
    override fun isClientLoggingEnabled(): Boolean = false

    @Throws(Exception::class)
    override fun getInterfacesAsJson(): String {
        val out = JSONArray()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                try {
                    val addrs = JSONArray()
                    for (ia in nif.interfaceAddresses) {
                        val addr = ia.address ?: continue
                        val host = addr.hostAddress ?: continue
                        addrs.put(org.json.JSONObject().apply {
                            put("ip", host)
                            put("prefixLen", ia.networkPrefixLength.toInt())
                        })
                    }
                    out.put(org.json.JSONObject().apply {
                        put("name", nif.name)
                        put("index", nif.index)
                        put("mtu", nif.mtu)
                        put("up", nif.isUp)
                        put("broadcast", nif.supportsMulticast())
                        put("loopback", nif.isLoopback)
                        put("pointToPoint", nif.isPointToPoint)
                        put("multicast", nif.supportsMulticast())
                        put("addrs", addrs)
                    })
                } catch (_: Exception) {
                    // skip broken interface
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getInterfacesAsJson异常: ${t.message}")
        }
        return out.toString()
    }

    override fun getPlatformDNSConfig(): String = ""

    @Throws(Exception::class)
    override fun getSyspolicyStringValue(key: String?): String = ""

    @Throws(Exception::class)
    override fun getSyspolicyBooleanValue(key: String?): Boolean = false

    @Throws(Exception::class)
    override fun getSyspolicyStringArrayJSONValue(key: String?): String = "[]"

    override fun hardwareAttestationKeySupported(): Boolean = false

    @Throws(Exception::class)
    override fun hardwareAttestationKeyCreate(): String = throw UnsupportedOperationException("no hw attestation")

    @Throws(Exception::class)
    override fun hardwareAttestationKeyLoad(id: String?) = throw UnsupportedOperationException("no hw attestation")

    @Throws(Exception::class)
    override fun hardwareAttestationKeyPublic(id: String?): ByteArray =
        throw UnsupportedOperationException("no hw attestation")

    @Throws(Exception::class)
    override fun hardwareAttestationKeySign(id: String?, data: ByteArray?): ByteArray =
        throw UnsupportedOperationException("no hw attestation")

    @Throws(Exception::class)
    override fun hardwareAttestationKeyRelease(id: String?) =
        throw UnsupportedOperationException("no hw attestation")

    @SuppressLint("MissingPermission")
    override fun bindSocketToNetwork(fd: Int): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            if (net == null) {
                Log.d(TAG, "bindSocketToNetwork: no active network")
                false
            } else {
                android.os.ParcelFileDescriptor.fromFd(fd).use { pfd ->
                    net.bindSocket(pfd.fileDescriptor)
                }
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bindSocketToNetwork失败: ${t.message}")
            false
        }
    }

    @Throws(Exception::class)
    override fun getUserCACertsPEM(): ByteArray = ByteArray(0)
}
