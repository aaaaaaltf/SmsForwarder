package cn.ppps.forwarder.relay

import cn.ppps.forwarder.utils.Log
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ★ 2026-11-06【直连通道首包鉴权】
 *
 * 背景：56786（命令）、56783（屏幕 JPEG）、56791（麦克风 PCM）此前在 0.0.0.0 上裸监听，
 * 同 Wi-Fi / 同 tailnet 的任意主机连上即可下发命令或拉走屏幕/音频（tsnet 暴露后风险扩大到整个尾网）。
 *
 * 方案：
 *  - 命令通道：被控端 accept 后先发 `dautc000000`(nonce)，控制端回 `dautr000000`(HMAC)；
 *    5 秒未完成则断开。认证前的其他命令先缓冲，认证成功后按序补处理。
 *  - 媒体通道：控制端连上后首行发 `DAUTH|<port>|<ts>|<hmac>`；被控端校验 HMAC 与 ±300s 时间窗。
 *  - 密钥复用现有注册共享令牌（两端真源常量），不新增硬编码秘密。
 *
 * 兼容性：旧控制端/旧被控端不会参与握手，必须两端同时升级；中继模式（CHANNEL_RELAY）不启用。
 */
object DirectAuth {

    private const val TAG = "DirectAuth"

    /** 媒体首包时间窗（秒）：容忍手机时钟偏差，同时限制重放窗口 */
    private const val MEDIA_WINDOW_SEC = 300L

    /** 命令通道认证超时（毫秒） */
    const val CMD_AUTH_TIMEOUT_MS = 5000L

    // ★★★ 命令字必须严格 12 字符（RelayCommands.CMD_PREFIX_LEN=12）：
    //   曾写成 11 字符（dautc000000/dautr000000），parse() 会把 nonce 首字符并入命令字，
    //   两端永远匹配不上 → 控制端不应答挑战 → 每 5 秒被断开（麦克风/屏幕命令全部失效）。
    const val CMD_CHALLENGE = "dautc0000000"
    const val CMD_RESPONSE = "dautr0000000"

    private val KEY = RelayProtocol.LABEL_PHONE_REG_TOKEN.toByteArray(Charsets.UTF_8)

    fun hmacHex(message: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(KEY, "HmacSHA256"))
            val out = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(out.size * 2)
            for (b in out) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            sb.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "HMAC 计算失败: ${t.message}")
            ""
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** 命令通道应答：HMAC("cmd|"+nonce) */
    fun responseFor(challenge: String): String = hmacHex("cmd|$challenge")

    fun verifyResponse(challenge: String, response: String): Boolean {
        if (challenge.isEmpty() || response.isEmpty()) return false
        val expect = responseFor(challenge)
        return expect.isNotEmpty() && expect.equals(response, ignoreCase = true)
    }

    /** 媒体通道：生成首行（不含换行） */
    fun mediaAuthLine(port: Int): String {
        val ts = System.currentTimeMillis() / 1000
        val msg = "media|$port|$ts"
        return "DAUTH|$port|$ts|${hmacHex(msg)}"
    }

    /** 媒体通道：校验首行 */
    fun verifyMediaLine(port: Int, line: String): Boolean {
        val parts = line.trim().split("|")
        if (parts.size != 4 || parts[0] != "DAUTH") return false
        val gotPort = parts[1].toIntOrNull() ?: return false
        if (gotPort != port) return false
        val ts = parts[2].toLongOrNull() ?: return false
        val now = System.currentTimeMillis() / 1000
        if (kotlin.math.abs(now - ts) > MEDIA_WINDOW_SEC) {
            Log.w(TAG, "媒体鉴权时间窗超限: ts=$ts now=$now port=$port")
            return false
        }
        val expect = hmacHex("media|$port|$ts")
        return expect.isNotEmpty() && expect.equals(parts[3], ignoreCase = true)
    }

    /** 从流里读取一行（最多 maxLen 字节，超时由 Socket.soTimeout 控制） */
    fun readAuthLine(input: java.io.InputStream, maxLen: Int = 256): String? {
        val buf = java.io.ByteArrayOutputStream(maxLen)
        while (buf.size() < maxLen) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8")
            if (b != '\r'.code) buf.write(b)
        }
        return null
    }
}
