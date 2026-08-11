package livekit.org.webrtc

import androidx.annotation.Keep

/**
 * ★ 2026-08-11 修复：webrtc-sdk:android:114.5735.08(.1) 包的 .so 文件（libjingle_peerconnection_so.so）
 *   在 JNI_OnLoad 中尝试加载 "livekit.org.webrtc.WebRtcClassLoader" 类，但 aar 中的 classes.jar
 *   实际只包含 "org.webrtc.WebRtcClassLoader"（缺少 livekit. 前缀）。
 *
 *   这导致：System.loadLibrary("jingle_peerconnection_so") → JNI_OnLoad →
 *     FindClass("livekit/org/webrtc/WebRtcClassLoader") → ClassNotFoundException →
 *     JNI NewGlobalRef called with pending exception → SIGABRT → 进程崩溃。
 *
 *   ★ 重要注意事项：
 *   1. 必须使用 public class + public static 方法：R8 代码压缩默认会重命名/删除 package-private 类和方法，
 *      导致 JNI FindClass/GetStaticMethodID 找不到（抛出 NoSuchMethodException / ClassNotFoundException）。
 *   2. @Keep 注解：显式告知 R8 保留此类名和方法名，不进行混淆、内联或删除。
 *   3. 方法签名必须严格匹配 JNI 期望：名称 getClassLoader，参数为空，返回 Object 类型。
 *   4. 与 android_controller/app/src/main/java/livekit/org/webrtc/WebRtcClassLoader.java 对称
 *      （控制端也使用了 public 修饰符以防止被 R8 错误优化）。
 *
 *   ★ 2026-08-11 Kotlin版本：避免javac依赖，保持与Java版本完全等价的字节码结构
 */
@Keep
class WebRtcClassLoader {
    companion object {
        @Keep
        @JvmStatic
        fun getClassLoader(): Any {
            // 复用官方 org.webrtc.WebRtcClassLoader 的 ClassLoader
            try {
                val official = Class.forName("org.webrtc.WebRtcClassLoader")
                val m = official.getDeclaredMethod("getClassLoader")
                m.isAccessible = true
                return m.invoke(null) ?: throw RuntimeException("org.webrtc.WebRtcClassLoader returned null")
            } catch (t: Throwable) {
                // 回退：返回当前类的 ClassLoader
                val cl: ClassLoader? = WebRtcClassLoader::class.java.classLoader
                if (cl == null) {
                    throw RuntimeException("Failed to get WebRTC class loader.", t)
                }
                return cl
            }
        }
    }
}
