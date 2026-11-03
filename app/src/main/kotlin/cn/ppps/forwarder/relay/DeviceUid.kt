package cn.ppps.forwarder.relay

import android.content.Context
import java.util.UUID

/**
 * ★ 2026-11-03【设备唯一ID】手机被控端的稳定设备标识 did。
 *
 * 为什么需要：中继把所有被控端都打上同一个公网出口 IP，控制端原来只能靠"设备名相同"判断
 * 是不是同一台重连 —— 两台同名设备（例如两台都叫"红米K40"）会被误合并成一条。
 * did 与设备名无关、与 clientId 无关，仅本机一份，是"同机"的可靠判据。
 *
 * 生成与持久化：
 *   - 首次读取时生成 32 位 hex UUID，写入 SharedPreferences("device_identity", "did")；
 *   - 之后每次启动原样读出，**固定不变**；
 *   - 卸载重装或清除应用数据后 SharedPreferences 被删 ⇒ 允许生成新的 did（符合需求）。
 *
 * 上报：由 [RelayServerHandler] 在 CMD_VERSION_INFO 尾部追加 `|did=<hex>`（ASCII，编码安全）。
 */
object DeviceUid {

    private const val SP_NAME = "device_identity"
    private const val KEY_DID = "did"

    @Volatile
    private var cached: String? = null

    /** 取得本机 did（线程安全；进程内缓存）。 */
    fun get(): String {
        cached?.let { if (it.isNotBlank()) return it }
        return synchronized(this) {
            cached?.let { if (it.isNotBlank()) return it }
            var value = ""
            try {
                val ctx: Context? = try {
                    cn.ppps.forwarder.App.context
                } catch (_: Throwable) {
                    null
                }
                if (ctx != null) {
                    val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    value = sp.getString(KEY_DID, null).orEmpty().trim()
                    if (value.isBlank()) {
                        value = newUid()
                        sp.edit().putString(KEY_DID, value).apply()
                    }
                } else {
                    // 极早期（App 未初始化）拿不到 Context：本次进程内使用临时值，
                    // 不落盘；下一次拿到 Context 时会生成并持久化为正式 did。
                    value = newUid()
                }
            } catch (_: Throwable) {
                if (value.isBlank()) value = newUid()
            }
            cached = value
            value
        }
    }

    private fun newUid(): String = UUID.randomUUID().toString().replace("-", "")
}
