package cn.ppps.forwarder.tailscale

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * ★★★ 2026-10-19【手机被控端 NAS 自助补凭证】—— 对齐 PC 被控端
 * modules/upgrade_fetch.ensure_ts_cred 的同款能力（用户 2026-10-19 拍板）。
 *
 * 背景与边界（先讲清楚为什么手机端以前只有"控制端下发"一条路）：
 *   · 手机被控端拿 tailnet 凭证原本只有三条路：构建期注入 / 控制端 CMD_TAILSCALE_CRED
 *     加密下发 / 本机 SP 已存；
 *   · "无中继 + 新装机"时控制端也还没连上 ⇒ 三条全断 ⇒ VPN 永远起不来。
 *   · PC 被控端早在 2026-10-14 就有"检测升级时缺凭证 → 从 NAS 仓库自助补全"的自救；
 *     本文件把同款能力补到手机被控端。
 *
 * 数据源（与 PC 端 nas.json 完全同源，注意**顺序**）：
 *   ① https://nat5747.yyboxdns.com:11699 —— 拾光穿透（新机未进 tailnet 前**唯一可达**的源）
 *   ② http://100.98.160.68:8000 —— tailnet 直连（已入 tailnet 时最快、不耗穿透流量）
 *   token 与 NAS 侧 /etc/n3upgrade.env 的 UPGRADE_TOKEN 一致（?token= 查询参数，403 校验）。
 *
 * 安全口径（与全项目一致）：
 *   · OAuth 凭证经 [TailscaleCredGuard] Keystore 加密落盘，绝不明文；
 *   · 日志只打前缀与长度，绝不打印 key/secret 明文；
 *   · 穿透源走 **证书固定（pinned CA）**：PEM 内嵌于 [NAS_CA_PEM]（与 nas.json 的 tls_ca
 *     同源，公开信息），HTTPS 主机名仍按证书 SAN 校验，不整体跳过验证。
 *
 * 失败纪律：任何异常只打日志 + 5 分钟退避，绝不抛、绝不影响被控端其它流程；
 * 拿到任一部分凭证即视为成功并清退避。
 */
object TailscaleCredBootstrap {

    private const val TAG = "TsCredBoot"

    /** 与 PC 端 nas.json 同源：穿透优先（新机 bootstrap 时 tailnet 地址还不可达） */
    private const val NAS_URLS = "https://nat5747.yyboxdns.com:11699,http://100.98.160.68:8000"

    /** NAS 仓库令牌（= /etc/n3upgrade.env 的 UPGRADE_TOKEN，与 nas.json 的 token 键一致） */
    private const val NAS_TOKEN = "357c08e6a1681716ea4feb62d9aaa5be"

    private const val CRED_FILE = "ts_cred.json"
    private const val SP_NAME = "tailscale_settings"
    private const val SP_KEY_BACKOFF_UNTIL = "nas_cred_boot_backoff_until"

    /** 补全失败后的退避：PC 端是"升级检测节拍"，手机端由 ensureStarted 心跳驱动 ⇒ 5 分钟足够 */
    private const val BACKOFF_MS = 5 * 60_000L
    private const val HTTP_TIMEOUT_MS = 8000

    /** 防止 ensureStarted 心跳并发拉起多个补全线程 */
    @Volatile
    private var inFlight = false

    /** NAS 自签证书的固定 CA（与 PC 端 nas.json 的 tls_ca 同源；仅用于 pinning，公开信息） */
    private const val NAS_CA_PEM = """-----BEGIN CERTIFICATE-----
MIIDVzCCAj+gAwIBAgIULHzxQucqUeXSG94DID2dgp247PIwDQYJKoZIhvcNAQEL
BQAwFTETMBEGA1UEAwwKbjMtdXBncmFkZTAeFw0yNjEwMTQxNjQyMTdaFw0zNjEw
MTExNjQyMTdaMBUxEzARBgNVBAMMCm4zLXVwZ3JhZGUwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQC7Z7s7WXh1Ls9Xc/XiDCK872a/RxsY3iex7Mj+ZC7A
lGWD5HkPmtL7rE2XiL+gJV10JR7JAESJIhwZsj+Z2lCTFXIR8c6gKiJbb0DdUztN
ywx5ZnuZd6g2XTw+RDH2Z1Phz7nbgGPa3u7QUQvtVp3RnX5b/6SfRmdxM5JiFtB4
TtYqCeJPOH+3nEAKq1U7que6KKWXV3q8GCNUrbWixz1nME1ePZrFdZmRJH/UNT9c
yZwjakGynvmMhq7fmv7tip+MofLMJoFGbv/cSPwcH8sN2YiXjfTnHnPEWttxyV7A
PvjOdeRCuw2COR6cMGy95WP0oQVpQKuvvtCibVRvw+n7AgMBAAGjgZ4wgZswHQYD
VR0OBBYEFM9bt/p0Brcpxn6kqhHFbti8RB67MB8GA1UdIwQYMBaAFM9bt/p0Brcp
xn6kqhHFbti8RB67MB8GA1UdEwEB/wQFMAMBAf8wSAYDVR0RBEEwP4IUbmF0NTc0
Ny55eWJveGRucy5jb22CCm4zLXVwZ3JhZGWCCW5hcy5sb2NhbIcEZGKgRIcEwKgK
D4cEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEAkXPMhRfhKlVK4GHtl970X/BV9Gk0
r2bEOEwucR42cpP9QjEpTwFuTbxeWrZt/TbkmZ/MmYA/xkjDaBnANUXvQ2SVwIst
YSaRd7bRJ5tNM7t8nA6pvCmPKAqTueTzoUD1+Tb2pg+uLMeRVnuVpNAkFP5ZuJC7
1URlm+e4w6w7l5ZQg9HBph6ApNfZHTLlLE5tGnZC5JVhweUa04dKydqOE/MqDkKE
9m62QkRlWuveXZwUQMFClekCWbW9lwMi+TE2sU72LxVLxn4unGGk1VpOCq7Er1Z7
KgeoPUOUnqX3RfnOHuS9lOXJkgqQTKzxm+SMuY9O2BjPoApo9h6acul+yA==
-----END CERTIFICATE-----"""

    /**
     * 是否还缺直连凭证（既无 OAuth 客户端凭证、也无 authkey）——与 PC 端
     * needs_bootstrap_cred 同口径；返回 true 才值得尝试补全。
     */
    fun needsBootstrap(ctx: Context): Boolean =
        !TailscaleCredGuard.hasOAuthCred(ctx) && TailscaleManager.getAuthkey(ctx).isEmpty()

    /** 异步补全（带 5 分钟失败退避 + 单飞保护）。失败/退避中只打日志，绝不抛。 */
    fun bootstrapAsync(ctx: Context) {
        if (inFlight) return
        val sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val until = sp.getLong(SP_KEY_BACKOFF_UNTIL, 0L)
        if (System.currentTimeMillis() < until) {
            Log.i(TAG, "★ NAS 补凭证退避中（剩余 ${(until - System.currentTimeMillis()) / 1000}s），跳过")
            return
        }
        inFlight = true
        Thread({
            try {
                bootstrap(ctx)
            } catch (t: Throwable) {
                Log.w(TAG, "★ NAS 补凭证线程异常(忽略): ${t.javaClass.simpleName}")
            } finally {
                inFlight = false
            }
        }, "TsCredBoot").apply { isDaemon = true }.start()
    }

    /**
     * 同步补全。按 [NAS_URLS] 顺序尝试；拿到 OAuth 和/或 authkey 即成功。
     * @return true = 本机已具备凭证（已触发签发/重登）
     */
    fun bootstrap(ctx: Context): Boolean {
        val appCtx = ctx.applicationContext
        val sp = appCtx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        for (base in NAS_URLS.split(',')) {
            val b = base.trim().trimEnd('/')
            if (b.isEmpty()) continue
            try {
                val body = httpGet("$b/$CRED_FILE?token=" + NAS_TOKEN)
                if (body == null || body.isEmpty()) continue
                val obj = JSONObject(String(body, Charsets.UTF_8))
                val oauth = obj.optJSONObject("oauth")
                val cid = oauth?.optString("client_id", "")?.trim() ?: ""
                val secret = oauth?.optString("client_secret", "")?.trim() ?: ""
                val authkey = obj.optString("authkey", "").trim()
                if (cid.isEmpty() && authkey.isEmpty()) {
                    Log.w(TAG, "★ NAS 补凭证源 $b 的 bundle 既无 oauth 也无 authkey，忽略")
                    continue
                }
                var got = false
                if (cid.isNotEmpty() && secret.isNotEmpty()) {
                    got = TailscaleCredGuard.saveOAuthCred(appCtx, cid, secret)
                    Log.i(TAG, "★ NAS 补凭证命中: $b → OAuth 已 Keystore 加密落盘(ok=$got, id前缀=${cid.take(8)}…)")
                }
                var authkeySaved = false
                if (authkey.isNotEmpty()) {
                    TailscaleManager.setAuthkey(appCtx, authkey)
                    authkeySaved = true
                    got = true
                    Log.i(TAG, "★ NAS 补凭证命中: $b → authkey 已保存(长度=${authkey.length})")
                }
                if (got) {
                    sp.edit().remove(SP_KEY_BACKOFF_UNTIL).apply()
                    if (authkeySaved) {
                        // authkey 已就位 ⇒ 直接用新 key 重登（startLogin 幂等，有在途保护）
                        TailscaleManager.startLogin(appCtx)
                    } else {
                        // 只有 OAuth 凭证 ⇒ 走既有"凭证就绪即签发+重登"链路
                        TailscaleManager.onCredentialStored(appCtx)
                    }
                    Log.i(TAG, "★ NAS 补凭证完成，登录链路已触发")
                    return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "★ NAS 补凭证源 $b 失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        sp.edit().putLong(SP_KEY_BACKOFF_UNTIL, System.currentTimeMillis() + BACKOFF_MS).apply()
        Log.w(TAG, "★ NAS 补凭证全部源未命中，进入 ${BACKOFF_MS / 60000} 分钟退避")
        return false
    }

    /** http/https 通用 GET；200 才返回字节，其余/异常返回 null（异常只由调用方打日志） */
    private fun httpGet(urlStr: String): ByteArray? {
        val url = URL(urlStr)
        val conn = if (url.protocol == "https") {
            (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = pinnedSslContext().socketFactory
            }
        } else {
            url.openConnection() as HttpURLConnection
        }
        (conn as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "smsf-tscred/1")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "★ NAS 补凭证 HTTP ${conn.responseCode}: ${conn.responseMessage?.take(60)}")
                return null
            }
            val ins: InputStream = conn.inputStream
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                bos.write(buf, 0, n)
                if (bos.size() > 256 * 1024) break   // 凭证束只有几百字节，防呆
            }
            return bos.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /** 证书固定：只信任内嵌的 NAS 自签 CA（与 nas.json tls_ca 同源），其余 CA 一律不认 */
    private fun pinnedSslContext(): SSLContext {
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(NAS_CA_PEM.byteInputStream())
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        ks.setCertificateEntry("nas-ca", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }
    }
}
