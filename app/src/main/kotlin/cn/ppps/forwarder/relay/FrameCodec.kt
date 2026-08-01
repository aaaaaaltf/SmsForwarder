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
        val bytes = buffer.toByteArray()
        if (bytes.size < 4) return null
        val frameLen = FrameCodec.readInt(bytes, 0)
        if (frameLen <= 0 || frameLen > FrameCodec.MAX_FRAME_SIZE) {
            // 帧长度异常，清空缓冲
            buffer.reset()
            return null
        }
        val total = 4 + frameLen
        if (bytes.size < total) return null
        buffer.reset()
        if (bytes.size > total) {
            buffer.write(bytes, total, bytes.size - total)
        }
        return bytes.copyOfRange(0, total)
    }
}
