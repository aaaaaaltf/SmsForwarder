package cn.ppps.forwarder.tailscale

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cn.ppps.forwarder.utils.Log
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ★★★ 2026-08-29 被控端 Tailscale OAuth 凭证保护（加密下发 + 加密落盘）
 *
 * 与 PC 端 modules/cred_crypto.py + modules/cred_channel.py 的 **v2 信封逐字节兼容**：
 *   HELLO(被控→控制)：{"v":2,"pk":<我方X25519公钥b64>,"n":<helloNonce>,"ts":<秒>}
 *   凭证帧(控制→被控)：{"v":2,"h":<回带的helloNonce>,"pk":<对方公钥b64>,
 *                      "n":<IV b64>,"ts":<秒>,"ct":<AES-256-GCM密文b64>}
 *   会话密钥 = HKDF-SHA256(ECDH(我方私钥,对方公钥), salt=helloNonce原字节,
 *                          info="remote_admin/tailscale-cred/v2", L=32)
 *   AAD      = "remote_admin|tscred000000|ctrl2cli|v=2|ts=<ts>|n=<n>|h=<h>"
 *   明文     = {"id":"..","secret":"..","v":2}
 *
 * 安全设计（与 PC 端一致的 fail-closed 语义）：
 *  1. 【传输加密】链路只出现 base64 公钥与密文，明文凭证不上线。未认证临时 ECDH，
 *     边界与 PC 端相同：能在此 TCP 会话上注入帧者可冒充控制端拿明文——它本来也
 *     能直接注入任意命令，故本侧不提供对等身份认证（要提供需预置控制端公钥）。
 *  2. 【绝不明文回落】version != 2 / 无待用会话 / 时间窗超界 / GCM 标签不符
 *     一律拒绝，**绝不"解不开就当明文解析"**。
 *  3. 【一次性会话】HELLO 发出去的私钥只存内存，TTL 60 秒，取用即作废。
 *  4. 【防重放】已解密帧的 IV 进 15 分钟 TTL 缓存，重复即拒。
 *  5. 【落盘加密】收到的凭证用 Android Keystore 里**不可导出**的 AES-256-GCM 密钥
 *     包裹后才写 SharedPreferences；Keystore 不可用时直接拒绝保存，绝不写明文。
 *
 * 本文件不含任何真实凭证字面量。
 */
object TailscaleCredGuard {

    private const val TAG = "TsCredGuard"

    /** 与 PC 端 cred_crypto.CRED_PAYLOAD_VERSION 一致 */
    private const val PAYLOAD_VERSION = 2

    /** 与 PC 端 cred_crypto._INFO_LABEL 一致（HKDF info） */
    private const val HKDF_INFO = "remote_admin/tailscale-cred/v2"

    /** 与 PC 端 cred_crypto.build_aad 的方向串一致（控制端→被控端） */
    private const val DIRECTION = "ctrl2cli"

    /** 与 PC 端 cred_crypto.TS_WINDOW_SECONDS 一致 */
    private const val TS_WINDOW_SECONDS = 120L

    /** 与 PC 端 cred_crypto.HELLO_TTL_SECONDS 一致 */
    private const val HELLO_TTL_MS = 60_000L

    /** 与 PC 端 cred_crypto.REPLAY_CACHE_SECONDS 一致 */
    private const val REPLAY_TTL_MS = 900_000L

    /** 落盘 blob 前缀（一眼区分密文/明文，便于 grep 与人工核对） */
    private const val KS_PREFIX = "ks:v1:"

    private const val SP_NAME = "tailscale_settings"
    private const val SP_KEY_ID = "oauth_client_id_enc"
    private const val SP_KEY_SECRET = "oauth_client_secret_enc"

    private const val KS_ALIAS = "cn.ppps.forwarder.tailscale.cred"

    private val random = SecureRandom()

    // ---------------------------------------------------------------- 一次性会话

    /** 发出一次 HELLO 后暂存的会话（私钥只留在内存，取用即作废） */
    private class PendingSession(
        val privRaw: ByteArray,
        val helloNonceB64: String,
        val expireAtMs: Long,
    ) {
        @Volatile var used = false
        fun expired(now: Long = SystemClock.elapsedRealtime()) = now > expireAtMs
    }

    private val pending = ConcurrentHashMap<String, PendingSession>()
    private val seenNonces = ConcurrentHashMap<String, Long>()

    /** 两次 HELLO 的最小间隔：避免每收到一条命令就向控制端要一次凭证 */
    private const val HELLO_MIN_INTERVAL_MS = 60_000L

    /**
     * ★ 2026-08-30 节流粒度：由"全局"改为"按对端连接"。
     *
     * 原实现只有一个全局 lastHelloAt。被控端同时挂着中继与直连监听多条控制通道，
     * 每条通道上的常规命令（探活/取状态，实测约 10 秒一次）都会走 maybeRequestCredential，
     * 于是"最先抢到 60 秒窗口的那条连接"独吞了所有 HELLO —— 其余对端（包括真正持有
     * 凭证、能应答的那一个）永远拿不到发送机会，全新安装的手机因此永远拿不到凭证。
     * 现在每个对端各记自己的时间戳，窗口互不抢占。
     */
    private val lastHelloAt = ConcurrentHashMap<String, Long>()

    /** 节流表规模上限：长跑时控制端连接反复重建，避免表无界增长 */
    private const val THROTTLE_MAX_KEYS = 64

    /**
     * 构造一帧 HELLO 的 payload（★ 不含任何凭证，只有临时公钥与随机 salt）。
     * @return payload JSON 字符串；Crypto 不可用时返回 null（调用方必须放弃申请，不得改要明文）
     */
    fun buildHelloPayload(): String? {
        return try {
        purgeExpired()
        val priv = ByteArray(32).also { random.nextBytes(it) }
        val pub = x25519Public(priv) ?: return null
        val nonce = randomB64(16)
        pending[nonce] = PendingSession(priv, nonce, SystemClock.elapsedRealtime() + HELLO_TTL_MS)
        val obj = JSONObject()
            .put("v", PAYLOAD_VERSION)
            .put("pk", b64u(pub))
            .put("n", nonce)
            .put("ts", System.currentTimeMillis() / 1000L)
        obj.toString()
    } catch (t: Throwable) {
        Log.w(TAG, "HELLO 生成失败: ${t.javaClass.simpleName}")
        null
    }
    }

    /** HELLO 发出失败时立刻作废会话，别把私钥多留在内存里 */
    fun abandonHello(helloNonceB64: String?) {
        if (helloNonceB64.isNullOrEmpty()) return
        pending.remove(helloNonceB64)?.let { wipe(it.privRaw) }
    }

    /**
     * 该对端距上次 HELLO 是否仍在节流窗口内（★ 按连接独立计数）。
     *
     * @param key 对端标识：直连监听传 connId(Long)，中继/TS 直连传 sender 对象；null 退化为全局键。
     * @return true = 仍在窗口内不要发；false = 已放行并记时
     */
    fun helloThrottled(key: Any? = null): Boolean {
        val k = throttleKey(key)
        val now = SystemClock.elapsedRealtime()
        val prev = lastHelloAt[k]
        if (prev != null && now - prev < HELLO_MIN_INTERVAL_MS) return true
        lastHelloAt[k] = now
        trimThrottleTable(now)
        return false
    }

    /** 为某对端记一次"HELLO 刚发过"的时间戳（自发路径用，避免命令路径紧接着重复发送） */
    fun markHelloSent(key: Any? = null) {
        val now = SystemClock.elapsedRealtime()
        lastHelloAt[throttleKey(key)] = now
        trimThrottleTable(now)
    }

    /** 对端标识归一化：数值型（connId）与对象型（sender 实例）分别加前缀，避免键冲突 */
    private fun throttleKey(key: Any?): String = when (key) {
        null -> "global"
        is String -> key
        is Number -> "conn:$key"
        else -> "obj:" + System.identityHashCode(key)
    }

    /** 供日志用：把对端标识归一化成可读字符串（只含 connId/对象哈希，绝不含凭证） */
    fun describeThrottleKey(key: Any?): String = throttleKey(key)

    private fun trimThrottleTable(now: Long) {
        if (lastHelloAt.size <= THROTTLE_MAX_KEYS) return
        // 先清过期项；仍超限则整表重建（节流表丢了只会多发一次 HELLO，无正确性风险）
        lastHelloAt.entries.filter { now - (it.value ?: 0L) > HELLO_MIN_INTERVAL_MS }
            .forEach { lastHelloAt.remove(it.key) }
        if (lastHelloAt.size > THROTTLE_MAX_KEYS) lastHelloAt.clear()
    }

    /**
     * 解一帧 v2 加密凭证。
     *
     * @return 成功返回 Pair(id, secret)；任何一步失败返回 null 并只打脱敏原因。
     *         ★ 绝不回落"把 payload 当明文 v1 解析"。
     */
    fun openCredFrame(payloadBytes: ByteArray): Pair<String, String>? {
        val (helloNonce, peerPubB64, nonceB64, ctB64, ts, version) = try {
            val o = JSONObject(String(payloadBytes, Charsets.US_ASCII))
            Quint(
                o.optString("h", ""), o.optString("pk", ""), o.optString("n", ""),
                o.optString("ct", ""), o.optLong("ts", 0L), o.optInt("v", 0),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "★ 凭证帧格式非法（拒绝，不回落明文）")
            return null
        }
        if (version != PAYLOAD_VERSION) {
            Log.w(TAG, "★ 凭证帧版本不支持 v=$version（仅接受 v=2，明文帧一律拒绝）")
            return null
        }
        if (helloNonce.isEmpty() || ctB64.isEmpty()) {
            Log.w(TAG, "★ 凭证帧不是 v2 信封（拒绝，不回落明文）")
            return null
        }
        // 校验顺序（照搬 PC 端踩过的坑）：先确认"这帧属于本会话"，再查时间窗/字段完整性，
        // 最后才把 IV 记进重放缓存 —— 否则被拒的垃圾帧会污染缓存，紧跟的合法帧反被判为重放。
        val ch = pending.remove(helloNonce)
        if (ch == null || ch.used || ch.expired()) {
            Log.w(TAG, "★ 无匹配的待用 HELLO 会话（旧帧/别的会话/已过期），拒绝")
            return null
        }
        ch.used = true
        return try {
            val nowSec = System.currentTimeMillis() / 1000L
            if (ts <= 0L || Math.abs(nowSec - ts) > TS_WINDOW_SECONDS) {
                Log.w(TAG, "★ 凭证帧时间戳超窗（ts=$ts now=$nowSec），拒绝")
                return null
            }
            if (peerPubB64.isEmpty() || nonceB64.isEmpty()) {
                Log.w(TAG, "★ 凭证帧字段不完整，拒绝")
                return null
            }
            val iv = b64d(nonceB64)
            val ct = b64d(ctB64)
            if (iv == null || iv.size != 12 || ct == null || ct.isEmpty()) {
                Log.w(TAG, "★ 凭证帧 IV/密文长度非法，拒绝")
                return null
            }
            val replayKey = "cred:$nonceB64"
            if (nonceSeen(replayKey)) {
                Log.w(TAG, "★ 凭证帧重放（IV 已用过），拒绝")
                return null
            }
            val shared = x25519Shared(ch.privRaw, peerPubB64) ?: run {
                Log.w(TAG, "★ ECDH 失败，拒绝")
                return null
            }
            val salt = b64d(helloNonce) ?: ByteArray(0)
            val key = hkdfSha256(shared, salt, HKDF_INFO.toByteArray(Charsets.US_ASCII), 32)
            wipe(shared)
            val aad = ("remote_admin|${cn.ppps.forwarder.relay.RelayCommands.CMD_TAILSCALE_CRED}" +
                    "|$DIRECTION|v=$PAYLOAD_VERSION|ts=$ts|n=$nonceB64|h=$helloNonce")
                .toByteArray(Charsets.US_ASCII)
            val plain = try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, iv),
                )
                c.updateAAD(aad)
                c.doFinal(ct)
            } catch (t: Throwable) {
                // GCM 认证失败 / 密钥不对 —— 一律拒
                Log.w(TAG, "★ 凭证解密失败(${t.javaClass.simpleName})，拒绝")
                return null
            } finally {
                wipe(key)
            }
            val body = try {
                JSONObject(String(plain, Charsets.US_ASCII))
            } catch (t: Throwable) {
                Log.w(TAG, "★ 凭证明文解析失败，拒绝")
                return null
            } finally {
                wipe(plain)
            }
            if (body.optInt("v", 0) != PAYLOAD_VERSION) {
                Log.w(TAG, "★ 内层版本不符，拒绝")
                return null
            }
            val id = body.optString("id", "").trim()
            val secret = body.optString("secret", "").trim()
            if (id.isEmpty() || secret.isEmpty()) {
                Log.w(TAG, "★ 解密后凭证为空，拒绝（绝不用空值覆盖已有好凭证）")
                return null
            }
            id to secret
        } finally {
            wipe(ch.privRaw)
        }
    }

    private data class Quint(
        val a: String, val b: String, val c: String, val d: String,
        val e: Long, val f: Int,
    )

    // ---------------------------------------------------------------- 落盘（Keystore 包裹）

    /**
     * 保存 OAuth 凭证（★ 拒绝空值；Keystore 不可用则拒绝保存，绝不写明文）。
     * @return 是否真的加密落盘成功
     */
    fun saveOAuthCred(ctx: Context, id: String, secret: String): Boolean {
        if (id.isBlank() || secret.isBlank()) {
            Log.w(TAG, "★ 拒绝保存空白凭证")
            return false
        }
        val encId = wrap(id)
        val encSecret = wrap(secret)
        if (encId == null || encSecret == null) {
            Log.e(TAG, "★ Android Keystore 不可用，拒绝落盘凭证（不退回明文存储）")
            return false
        }
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit()
            .putString(SP_KEY_ID, encId)
            .putString(SP_KEY_SECRET, encSecret)
            .apply()
        Log.i(TAG, "★ OAuth 凭证已加密落盘 ${describeForLog(id, secret)}")
        return true
    }

    /** 读取 oauth client_id（无则空串；遇到遗留明文会就地改写为密文） */
    fun loadOAuthClientId(ctx: Context): String =
        unwrap(ctx, SP_KEY_ID, ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).getString(SP_KEY_ID, null))

    /** 读取 oauth client_secret（无则空串；遇到遗留明文会就地改写为密文） */
    fun loadOAuthClientSecret(ctx: Context): String =
        unwrap(ctx, SP_KEY_SECRET, ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).getString(SP_KEY_SECRET, null))

    /** 是否已持有可用 OAuth 凭证 */
    fun hasOAuthCred(ctx: Context): Boolean =
        loadOAuthClientId(ctx).isNotEmpty() && loadOAuthClientSecret(ctx).isNotEmpty()

    /**
     * 本机是否具备"完整的凭证保护能力"（Keystore 可用）。
     * 与 PC 端 transport_available() 语义对应：返回 false 时本侧仍**不会**接受明文凭证。
     */
    fun atRestAvailable(ctx: Context): Boolean = try {
        keystoreKey(ctx) != null
    } catch (t: Throwable) {
        false
    }

    /** 统一脱敏日志片段：只出现 id 前 6 位与两个长度，永不出现 secret */
    fun describeForLog(id: String, secret: String): String =
        "id=${id.take(6)}...(len=${id.length}), secret_len=${secret.length}"

    private fun unwrap(ctx: Context, spKey: String, blob: String?): String {
        if (blob.isNullOrEmpty()) return ""
        if (!blob.startsWith(KS_PREFIX)) {
            // ★ 2026-08-30 补齐遗留明文迁移：老版本可能直接写明文进 SP。
            //   这里立刻用 Keystore 重新包裹并回写同一个键（覆盖即从磁盘抹掉明文），
            //   而不是只打一句"已改写为密文"的谎报日志。
            val plain = blob
            val migrated = runCatching { wrap(plain) }.getOrNull()
            if (migrated.isNullOrEmpty()) {
                Log.e(TAG, "★ 检测到 SP 中非 Keystore 明文凭证，但 Keystore 不可用、无法回写密文：明文暂留磁盘（本次仍按内存值使用）")
            } else {
                ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit()
                    .putString(spKey, migrated).apply()
                Log.i(TAG, "★ 已把 SP 中的遗留明文凭证改写为 Keystore 密文并覆盖删除明文 (key=$spKey)")
            }
            return plain
        }
        return try {
            val raw = b64d(blob.substring(KS_PREFIX.length)) ?: return ""
            if (raw.size <= 12) return ""
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keystoreKeyOrThrow(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (t: Throwable) {
            // 换机/恢复备份/密钥被清除：解不开就是没有，绝不当明文用
            Log.w(TAG, "★ 凭证解不开(${t.javaClass.simpleName})，视为无凭证")
            ""
        }
    }

    private fun wrap(plain: String): String? {
        // ★★★ 2026-08-30 修复"凭证永远落不了盘"：本密钥是 setRandomizedEncryptionRequired(true)
        //   的 Keystore 密钥，**IV 必须由 Keystore 自己生成**；旧代码自己传 12 字节 IV，
        //   实测红米K40 上每次 ENCRYPT init 都抛 InvalidAlgorithmParameterException
        //   → 走到"Keystore 不可用，拒绝落盘"分支 → 全新安装即使收到下发也存不下来。
        val c = try {
            Cipher.getInstance("AES/GCM/NoPadding")
        } catch (t: Throwable) {
            Log.w(TAG, "★ AES/GCM 不可用: ${t.javaClass.simpleName}")
            return null
        }
        val key = try {
            keystoreKeyOrThrow()
        } catch (t: Throwable) {
            Log.w(TAG, "★ Keystore 密钥不可得: ${t.javaClass.simpleName}")
            return null
        }
        val inited = runCatching { c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(0))) }
            .recoverCatching { runCatching { c.init(Cipher.ENCRYPT_MODE, key) }.getOrThrow() }
        if (inited.isFailure) {
            Log.w(TAG, "★ 凭证加密失败: ${inited.exceptionOrNull()?.javaClass?.simpleName}（拒绝落盘，不退回明文）")
            return null
        }
        // Keystore 生成的 IV（GCM 固定 12B），前置拼接供 unwrap() 取回
        val iv = runCatching { c.iv }.getOrNull()
        if (iv == null || iv.size != 12) {
            Log.w(TAG, "★ Keystore 未提供合法 IV（len=${iv?.size}），拒绝落盘")
            return null
        }
        return try {
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            KS_PREFIX + b64u(iv + ct)
        } catch (t: Throwable) {
            Log.w(TAG, "★ 凭证加密失败: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun keystoreKeyOrThrow(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return kg.generateKey()
    }

    /** 供 atRestAvailable() 用：不抛异常 */
    private fun keystoreKey(ctx: Context): SecretKey? = try {
        keystoreKeyOrThrow()
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------------- 原语

    private fun purgeExpired() {
        if (pending.size <= 4) return
        val now = SystemClock.elapsedRealtime()
        pending.entries.filter { it.value.expired(now) || it.value.used }.forEach {
            wipe(it.value.privRaw); pending.remove(it.key)
        }
    }

    private fun nonceSeen(key: String): Boolean {
        if (key.isEmpty()) return true
        val now = SystemClock.elapsedRealtime()
        seenNonces.entries.filter { now - (it.value ?: 0L) > REPLAY_TTL_MS }
            .forEach { seenNonces.remove(it.key) }
        return seenNonces.putIfAbsent(key, now) != null
    }

    private fun randomB64(n: Int): String {
        val b = ByteArray(n).also { random.nextBytes(it) }
        return b64u(b)
    }

    /** X25519 私钥原始 32B -> 公钥原始 32B（BC 纯 Java 实现，不依赖 provider 是否支持 X25519） */
    private fun x25519Public(privRaw: ByteArray): ByteArray? = try {
        ensureBc()
        org.bouncycastle.crypto.params.X25519PrivateKeyParameters(privRaw, 0)
            .generatePublicKey().encoded
    } catch (t: Throwable) {
        Log.w(TAG, "★ 公钥推导失败: ${t.javaClass.simpleName}")
        null
    }

    /** ECDH(我方私钥, 对方公钥) -> 32B 共享秘密 */
    private fun x25519Shared(privRaw: ByteArray, peerPubB64: String): ByteArray? {
        return try {
        ensureBc()
        val peer = b64d(peerPubB64) ?: return null
        if (peer.size != 32) return null
        val ag = org.bouncycastle.crypto.agreement.X25519Agreement()
        ag.init(org.bouncycastle.crypto.params.X25519PrivateKeyParameters(privRaw, 0))
        val out = ByteArray(ag.agreementSize)
        ag.calculateAgreement(
            org.bouncycastle.crypto.params.X25519PublicKeyParameters(peer, 0), out, 0
        )
        out
    } catch (t: Throwable) {
        Log.w(TAG, "★ X25519 异常: ${t.javaClass.simpleName}")
        null
    }
    }

    private var bcChecked = false
    private fun ensureBc() {
        if (bcChecked) return
        synchronized(this) {
            if (!bcChecked) {
                try {
                    Class.forName("org.bouncycastle.jcajce.provider.BouncyCastleProvider")
                } catch (_: Throwable) {
                }
                bcChecked = true
            }
        }
    }

    /** RFC5869 HKDF-SHA256（L<=32 时单块即可，与 cryptography.HKDF 一致） */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(len)
        var prev = ByteArray(0)
        var off = 0
        var counter = 1
        while (off < len) {
            val block = hmac(prk, prev + info + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, len - off)
            System.arraycopy(block, 0, out, off, take)
            off += take
            prev = block
            counter++
        }
        wipe(prk)
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    /** bytes -> base64(urlsafe, 无 padding)，与 PC 端 b64_encode_loose 一致 */
    private fun b64u(raw: ByteArray): String =
        Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** 宽松 base64 解码：先按 urlsafe，失败按标准（对应 PC 端 _b64_decode_loose） */
    private fun b64d(text: String): ByteArray? = try {
        Base64.decode(text.trim(), Base64.URL_SAFE or Base64.NO_WRAP)
    } catch (_: Throwable) {
        try {
            Base64.decode(text.trim(), Base64.DEFAULT)
        } catch (_: Throwable) {
            null
        }
    }

    private fun wipe(b: ByteArray?) {
        try {
            b?.fill(0)
        } catch (_: Throwable) {
        }
    }
}
