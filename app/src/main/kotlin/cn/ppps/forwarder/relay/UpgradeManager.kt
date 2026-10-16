package cn.ppps.forwarder.relay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import cn.ppps.forwarder.utils.AppUtils
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest

/**
 * 手机被控端自升级（2026-10-16 用户需求）。
 *
 * 需求原文与口径：
 *  - 「检查更新」按钮触发；**不做自动检查**，但检测到新版本后**自动下载**到手机；
 *  - 点按钮时**先看手机上是否已存在**"自动下载的、高于当前版本的 APK" ⇒ 有则弹确认 → 安装；
 *  - 不存在则取清单比对：有新版 → 自动下载 → 下载完弹确认 → 调起系统安装界面；
 *  - 来源：**中继模式 → 中继云 56790**（UPVERSION/UPGET，与 PC 被控端同一套口径）；
 *    **直连模式 → NAS**（先局域网 192.168.10.15:31000，再 tailnet 100.98.160.68:8000）。
 *
 * ★ 安装只能是"调起系统安装界面让用户点安装"（普通应用无静默安装权限，用户已确认接受）。
 * ★ 中继读响应必须**逐字节读到 '\n'**：UPGET 头行后面紧跟二进制，用 BufferedReader
 *   会预读缓冲把文件头几个字节吞掉（PC 侧同款坑）。
 */
object UpgradeManager {

    // ---- 源 ----
    const val RELAY_HOST = "106.12.48.88"
    const val RELAY_PORT = 56790
    val NAS_BASES = listOf(
        "http://192.168.10.15:31000",       // ① NAS 局域网（要求手机与 NAS 同网段）
        "http://100.98.160.68:8000"         // ② NAS tailnet（要求手机装了 Tailscale）
    )
    /** NAS 仓库令牌（与仓库 nas.json / 云端 UPGRADE_TOKEN 同源）。 */
    const val NAS_TOKEN = "357c08e6a1681716ea4feb62d9aaa5be"
    /** 清单里的角色键（发布侧 tools/upgrade_publish.py 的 ROLE_PHONE）。 */
    const val ROLE = "phone"

    private const val CHUNK = 1024 * 1024              // UPGET 分片大小（与 PC 侧一致）
    private const val RELAY_TIMEOUT_MS = 15000
    private const val HTTP_TIMEOUT_MS = 15000

    data class Entry(
        val version: String,
        val file: String,
        val size: Long,
        val sha256: String,
        val source: String
    )

    /** 升级过程中的阶段提示（回主线程用）。 */
    data class Stage(val text: String, val pct: Int = -1)

    // ------------------------------------------------------------------
    // 本地
    // ------------------------------------------------------------------
    fun apkDir(ctx: Context): File {
        val d = File(ctx.filesDir, "apk")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun localVersionName(): String = try {
        AppUtils.getAppVersionName()
    } catch (e: Exception) {
        "0"
    }

    fun localVersionCode(): Int = try {
        AppUtils.getAppVersionCode()
    } catch (e: Exception) {
        0
    }

    /**
     * 版本比较：分段数值比较（versionName 形如 `1.0.17.20261016`，最后一段是构建日期）。
     * 非数字段按 0 处理；段数不同时短者补 0（`1.0.17` < `1.0.17.1`）。
     */
    fun isNewer(remote: String?, local: String?): Boolean {
        val r = (remote ?: "").trim()
        val l = (local ?: "").trim()
        if (r.isEmpty()) return false
        if (l.isEmpty()) return true
        val ra = r.split('.', '-')
        val la = l.split('.', '-')
        val n = Math.max(ra.size, la.size)
        for (i in 0 until n) {
            val rv = ra.getOrNull(i)?.toIntOrNull() ?: 0
            val lv = la.getOrNull(i)?.toIntOrNull() ?: 0
            if (rv != lv) return rv > lv
        }
        return false
    }

    /** 已下载目录里**版本高于当前**的 APK（用户要求：先查它，有就直接装）。 */
    fun findDownloadedNewer(ctx: Context): File? {
        val dir = apkDir(ctx)
        val local = localVersionName()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: return null
        var best: File? = null
        var bestVer = ""
        for (f in files) {
            // ★★ 2026-10-16【真机踩坑】必须跳过**半截/空包**：服务端拒收时（如白名单不认 .apk）
            //   会留下 0 字节同名文件，而这里只按文件名解析版本 ⇒ 会被当成"已下载的新版本"
            //   直接返回 ⇒ 用户点安装必然失败，且**再也不会去联网重下**（短路）。
            //   安装包至少几 MB，低于 1MB 一律视为坏包并删掉。
            if (f.length() < 1024L * 1024L) {
                try {
                    f.delete()
                } catch (e: Exception) {
                }
                continue
            }
            val ver = versionFromFileName(f.name)
            if (ver.isEmpty()) continue
            if (!isNewer(ver, local)) continue
            if (best == null || isNewer(ver, bestVer)) {
                best = f
                bestVer = ver
            }
        }
        return best
    }

    /** 从文件名猜版本：`phone-1.0.17.20261016.apk` / `SmsF_1.0.17.20261016_100_arm64-v8a_debug.apk`。 */
    fun versionFromFileName(name: String): String {
        val base = name.removeSuffix(".apk")
        val m = Regex("(\\d+(?:\\.\\d+)+)").find(base) ?: return ""
        return m.groupValues[1]
    }

    // ------------------------------------------------------------------
    // 取清单
    // ------------------------------------------------------------------
    /** 取清单（按模式选源）→ (Entry?, 说明)。relayMode=true 走中继云，否则走 NAS 双地址。 */
    fun fetchManifest(relayMode: Boolean): Pair<Entry?, String> {
        return if (relayMode) fetchManifestRelay() else fetchManifestNas()
    }

    private fun parseManifest(json: String, source: String): Entry? {
        return try {
            val root = org.json.JSONObject(json)
            val latest = root.optJSONObject("latest") ?: return null
            val e = latest.optJSONObject(ROLE) ?: return null
            val ver = e.optString("version", "")
            val file = e.optString("file", "")
            if (ver.isEmpty() || file.isEmpty()) return null
            Entry(ver, file, e.optLong("size", 0L), e.optString("sha256", ""), source)
        } catch (ex: Exception) {
            null
        }
    }

    private fun fetchManifestRelay(): Pair<Entry?, String> {
        var sock: Socket? = null
        try {
            sock = Socket()
            sock.connect(InetSocketAddress(RELAY_HOST, RELAY_PORT), RELAY_TIMEOUT_MS)
            sock.soTimeout = RELAY_TIMEOUT_MS
            val out = BufferedOutputStream(sock.getOutputStream())
            out.write("UPVERSION\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val line = readLineUtf8(sock.getInputStream()) ?: return null to "中继无响应"
            if (!line.startsWith("UPVERSION|")) return null to ("中继应答异常: " + line.take(80))
            val body = line.substring("UPVERSION|".length)
            if (body.startsWith("ERROR")) return null to ("中继错误: " + body)
            val e = parseManifest(body, "中继云 " + RELAY_HOST)
            return e to (if (e == null) "清单里没有 $ROLE 产物（云端还没发布手机被控端？）" else body.length.toString() + " 字节")
        } catch (ex: Exception) {
            return null to ("连中继失败: " + (ex.message ?: ex.javaClass.simpleName))
        } finally {
            try {
                sock?.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun fetchManifestNas(): Pair<Entry?, String> {
        val errs = StringBuilder()
        for (base in NAS_BASES) {
            try {
                val url = URL("$base/latest.json" + (if (NAS_TOKEN.isNotEmpty()) "?token=$NAS_TOKEN" else ""))
                val conn = (url.openConnection() as HttpURLConnection)
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                if (NAS_TOKEN.isNotEmpty()) conn.setRequestProperty("X-Upgrade-Token", NAS_TOKEN)
                val code = conn.responseCode
                if (code != 200) {
                    errs.append("$base → HTTP $code; ")
                    conn.disconnect()
                    continue
                }
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                val e = parseManifest(body, "NAS $base")
                if (e != null) return e to base
                errs.append("$base → 清单无 $ROLE 条目; ")
            } catch (ex: Exception) {
                errs.append("$base → " + (ex.message ?: "失败") + "; ")
            }
        }
        return null to ("NAS 均不可用: " + errs.toString().take(200))
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------
    /** 下载 entry 到 filesDir/apk/（中继走 UPGET 分片，NAS 走 HTTP），校验 sha256。 */
    fun download(entry: Entry, onStage: (Stage) -> Unit): Pair<File?, String> {
        val fromRelay = entry.source.startsWith("中继云")
        return if (fromRelay) downloadViaRelay(entry, onStage) else downloadViaHttp(entry, onStage)
    }

    private fun downloadViaHttp(entry: Entry, onStage: (Stage) -> Unit): Pair<File?, String> {
        val ctx = cn.ppps.forwarder.App.context ?: return null to "无上下文"
        val dst = File(apkDir(ctx), entry.file)
        val base = entry.source.removePrefix("NAS ").trim()
        return try {
            val conn = (URL("$base/${entry.file}" + (if (NAS_TOKEN.isNotEmpty()) "?token=$NAS_TOKEN" else "")).openConnection() as HttpURLConnection)
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            if (NAS_TOKEN.isNotEmpty()) conn.setRequestProperty("X-Upgrade-Token", NAS_TOKEN)
            if (conn.responseCode != 200) return null to ("HTTP " + conn.responseCode)
            // ★ contentLengthLong 需 API 24，本项目要兼容 API 19 ⇒ 用 contentLength
            val total = conn.contentLength.toLong().takeIf { it > 0 } ?: entry.size
            conn.inputStream.use { input ->
                FileOutputStream(dst).use { fos ->
                    copyWithProgress(input, fos, total, onStage)
                }
            }
            conn.disconnect()
            verify(dst, entry)
        } catch (ex: Exception) {
            try {
                dst.delete()        // 同上：失败不留半截包
            } catch (e: Exception) {
            }
            null to ("下载失败: " + (ex.message ?: ex.javaClass.simpleName))
        }
    }

    private fun downloadViaRelay(entry: Entry, onStage: (Stage) -> Unit): Pair<File?, String> {
        val ctx = cn.ppps.forwarder.App.context ?: return null to "无上下文"
        val dst = File(apkDir(ctx), entry.file)
        var off = 0L
        var total = entry.size
        try {
            FileOutputStream(dst).use { fos ->
                while (true) {
                    // ★★ 2026-10-16【真机踩坑】**每个分片开一个新连接**。
                    //   服务端 serve_upget 是"一次连接服务一个分片"的语义（PC 侧
                    //   upgrade_fetch._upget_once 也是每片新连接）；原来复用一个连接连续
                    //   请求下一片 ⇒ 第二片永远等不到应答，表现为**文件停在 1MB 不动**
                    //   （第一片 1MB 写完后一直阻塞在 readLine）。
                    val sock = Socket()
                    try {
                        sock.connect(InetSocketAddress(RELAY_HOST, RELAY_PORT), RELAY_TIMEOUT_MS)
                        sock.soTimeout = 30000
                        val input = BufferedInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()
                        out.write("UPGET|${entry.file}|$off|$CHUNK\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                        val head = readLineUtf8(input) ?: break
                        val ps = head.split('|')
                        // ★ ERROR 必须先判：`UPGET|ERROR|bad_name` 只有 3 段，先判段数会把
                        //   真实原因吞成"应答异常"（真机排查时被这条误导过）。
                        if (ps.size > 1 && ps[1].startsWith("ERROR")) {
                            return null to ("UPGET 错误: " + ps[1])
                        }
                        if (ps.size < 5 || ps[0] != "UPGET") {
                            return null to ("UPGET 应答异常: " + head.take(80))
                        }
                        val real = ps[3].toIntOrNull() ?: 0
                        ps[4].toLongOrNull()?.let { if (it > 0) total = it }
                        if (real <= 0) break
                        var got = 0
                        val buf = ByteArray(64 * 1024)
                        while (got < real) {
                            val n = input.read(buf, 0, Math.min(real - got, buf.size))
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            got += n
                            off += n
                        }
                        if (total > 0) {
                            val pct = ((off * 100) / Math.max(1L, total)).toInt()
                            onStage(Stage("正在下载 $pct%（中继云）", pct))
                        }
                        if (real < CHUNK) break      // 最后一片
                    } finally {
                        try {
                            sock.close()
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            return verify(dst, entry)
        } catch (ex: Exception) {
            try {
                dst.delete()        // ★ 失败不留半截包（半截包会被 findDownloadedNewer 认成"已下载"）
            } catch (e: Exception) {
            }
            return null to ("中继下载失败: " + (ex.message ?: ex.javaClass.simpleName))
        }
    }
    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long,
                                 onStage: (Stage) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            done += n
            if (total > 0) {
                val pct = ((done * 100) / Math.max(1L, total)).toInt()
                onStage(Stage("正在下载 $pct%（NAS）", pct))
            }
        }
    }

    private fun verify(f: File, entry: Entry): Pair<File?, String> {
        if (!f.exists() || f.length() == 0L) return null to "下载文件为空"
        if (entry.size > 0 && f.length() != entry.size) {
            return null to ("大小不符(" + f.length() + "/" + entry.size + ")，已丢弃")
        }
        if (entry.sha256.isNotEmpty()) {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(entry.sha256, ignoreCase = true)) {
                f.delete()
                return null to "sha256 校验失败，已丢弃"
            }
        }
        return f to ("校验通过: " + f.name)
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------
    /**
     * 调起系统安装界面（普通应用无静默安装权限）。
     * Android 8+ 未授予"安装未知应用"时先跳设置页，返回提示让用户开完再点一次。
     */
    fun install(ctx: Context, apk: File): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!ctx.packageManager.canRequestPackageInstalls()) {
                    val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                   Uri.parse("package:" + ctx.packageName))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    return "已跳到「安装未知应用」设置页：请允许本应用安装，然后**再点一次检查更新**完成安装。"
                }
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
            val it = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(it)
            return "已调起系统安装界面，请点「安装」完成升级。"
        } catch (ex: Exception) {
            return "调起安装失败: " + (ex.message ?: ex.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------
    // 后台自动检测（不安装）
    // ------------------------------------------------------------------
    /** 最近一次自动检测的结果与时间（供界面显示；进程内有效）。 */
    @Volatile
    var lastAutoResult: String = ""
        private set
    @Volatile
    var lastAutoTime: Long = 0L
        private set

    /**
     * 是否"中继模式"（决定升级来源：中继云 / NAS）。
     *
     * ★★ 2026-10-16 真机修正：原实现用**反射**调 `TailscaleManager.isRelayConnected()`，
     *    而它是 Kotlin **object（单例）的实例方法**，`invoke(null)` 抛异常被 catch 吞掉
     *    ⇒ 永远 false（现象：界面显示"中继已连接"却总走 NAS）。
     *    现已确认真实路径并改为**直接调用**（编译期校验）。
     */
    fun detectRelayMode(): Boolean = try {
        cn.ppps.forwarder.tailscale.TailscaleManager.isRelayConnected()
    } catch (e: Exception) {
        false
    }

    /**
     * 当前是否通过 **WiFi** 联网（用户要求：只在 WiFi 下自动下载）。
     *
     * ★ minSdk=26 ⇒ 直接用 NetworkCapabilities.TRANSPORT_WIFI，无需旧 NetworkInfo 兼容分支。
     * ★ 取不到网络信息时**按"非 WiFi"处理**（宁可不下载，也不在用户不知情时走移动数据）。
     */
    fun isOnWifi(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 后台**自动检测 + 自动下载**（**不安装**）。
     *
     * 用户口径：自动检测中继云/NAS 上的版本，发现高于本机就自动把安装包下到手机；
     * 「安装」这一步必须由用户点「检查更新」再确认 —— 所以这里只做"查到 + 下好"。
     * 已下好之后：用户点「检查更新」会先命中本地包（findDownloadedNewer）⇒ 直接弹确认。
     * ★ 走的是与手动同一套 checkUpdate（含首选源失败自动回退、sha256 校验），不重复实现。
     */
    fun autoCheckAndDownload(ctx: Context): String {
        val msg = try {
            val (_, text) = checkUpdate(ctx, detectRelayMode()) { }
            text
        } catch (e: Exception) {
            "自动检测异常: " + (e.message ?: e.javaClass.simpleName)
        }
        lastAutoResult = msg
        lastAutoTime = System.currentTimeMillis()
        android.util.Log.i("AutoUpgrade", "自动检测: $msg")
        return msg
    }

    // ------------------------------------------------------------------
    // 主流程（按钮调用；后台线程执行，回调在主线程由调用方处理）
    // ------------------------------------------------------------------
    /**
     * 检查更新（用户口径）：
     *  ① 本地已有更高版本 APK ⇒ 直接返回它（由 UI 弹确认后安装）；
     *  ② 否则取清单比对 ⇒ 有新版就**自动下载** ⇒ 返回下载好的文件；
     *  ③ 无新版 ⇒ 返回 (null, "已是最新")。
     */
    fun checkUpdate(ctx: Context, relayMode: Boolean, onStage: (Stage) -> Unit): Pair<File?, String> {
        val local = localVersionName()
        onStage(Stage("当前版本 $local，正在检查是否有已下载的新版本…", -1))
        val cached = findDownloadedNewer(ctx)
        if (cached != null) {
            return cached to ("发现已下载的新版本 " + versionFromFileName(cached.name))
        }
        onStage(Stage("正在获取清单（" + (if (relayMode) "中继云" else "NAS") + "）…", -1))
        var (entry, why) = fetchManifest(relayMode)
        if (entry == null) {
            // ★ 首选源不可用就自动回退另一源：模式判定（反射）万一取不到，升级也不至于不可用；
            //   真机换网/中继服务重启期间用户点一下也能升成功。
            onStage(Stage("首选源不可用（$why），尝试另一源…", -1))
            val alt = fetchManifest(!relayMode)
            if (alt.first != null) {
                entry = alt.first
                why = alt.second
            }
        }
        if (entry == null) return null to ("取清单失败：$why")
        if (!isNewer(entry.version, local)) {
            // ★ 把**来源**写进结果：用户要验收"中继模式走中继云、直连模式走 NAS"，
            //   界面上能直接看到这次是从哪取的清单（不然只能靠耗时猜）。
            return null to ("已是最新（云端 ${entry.version}，本机 $local；来源 ${entry.source}）")
        }
        onStage(Stage("发现新版本 ${entry.version}（来源 ${entry.source}），开始自动下载…", 0))
        val (f, msg) = download(entry, onStage)
        if (f == null) return null to ("下载失败：$msg　（云端 ${entry.version}）")
        return f to ("已下载新版本 ${entry.version}：$msg")
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------
    /** 逐字节读到 '\n'（不能用 BufferedReader：会预读吞掉紧随其后的二进制）。 */
    private fun readLineUtf8(input: InputStream): String? {
        val sb = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.size() == 0) null else sb.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 8192) break
        }
        return sb.toString("UTF-8")
    }
}
