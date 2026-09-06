package cn.ppps.forwarder.relay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 中继帧编解码工具
 * 帧格式与中继服务(python_version/core/relay_server.py)完全一致：
 * - 被控端帧: [4字节大端长度][数据]
 * - 控制端帧: [4字节大端总长度(含pc_id)][4字节大端pc_id][数据]
 */
object FrameCodec {

    /** 最大帧长度（100MB，与中继一致） */
    const val MAX_FRAME_SIZE = 100 * 1024 * 1024

    /** 构建普通帧：[4字节大端长度][data] */
    fun encode(data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + data.size)
        buf.putInt(data.size)
        buf.put(data)
        return buf.array()
    }

    /** 构建控制端带pc_id的帧：[4字节大端总长度][4字节大端pc_id][data]，总长度 = 4 + data.size */
    fun encodeWithPcId(pcId: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + 4 + data.size)
        buf.putInt(4 + data.size)
        buf.putInt(pcId)
        buf.put(data)
        return buf.array()
    }

    /** 读取4字节大端长度 */
    fun readInt(data: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(data, offset, 4).int
    }
}

/**
 * TCP粘包缓冲：按 [4字节大端长度][数据] 取出完整帧
 */
class StreamBuffer {
    private val buffer = ByteArrayOutputStream()

    /** 追加收到的原始字节 */
    fun append(data: ByteArray) {
        buffer.write(data)
    }

    /**
     * 尝试取出一个完整帧
     * @return 完整帧（含4字节长度头），数据不足返回 null
     */
    fun readFrame(): ByteArray? {
        while (true) {
            val bytes = buffer.toByteArray()
            if (bytes.size < 4) return null
            val frameLen = FrameCodec.readInt(bytes, 0)
            // 合法长度只有两类：0（空帧——FrameCodec.encode(b'') 就是 00 00 00 00，
            // 被控端侧 30 秒空闲心跳发的正是它）与 >=4（中继帧至少含 4 字节 pc_id）。
            // 原先 frameLen <= 0 判非法后 buffer.reset()：一个心跳空帧就把同一次 read
            // 里排在它后面的完整命令帧整段销毁，直到重连才恢复。
            // 现在空帧照常上交，调用点已有的 `if (frame.size <= 4) continue` 会跳过它。
            if (frameLen < 0 || (frameLen > 0 && frameLen < 4) || frameLen > FrameCodec.MAX_FRAME_SIZE) {
                // 真·坏头：只丢这 4 字节表头重新对齐，手里已到帧一个字节都不销毁
                reflush(bytes, 4)
                continue
            }
            val total = 4 + frameLen
            if (bytes.size < total) return null
            reflush(bytes, total)
            return bytes.copyOfRange(0, total)
        }
    }

    /** 丢弃开头 consumed 字节（帧已取走或表头无效），保留其余数据 */
    private fun reflush(bytes: ByteArray, consumed: Int) {
        buffer.reset()
        if (bytes.size > consumed) {
            buffer.write(bytes, consumed, bytes.size - consumed)
        }
    }
}
