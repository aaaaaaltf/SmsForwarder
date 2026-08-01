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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 被控端摄像头预览（控制另一部手机的摄像头）
 * - 通过中继向被控端发送 vcdstr 启动摄像头流（被控端仅采集推流，不在其屏幕显示）
 * - 通过 ClientActivity.sStreamFrameListener 接收 vcdfrm000000 JPEG帧显示
 * - ★ 支持前置/后置切换：先发 vcdstp 停止旧流，收到确认后发 vcdstr(新索引) 启动新流；
 *   切换期间禁用切换按钮，直到新视频流更新才解除
 * - ★ 画面叠加显示当前摄像头（前置/后置）与实时时间（精确到秒）
 * - 退出时发送 vcdstp 停止
 */
public class CameraPreviewActivity extends AppCompatActivity implements ClientActivity.StreamFrameListener {

    private static final int ACTION_NONE = 0;
    private static final int ACTION_SWITCH_STOPPING = 1;
    private static final int ACTION_SWITCH_STARTING = 2;

    private ImageView previewImage;
    private TextView statusText;
    private TextView tagText;
    private TextView timeText;
    private Button closeButton;
    private Button switchButton;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private int pcId = -1;
    private volatile Bitmap currentBitmap;
    private boolean started = false;

    /** 当前摄像头索引：0=后置，1=前置 */
    private int cameraIndex = 0;
    /** 切换目标索引 */
    private int pendingSwitchIndex = 0;
    /** 切换状态机 */
    private int actionState = ACTION_NONE;
    /** 切换超时兜底 */
    private Runnable switchTimeoutRunnable = null;

    /** 画面实时时钟（精确到秒） */
    private final Runnable timeRunnable = new Runnable() {
        @Override
        public void run() {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            if (timeText != null) timeText.setText(time);
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_preview);

        pcId = getIntent().getIntExtra("pcId", -1);
        if (pcId < 0) {
            toast("无效的被控端ID");
            finish();
            return;
        }

        previewImage = findViewById(R.id.camera_image);
        statusText = findViewById(R.id.camera_status);
        tagText = findViewById(R.id.camera_tag);
        timeText = findViewById(R.id.camera_time);
        closeButton = findViewById(R.id.btn_camera_close);
        switchButton = findViewById(R.id.btn_camera_switch);

        closeButton.setOnClickListener(v -> finish());
        switchButton.setOnClickListener(v -> {
            if (actionState == ACTION_NONE) switchCamera();
            else toast("正在切换摄像头，请稍候...");
        });
        // 画面实时时间
        uiHandler.post(timeRunnable);
        // 显示当前摄像头标签
        tagText.setText(cameraName(cameraIndex));

        ClientActivity.sStreamFrameListener = this;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ClientActivity.sStreamFrameListener = this;
        startCameraStream();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCameraStream();
        ClientActivity.sStreamFrameListener = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCameraStream();
        if (ClientActivity.sStreamFrameListener == this) {
            ClientActivity.sStreamFrameListener = null;
        }
        uiHandler.removeCallbacks(timeRunnable);
        if (switchTimeoutRunnable != null) uiHandler.removeCallbacks(switchTimeoutRunnable);
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
    }

    private String cameraName(int index) {
        return index == 0 ? "后置" : "前置";
    }

    /** 发送启动摄像头流命令（负载=摄像头索引），被控端仅采集推流不显示本机画面 */
    private void startCameraStream() {
        RelayControllerClient client = ClientActivity.sClient;
        if (client == null || !client.isConnected()) {
            statusText.setText("中继连接已断开，无法启动摄像头");
            toast("中继连接已断开");
            return;
        }
        // ★ 清空旧画面：避免重新打开时残留关闭前的画面
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
        previewImage.setImageBitmap(null);
        started = true;
        statusText.setText("正在开启被控端 #" + pcId + " 摄像头...");
        tagText.setText(cameraName(cameraIndex));
        client.send(pcId, RelayCommands.CMD_CAMERA_STREAM_START, String.valueOf(cameraIndex));
        // ★ 启动超时兜底：10秒未收到任何帧则提示（连接可能已失效）
        if (switchTimeoutRunnable != null) uiHandler.removeCallbacks(switchTimeoutRunnable);
        switchTimeoutRunnable = () -> {
            if (actionState == ACTION_NONE && currentBitmap == null) {
                statusText.setText("未收到视频流，请检查被控端连接后重试");
            }
        };
        uiHandler.postDelayed(switchTimeoutRunnable, 10000);
    }

    /** 切换摄像头（0后置 ↔ 1前置）：先停止旧流，确认后启动新索引；切换期间禁止再次切换 */
    private void switchCamera() {
        RelayControllerClient client = ClientActivity.sClient;
        if (client == null || !client.isConnected()) {
            toast("中继连接已断开");
            return;
        }
        int newIndex = 1 - cameraIndex;
        pendingSwitchIndex = newIndex;
        actionState = ACTION_SWITCH_STOPPING;
        switchButton.setEnabled(false);
        statusText.setText("正在切换为" + cameraName(newIndex) + "摄像头，请稍候...");
        // 清空旧画面，避免切换期间显示旧流
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
        previewImage.setImageBitmap(null);
        // ★ 切换超时兜底：STOP确认丢失时自动补发新索引START
        if (switchTimeoutRunnable != null) uiHandler.removeCallbacks(switchTimeoutRunnable);
        switchTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (actionState == ACTION_SWITCH_STOPPING) {
                    actionState = ACTION_SWITCH_STARTING;
                    RelayControllerClient c = ClientActivity.sClient;
                    if (c != null && c.isConnected()) {
                        c.send(pcId, RelayCommands.CMD_CAMERA_STREAM_START, String.valueOf(pendingSwitchIndex));
                        statusText.setText("正在切换为" + cameraName(pendingSwitchIndex) + "摄像头，请稍候...");
                        uiHandler.postDelayed(this, 8000);
                        return;
                    }
                }
                if (actionState != ACTION_NONE) {
                    actionState = ACTION_NONE;
                    switchButton.setEnabled(true);
                    statusText.setText("切换超时，请重试");
                }
            }
        };
        uiHandler.postDelayed(switchTimeoutRunnable, 8000);
        // ★ 命令通道立即通知被控端停止发送当前视频流
        client.send(pcId, RelayCommands.CMD_CAMERA_STREAM_STOP, String.valueOf(cameraIndex));
    }

    /** 发送停止摄像头流命令 */
    private void stopCameraStream() {
        if (!started) return;
        started = false;
        try {
            RelayControllerClient client = ClientActivity.sClient;
            if (client != null && client.isConnected() && pcId >= 0) {
                client.send(pcId, RelayCommands.CMD_CAMERA_STREAM_STOP, String.valueOf(cameraIndex));
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== StreamFrameListener 回调（后台线程调用） ====================

    @Override
    public void onCameraFrame(int cameraIndex, byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        final Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) return;
        final int frameIdx = cameraIndex;
        uiHandler.post(() -> {
            // ★ 切换中：仅接受与切换目标一致的新流帧（旧流残留帧不解除锁定）
            if (actionState == ACTION_SWITCH_STARTING && frameIdx != pendingSwitchIndex) {
                if (!bmp.isRecycled()) bmp.recycle();
                return;
            }
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
            }
            currentBitmap = bmp;
            previewImage.setImageBitmap(bmp);
            tagText.setText(cameraName(frameIdx));
            // ★ 新视频流已更新：解除切换锁定，并显示当前摄像头名称
            if (actionState == ACTION_SWITCH_STARTING) {
                actionState = ACTION_NONE;
                if (switchTimeoutRunnable != null) uiHandler.removeCallbacks(switchTimeoutRunnable);
                CameraPreviewActivity.this.cameraIndex = frameIdx;
                switchButton.setEnabled(true);
                statusText.setText("摄像头画面（" + cameraName(frameIdx) + "）");
            } else if (actionState == ACTION_NONE && started) {
                statusText.setText("摄像头画面（" + cameraName(frameIdx) + "）");
            }
        });
    }

    @Override
    public void onCameraStatus(int cameraIndex, boolean success, String detail) {
        final int idx = cameraIndex;
        uiHandler.post(() -> {
            if (actionState == ACTION_SWITCH_STOPPING) {
                // ★ 被控端确认已停止 → 立即发送新索引启动命令
                actionState = ACTION_SWITCH_STARTING;
                RelayControllerClient c = ClientActivity.sClient;
                if (c != null && c.isConnected()) {
                    c.send(pcId, RelayCommands.CMD_CAMERA_STREAM_START, String.valueOf(pendingSwitchIndex));
                    statusText.setText("正在切换为" + cameraName(pendingSwitchIndex) + "摄像头，请稍候...");
                } else {
                    actionState = ACTION_NONE;
                    switchButton.setEnabled(true);
                    statusText.setText("切换失败：中继已断开");
                }
                return;
            }
            if (actionState == ACTION_SWITCH_STARTING) {
                if (!success) {
                    // 启动失败：解除锁定并提示
                    actionState = ACTION_NONE;
                    if (switchTimeoutRunnable != null) uiHandler.removeCallbacks(switchTimeoutRunnable);
                    switchButton.setEnabled(true);
                    statusText.setText("摄像头启动失败：" + detail);
                }
                return;
            }
            if (success && detail != null && detail.contains("已启动")) {
                statusText.setText("摄像头画面（" + cameraName(idx) + "）");
            } else if (!success) {
                statusText.setText("摄像头启动失败：" + detail);
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
