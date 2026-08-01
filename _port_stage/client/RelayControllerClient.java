package com.example.remoteconsole.client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 控制端中继客户端（Java 移植版）
 * 主动连接中继服务 56782 端口，连接成功后发送1字节类型标识(0x01=手机控制端)，
 * 支持多被控端(pc_id)复用同一连接。
 *
 * 发送帧: [4字节大端总长度][4字节大端pc_id][12字节命令][负载]
 * 接收帧: [4字节大端总长度][4字节大端pc_id][12字节命令][负载]
 * pc_id = 0xFFFFFFFF 时为中继系统广播（online000000 / discon000000，GBK编码）
 *
 * ★ 修复：所有socket写操作在专用后台发送线程执行，避免主线程直发触发 NetworkOnMainThreadException
 */
public class RelayControllerClient {

    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onDeviceOnline(int pcId, String ip);
        void onDeviceOffline(int pcId);
        void onCommand(int pcId, String cmd, String payload);
        /** 二进制负载回调（如摄像头JPEG帧），payload为命令之后的原始字节 */
        void onBinaryCommand(int pcId, String cmd, byte[] payload);
    }

    public interface OnResult {
        void onResult(String json);
    }

    public interface OnError {
        void onError(String msg);
    }

    private static final String TAG = "RelayControllerClient";
    private static final long REQUEST_TIMEOUT = 30000L;
    private static final int MAX_FRAME_SIZE = 100 * 1024 * 1024;

    private final String host;
    private final int port;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final Object sendLock = new Object();

    /** ★ 专用发送线程：所有socket写操作必须在后台线程执行 */
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RelayCtrlSend");
        t.setDaemon(true);
        return t;
    });

    private Socket socket;
    private volatile boolean running = false;
    private Thread thread;

    private static class PendingRequest {
        final OnResult callback;
        final Runnable timeoutRunnable;
        PendingRequest(OnResult cb, Runnable t) {
            callback = cb;
            timeoutRunnable = t;
        }
    }

    public RelayControllerClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed()
                && !s.isInputShutdown() && !s.isOutputShutdown();
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::connectLoop, "RelayControllerConnect");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        for (String key : pending.keySet()) {
            PendingRequest r = pending.remove(key);
            if (r != null) mainHandler.removeCallbacks(r.timeoutRunnable);
        }
        pending.clear();
        sendExecutor.shutdownNow();
    }

    private void connectLoop() {
        while (running) {
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(host, port), 8000);
                if (!running) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                    return;
                }
                socket = s;
                Log.i(TAG, "已连接中继服务 " + host + ":" + port);
                // 发送类型标识：0x01 = 手机控制端
                try {
                    s.getOutputStream().write(RelayCommands.CTRL_TYPE_PHONE);
                    s.getOutputStream().flush();
                } catch (IOException e) {
                    Log.w(TAG, "发送类型标识失败: " + e.getMessage());
                    throw e;
                }
                mainHandler.post(() -> listener.onConnected());
                receiveLoop(s);
            } catch (Exception e) {
                if (running) Log.w(TAG, "连接中继失败: " + e.getMessage());
            }
            socket = null;
            mainHandler.post(() -> listener.onDisconnected());
            if (running) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void receiveLoop(Socket s) {
        InputStream input;
        try {
            input = s.getInputStream();
        } catch (IOException e) {
            return;
        }
        StreamBuffer streamBuffer = new StreamBuffer();
        byte[] tmp = new byte[4096];
        try {
            while (running && !s.isClosed()) {
                int n = input.read(tmp);
                if (n < 0) break;
                streamBuffer.append(tmp, n);
                while (true) {
                    byte[] frame = streamBuffer.readFrame();
                    if (frame == null) break;
                    if (frame.length < 8) continue;
                    int pcId = readInt(frame, 4);
                    byte[] body = new byte[frame.length - 8];
                    System.arraycopy(frame, 8, body, 0, body.length);
                    String[] parsed = RelayCommands.parse(body);
                    String cmd = parsed[0];
                    if (cmd.isEmpty()) continue;
                    if (pcId == RelayCommands.BROADCAST_PC_ID) {
                        handleBroadcast(cmd, body);
                    } else {
                        try {
                            if (isBinaryCommand(cmd)) {
                                // 二进制负载命令：剥离12字节命令前缀后回调
                                byte[] payload = new byte[body.length - RelayCommands.CMD_PREFIX_LEN];
                                System.arraycopy(body, RelayCommands.CMD_PREFIX_LEN, payload, 0, payload.length);
                                listener.onBinaryCommand(pcId, cmd, payload);
                            } else {
                                listener.onCommand(pcId, cmd, parsed[1]);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "处理响应异常: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "接收中断: " + e.getMessage());
        }
    }

    /** 是否为二进制负载命令（不走文本UTF-8解码） */
    private boolean isBinaryCommand(String cmd) {
        return RelayCommands.CMD_CAMERA_STREAM_FRAME.equals(cmd);
    }

    private void handleBroadcast(String cmd, byte[] payload) {
        // 中继服务端(python)构造广播时用 GBK 编码，必须用 GBK 解码还原文本
        String text = new String(payload, Charset.forName("GBK"));
        if (RelayCommands.CMD_CLIENT_ONLINE.equals(cmd)) {
            int pcId = RelayCommands.parseBroadcastPcId(text);
            String ip = text.contains("|") ? text.substring(text.indexOf('|') + 1).trim() : "";
            if (pcId >= 0) {
                final int id = pcId;
                final String ipv = ip;
                mainHandler.post(() -> listener.onDeviceOnline(id, ipv));
            }
        } else if (RelayCommands.CMD_CLIENT_DISCONNECT.equals(cmd)) {
            int pcId = RelayCommands.parseBroadcastPcId(text);
            if (pcId >= 0) {
                final int id = pcId;
                mainHandler.post(() -> listener.onDeviceOffline(id));
            }
        }
    }

    /**
     * 发送命令到指定被控端（★ 网络写入在后台发送线程执行）
     */
    public void send(int pcId, String cmd, String payload) {
        Socket s = socket;
        if (s == null) return;
        byte[] data = concat(cmd.getBytes(StandardCharsets.US_ASCII), payload.getBytes(StandardCharsets.UTF_8));
        byte[] frame = encodeWithPcId(pcId, data);
        try {
            sendExecutor.execute(() -> {
                try {
                    synchronized (sendLock) {
                        OutputStream out = s.getOutputStream();
                        out.write(frame);
                        out.flush();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "发送失败: " + e.getMessage() + "，关闭socket触发自动重连");
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "提交发送任务失败: " + e.getMessage());
        }
    }

    /**
     * 发送请求并注册响应回调
     * @param rspCmd 期望的响应命令前缀
     */
    public void request(int pcId, String cmd, String payload, String rspCmd,
                        OnResult onResult, OnError onError) {
        if (!isConnected()) {
            onError.onError("未连接中继服务器");
            return;
        }
        pending.remove(rspCmd);
        Runnable timeoutRunnable = () -> {
            pending.remove(rspCmd);
            onError.onError("请求超时，请检查被控端是否在线");
        };
        pending.put(rspCmd, new PendingRequest(onResult, timeoutRunnable));
        mainHandler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT);
        send(pcId, cmd, payload);
    }

    /** 由接收循环调用：匹配已注册的响应回调 */
    public void dispatchResponse(String cmd, String payload) {
        PendingRequest req = pending.remove(cmd);
        if (req == null) return;
        mainHandler.removeCallbacks(req.timeoutRunnable);
        mainHandler.post(() -> {
            try {
                req.callback.onResult(payload);
            } catch (Exception e) {
                Log.e(TAG, "响应回调异常: " + e.getMessage());
            }
        });
    }

    // ==================== 帧编解码 ====================

    private static byte[] encodeWithPcId(int pcId, byte[] data) {
        int totalLen = 4 + data.length;
        byte[] frame = new byte[4 + totalLen];
        writeInt(frame, 0, totalLen);
        writeInt(frame, 4, pcId);
        System.arraycopy(data, 0, frame, 8, data.length);
        return frame;
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    private static void writeInt(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >> 24);
        b[offset + 1] = (byte) (value >> 16);
        b[offset + 2] = (byte) (value >> 8);
        b[offset + 3] = (byte) value;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** 粘包缓冲 */
    private static class StreamBuffer {
        private byte[] buf = new byte[0];

        void append(byte[] data, int len) {
            byte[] nb = new byte[buf.length + len];
            System.arraycopy(buf, 0, nb, 0, buf.length);
            System.arraycopy(data, 0, nb, buf.length, len);
            buf = nb;
        }

        byte[] readFrame() {
            if (buf.length < 4) return null;
            int totalLen = readInt(buf, 0);
            if (totalLen <= 4 || totalLen > MAX_FRAME_SIZE) {
                buf = new byte[0];
                return null;
            }
            int frameTotal = 4 + totalLen;
            if (buf.length < frameTotal) return null;
            byte[] frame = new byte[frameTotal];
            System.arraycopy(buf, 0, frame, 0, frameTotal);
            byte[] rest = new byte[buf.length - frameTotal];
            System.arraycopy(buf, frameTotal, rest, 0, rest.length);
            buf = rest;
            return frame;
        }
    }
}
