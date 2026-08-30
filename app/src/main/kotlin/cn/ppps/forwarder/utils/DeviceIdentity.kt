package cn.ppps.forwarder.utils

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * ★★★ 2026-08-29 新增：本机被控端的【稳定唯一设备 ID】，用于 devstate 尾部追加字段。
 *
 * 背景（"任意手机互连"的真正根治点）：
 *   控制端判断"这个 100.x 是不是本机被控端的另一个入口"时，过去只能用
 *   devstate 的 name|锁屏|屏幕|电量|充电 指纹做比对。该指纹里【没有任何唯一 ID】，
 *   同型号两台手机只要设备名相同、瞬时状态相同就会碰撞；叠加"失效节点删除后 100.x
 *   被 Tailscale 回收复用"，新装被控端就可能被误判成本机/已知设备而永久消失。
 *
 * 设计约束：
 *   - 跨重启稳定：首次生成后持久化到 SharedPreferences（新 SP 文件，不改任何既有 key）。
 *   - 同一台机器上"被控端"与"控制端"各自唯一：本文件只在被控端工程内，
 *     且 SP 属于 cn.ppps.forwarder 私有目录，控制端进程读不到，天然不复用。
 *   - ANDROID_ID 只作为可选熵源：它在同一 Android 版本/签名下对每个 App 不同，
 *     但 ROM 修复过重复值问题，故不能单独作为身份，必须再拼一个随机 UUID。
 *   - 纯 ASCII、无 '|' 字符：可直接作为 devstate 的分段负载尾字段。
 */
object DeviceIdentity {

    private const val SP_NAME = "device_identity_v1"
    private const val SP_KEY_UUID = "unique_device_uuid"
    private const val SP_KEY_ANDROID_ID_SEED = "android_id_seed"

    @Volatile
    private var cached: String? = null

    /** 返回稳定唯一设备 ID（异常时也保证返回非空且稳定的值，绝不抛给上报链路） */
    @JvmStatic
    fun uniqueDeviceId(ctx: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val id = loadOrCreate(ctx)
            cached = id
            return id
        }
    }

    private fun loadOrCreate(ctx: Context): String {
        try {
            val sp = ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            val existing = sp.getString(SP_KEY_UUID, null)
            if (!existing.isNullOrEmpty() && existing.indexOf('|') < 0) {
                return existing
            }
            val seed = androidIdSeed(ctx)
            val fresh = "did:" + seed + "-" + UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString(SP_KEY_UUID, fresh).commit()
            return fresh
        } catch (t: Throwable) {
            // 极端情况下（SP 不可用）退化为进程内稳定值：至少本次运行期间唯一且不变，
            // 不会比旧的"无唯一 ID"指纹更差。
            return "did:fallback-" + UUID.randomUUID().toString().replace("-", "")
        }
    }

    /** ANDROID_ID 作为可选熵源；读不到就用 "na"，并且同样持久化以免后续变化导致 ID 漂移 */
    private fun androidIdSeed(ctx: Context): String {
        return try {
            val sp = ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            val fixed = sp.getString(SP_KEY_ANDROID_ID_SEED, null)
            if (!fixed.isNullOrEmpty()) return fixed
            @Suppress("HardwareIds")
            val raw = Settings.Secure.getString(ctx.applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
            val cleaned = if (raw.isNullOrEmpty()) "na" else raw.filter { it.isLetterOrDigit() }.take(16)
            sp.edit().putString(SP_KEY_ANDROID_ID_SEED, cleaned.ifEmpty { "na" }).commit()
            cleaned.ifEmpty { "na" }
        } catch (t: Throwable) {
            "na"
        }
    }
}
