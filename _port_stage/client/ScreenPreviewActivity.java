package com.example.remoteconsole.client;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.remoteconsole.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 被控端屏幕预览（远程桌面 - 控制另一部手机）
 * 1. 通过中继连接向被控端发送 rdstrt 启动命令（payload: 0|0|FPS|24|质量|pcId）
 * 2. 手机被控端以 PUSHER:pcId 推流到中继 56788
 * 3. 本界面连接中继 56788 发送 LISTENER:pcId 配对，接收 [4字节大端长度][JPEG] 帧显示
 */
public class ScreenPreviewActivity extends AppCompatActivity {

    private static final String TAG = "ScreenPreview";

    private ImageView previewImage;
    private TextView statusText;
    private Button closeButton;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private int pcId = -1;
    private volatile boolean running = false;
    private Socket videoSocket;
    private Thread connectThread;
    private volatile Bitmap currentBitmap;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_preview);

        pcId = getIntent().getIntExtra("pcId", -1);
        if (pcId < 0) {
            toast("无效的被控端ID");
            finish();
            return;
        }

        previewImage = findViewById(R.id.preview_image);
        statusText = findViewById(R.id.preview_status);
        closeButton = findViewById(R.id.btn_preview_close);
        closeButton.setOnClickListener(v -> finish());

        statusText.setText("正在请求被控端 #" + pcId + " 屏幕...");
        startRemoteDesktop();
    }

    /** 发送启动命令并连接中继视频流端口（手机被控端使用56788） */
    private void startRemoteDesktop() {
        RelayControllerClient client = ClientActivity.sClient;
        if (client == null) {
            statusText.setText("中继连接已断开");
            toast("中继连接已断开，请返回重新连接");
            return;
        }
        // 发送 rdstrt: 0|0|FPS|24|质量|pcId
        String payload = "0|0|15|24|50|" + pcId;
        client.send(pcId, RelayCommands.CMD_RD_START, payload);

        // 连接中继 56788 收流
        running = true;
        connectThread = new Thread(this::connectLoop, "ScreenPreviewConnect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /** 连接循环：连接中继56788 → 发LISTENER认证 → 收流 */
    private void connectLoop() {
        int retry = 0;
        while (running && retry < 5) {
            Socket sock = null;
            try {
                sock = new Socket();
                sock.setKeepAlive(true);
                sock.setTcpNoDelay(true);
                sock.connect(new InetSocketAddress(RelayCommands.RELAY_HOST,
                        RelayCommands.RELAY_PHONE_VIDEO_PORT), 8000);
                if (!running) {
                    try { sock.close(); } catch (IOException ignored) {}
                    return;
                }
                videoSocket = sock;
                OutputStream out = sock.getOutputStream();
                // LISTENER 认证标签与 pcId 配对
                String authTag = "LISTENER:" + pcId + "\n";
                out.write(authTag.getBytes("UTF-8"));
                out.flush();
                uiHandler.post(() -> statusText.setText("已连接中继视频流，接收被控端 #" + pcId + " 屏幕..."));
                receiveFrames(sock);
                return;
            } catch (Exception e) {
                if (!running) return;
                try { if (sock != null) sock.close(); } catch (IOException ignored) {}
                retry++;
                if (retry >= 5) {
                    final String err = e.getMessage();
                    uiHandler.post(() -> statusText.setText("视频流连接失败: " + err));
                } else {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    /** 接收 [4字节大端长度][JPEG] 帧 */
    private void receiveFrames(Socket sock) {
        try {
            InputStream in = sock.getInputStream();
            byte[] header = new byte[4];
            while (running && !sock.isClosed()) {
                if (readFully(in, header, 4) < 4) break;
                int frameLen = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                        | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                if (frameLen <= 0 || frameLen > 16 * 1024 * 1024) break;
                byte[] jpeg = new byte[frameLen];
                if (readFully(in, jpeg, frameLen) < frameLen) break;
                final Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bmp != null) {
                    uiHandler.post(() -> showFrame(bmp));
                }
            }
        } catch (IOException e) {
            if (running) {
                final String msg = e.getMessage();
                uiHandler.post(() -> statusText.setText("屏幕流中断: " + msg));
            }
        } finally {
            uiHandler.post(() -> statusText.setText("屏幕流已断开"));
        }
    }

    private int readFully(InputStream is, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = is.read(buf, total, len - total);
            if (n < 0) return total;
            total += n;
        }
        return total;
    }

    private void showFrame(Bitmap bmp) {
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
        }
        currentBitmap = bmp;
        previewImage.setImageBitmap(bmp);
        if (statusText != null) {
            statusText.setText("屏幕预览中 #" + pcId + "（点击关闭按钮结束）");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        // 发送停止命令
        try {
            RelayControllerClient client = ClientActivity.sClient;
            if (client != null && client.isConnected() && pcId >= 0) {
                client.send(pcId, RelayCommands.CMD_RD_STOP, "");
            }
        } catch (Exception ignored) {
        }
        try {
            if (videoSocket != null) videoSocket.close();
        } catch (Exception ignored) {
        }
        videoSocket = null;
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
