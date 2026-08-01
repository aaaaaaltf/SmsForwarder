package com.example.remoteconsole.client;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.remoteconsole.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机远程 - 客户端界面（中继控制端，控制另一部手机）
 * 连接云中继 106.12.48.88:56782，显示在线被控端（含手机被控端），可远程查询短信/通话/话簿、
 * 定位、电量、远程唤醒、屏幕预览、开启被控端摄像头等。
 * ★ 摄像头帧解析支持 "索引|流ID|JPEG" 新格式，并过滤中继缓冲的旧流残留帧
 */
public class ClientActivity extends AppCompatActivity implements View.OnClickListener {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText etHost;
    private EditText etPort;
    private Button btnConnect;
    private TextView tvStatus;
    private TextView tvRelayStatus;
    private TextView tvDevStatus;
    private ListView lvDevices;
    private TextView tvResult;

    private ArrayAdapter<String> deviceAdapter;
    private RelayControllerClient client;
    private int selectedPcId = -1;
    private final Map<Integer, String> devices = new LinkedHashMap<>();

    // ==================== 静态引用：供屏幕预览/摄像头预览界面复用中继连接 ====================
    /** 当前中继控制端连接（供 ScreenPreviewActivity / CameraPreviewActivity 复用发送命令） */
    public static volatile RelayControllerClient sClient;

    /** 流帧监听器（由 CameraPreviewActivity 注册，接收摄像头JPEG帧与状态） */
    public static volatile StreamFrameListener sStreamFrameListener;

    /** ★ 上次已消费的摄像头流ID：跨页面保留，过滤中继缓冲的旧流残留帧（重开/切换后显示旧视频） */
    public static volatile long sLastStreamId = 0L;

    /** 摄像头流帧/状态回调接口 */
    public interface StreamFrameListener {
        void onCameraFrame(int cameraIndex, byte[] jpeg);
        void onCameraStatus(int cameraIndex, boolean success, String detail);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);

        etHost = findViewById(R.id.et_host);
        etPort = findViewById(R.id.et_port);
        btnConnect = findViewById(R.id.btn_connect);
        tvStatus = findViewById(R.id.tv_status);
        tvRelayStatus = findViewById(R.id.tv_relay_status);
        tvDevStatus = findViewById(R.id.tv_dev_status);
        lvDevices = findViewById(R.id.lv_devices);
        tvResult = findViewById(R.id.tv_result);

        etHost.setText(RelayCommands.RELAY_HOST);
        etPort.setText(String.valueOf(RelayCommands.RELAY_CONTROLLER_PORT));

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvDevices.setAdapter(deviceAdapter);
        lvDevices.setOnItemClickListener((parent, view, position, id) -> selectDevice(position));

        btnConnect.setOnClickListener(this);
        findViewById(R.id.btn_sms).setOnClickListener(this);
        findViewById(R.id.btn_call).setOnClickListener(this);
        findViewById(R.id.btn_contacts).setOnClickListener(this);
        findViewById(R.id.btn_add_contact).setOnClickListener(this);
        findViewById(R.id.btn_location).setOnClickListener(this);
        findViewById(R.id.btn_battery).setOnClickListener(this);
        findViewById(R.id.btn_wol).setOnClickListener(this);
        findViewById(R.id.btn_screen).setOnClickListener(this);
        findViewById(R.id.btn_camera).setOnClickListener(this);

        updateRelayStatus(false);
        updateDeviceStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从预览界面返回时刷新连接状态
        updateRelayStatus(client != null && client.isConnected());
        updateDeviceStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sStreamFrameListener = null;
        if (client != null) client.stop();
        sClient = null;
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_connect) {
            if (client != null && client.isConnected()) {
                disconnect();
            } else {
                connect();
            }
        } else if (id == R.id.btn_sms) {
            request(RelayCommands.CMD_SMS_QUERY, "{\"type\":1,\"page_num\":1,\"page_size\":50,\"keyword\":\"\"}", RelayCommands.RSP_SMS_QUERY, "查询短信");
        } else if (id == R.id.btn_call) {
            request(RelayCommands.CMD_CALL_QUERY, "{\"type\":1,\"page_num\":1,\"page_size\":50,\"phone_number\":\"\"}", RelayCommands.RSP_CALL_QUERY, "查询通话记录");
        } else if (id == R.id.btn_contacts) {
            request(RelayCommands.CMD_CONTACT_QUERY, "{\"page_num\":1,\"page_size\":50,\"phone_number\":\"\",\"name\":\"\"}", RelayCommands.RSP_CONTACT_QUERY, "查询话簿");
        } else if (id == R.id.btn_add_contact) {
            showAddContactDialog();
        } else if (id == R.id.btn_location) {
            request(RelayCommands.CMD_LOCATION, "", RelayCommands.RSP_LOCATION, "查询定位");
        } else if (id == R.id.btn_battery) {
            request(RelayCommands.CMD_BATTERY, "", RelayCommands.RSP_BATTERY, "查询电量");
        } else if (id == R.id.btn_wol) {
            showWolDialog();
        } else if (id == R.id.btn_screen) {
            openScreenPreview();
        } else if (id == R.id.btn_camera) {
            openCameraPreview();
        }
    }

    // ==================== 连接管理 ====================

    private void connect() {
        String host = etHost.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            port = RelayCommands.RELAY_CONTROLLER_PORT;
        }
        if (host.isEmpty()) {
            toast("请输入中继服务器地址");
            return;
        }
        if (client != null) client.stop();
        client = new RelayControllerClient(host, port, listener);
        sClient = client;
        client.start();
        tvRelayStatus.setText("控制端 → 中继：正在连接...");
        tvRelayStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
    }

    private void disconnect() {
        if (client != null) client.stop();
        client = null;
        sClient = null;
        selectedPcId = -1;
        devices.clear();
        refreshDeviceList();
        tvStatus.setText("未连接中继服务器");
        btnConnect.setText("连接中继");
        updateRelayStatus(false);
        updateDeviceStatus();
    }

    private final RelayControllerClient.Listener listener = new RelayControllerClient.Listener() {
        @Override
        public void onConnected() {
            mainHandler.post(() -> {
                tvStatus.setText("已连接中继服务器");
                btnConnect.setText("断开连接");
                updateRelayStatus(true);
                updateDeviceStatus();
            });
        }

        @Override
        public void onDisconnected() {
            mainHandler.post(() -> {
                tvStatus.setText("连接已断开，自动重连中...");
                btnConnect.setText("连接中继");
                updateRelayStatus(false);
                updateDeviceStatus();
            });
        }

        @Override
        public void onDeviceOnline(int pcId, String ip) {
            mainHandler.post(() -> {
                devices.put(pcId, ip);
                if (selectedPcId < 0) {
                    selectedPcId = pcId;
                    toast("已自动选择被控端 #" + pcId);
                }
                refreshDeviceList();
                updateDeviceStatus();
            });
        }

        @Override
        public void onDeviceOffline(int pcId) {
            mainHandler.post(() -> {
                devices.remove(pcId);
                if (selectedPcId == pcId) {
                    selectedPcId = devices.isEmpty() ? -1 : devices.keySet().iterator().next();
                }
                refreshDeviceList();
                updateDeviceStatus();
            });
        }

        @Override
        public void onCommand(int pcId, String cmd, String payload) {
            if (RelayCommands.CMD_CAMERA_STATUS_REPORT.equals(cmd)) {
                // 摄像头状态报告: "索引|success/failed|详情"
                StreamFrameListener l = sStreamFrameListener;
                if (l != null) {
                    String[] parts = payload.split("\\|", 3);
                    int idx = 0;
                    boolean ok = false;
                    if (parts.length >= 2) {
                        try {
                            idx = Integer.parseInt(parts[0].trim());
                        } catch (Exception e) {
                            idx = 0;
                        }
                        ok = "success".equalsIgnoreCase(parts[1].trim());
                    }
                    String detail = parts.length >= 3 ? parts[2] : "";
                    l.onCameraStatus(idx, ok, detail);
                }
                return;
            }
            if (client != null) client.dispatchResponse(cmd, payload);
        }

        @Override
        public void onBinaryCommand(int pcId, String cmd, byte[] payload) {
            if (RelayCommands.CMD_CAMERA_STREAM_FRAME.equals(cmd)) {
                dispatchCameraFrame(payload);
            }
        }
    };

    /** 解析摄像头帧负载（"索引|流ID|"前缀 + JPEG二进制），丢弃旧流残留帧 */
    private void dispatchCameraFrame(byte[] payload) {
        StreamFrameListener l = sStreamFrameListener;
        if (l == null || payload == null || payload.length == 0) return;
        int sep1 = -1;
        int sep2 = -1;
        for (int i = 0; i < payload.length; i++) {
            if (payload[i] == '|') {
                if (sep1 < 0) sep1 = i;
                else {
                    sep2 = i;
                    break;
                }
            }
        }
        int cameraIndex = 0;
        long streamId = 0L;
        byte[] jpeg;
        if (sep2 > 0) {
            // 新格式: 索引|流ID|JPEG
            try {
                cameraIndex = Integer.parseInt(new String(payload, 0, sep1, "UTF-8").trim());
            } catch (Exception e) {
                cameraIndex = 0;
            }
            try {
                streamId = Long.parseLong(new String(payload, sep1 + 1, sep2 - sep1 - 1, "UTF-8").trim());
            } catch (Exception e) {
                streamId = 0L;
            }
            jpeg = new byte[payload.length - sep2 - 1];
            System.arraycopy(payload, sep2 + 1, jpeg, 0, jpeg.length);
        } else if (sep1 > 0) {
            // 旧格式兼容: 索引|JPEG
            try {
                cameraIndex = Integer.parseInt(new String(payload, 0, sep1, "GBK").trim());
            } catch (Exception e) {
                cameraIndex = 0;
            }
            jpeg = new byte[payload.length - sep1 - 1];
            System.arraycopy(payload, sep1 + 1, jpeg, 0, jpeg.length);
        } else {
            jpeg = payload;
        }
        // ★ 丢弃旧流残留帧：流ID不大于上次已消费的流ID → 中继缓冲的旧帧
        if (streamId > 0 && streamId <= sLastStreamId) return;
        if (streamId > 0) sLastStreamId = streamId;
        l.onCameraFrame(cameraIndex, jpeg);
    }

    // ==================== 连接状态显示（实时更新） ====================

    /** 控制端 → 中继 连接状态 */
    private void updateRelayStatus(boolean connected) {
        if (tvRelayStatus == null) return;
        if (connected) {
            tvRelayStatus.setText("控制端 → 中继：已连接");
            tvRelayStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            tvRelayStatus.setText("控制端 → 中继：未连接");
            tvRelayStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        }
    }

    /** 中继 → 被控端 连接状态（在线数量 + 当前选中） */
    private void updateDeviceStatus() {
        if (tvDevStatus == null) return;
        if (devices.isEmpty()) {
            tvDevStatus.setText("中继 → 被控端：无在线被控端");
            tvDevStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        } else {
            tvDevStatus.setText("中继 → 被控端：" + devices.size() + " 台在线（选中 #" + selectedPcId + "）");
            tvDevStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        }
    }

    // ==================== 屏幕预览 / 摄像头 ====================

    private void openScreenPreview() {
        if (client == null || !client.isConnected()) {
            toast("请先连接中继服务器");
            return;
        }
        if (selectedPcId < 0) {
            toast("请先在列表中选择被控端");
            return;
        }
        Intent intent = new Intent(this, ScreenPreviewActivity.class);
        intent.putExtra("pcId", selectedPcId);
        startActivity(intent);
    }

    private void openCameraPreview() {
        if (client == null || !client.isConnected()) {
            toast("请先连接中继服务器");
            return;
        }
        if (selectedPcId < 0) {
            toast("请先在列表中选择被控端");
            return;
        }
        Intent intent = new Intent(this, CameraPreviewActivity.class);
        intent.putExtra("pcId", selectedPcId);
        startActivity(intent);
    }

    private void refreshDeviceList() {
        deviceAdapter.clear();
        for (Map.Entry<Integer, String> e : devices.entrySet()) {
            deviceAdapter.add("被控端 #" + e.getKey() + "  " + e.getValue());
        }
        deviceAdapter.notifyDataSetChanged();
        int selIdx = new ArrayList<>(devices.keySet()).indexOf(selectedPcId);
        if (selIdx >= 0) lvDevices.setItemChecked(selIdx, true);
    }

    private void selectDevice(int position) {
        List<Integer> keys = new ArrayList<>(devices.keySet());
        if (position < 0 || position >= keys.size()) return;
        selectedPcId = keys.get(position);
        lvDevices.setItemChecked(position, true);
        updateDeviceStatus();
        toast("已选择被控端 #" + selectedPcId);
    }

    // ==================== 请求 ====================

    private void request(String cmd, String payload, String rspCmd, String desc) {
        if (client == null || !client.isConnected()) {
            toast("请先连接中继服务器");
            return;
        }
        if (selectedPcId < 0) {
            toast("请先在列表中选择被控端");
            return;
        }
        tvResult.setText("正在" + desc + "，请稍候...");
        client.request(selectedPcId, cmd, payload, rspCmd,
                json -> mainHandler.post(() -> showResult(desc, json)),
                msg -> mainHandler.post(() -> tvResult.setText(desc + "失败：" + msg)));
    }

    private void showResult(String desc, String json) {
        String display = formatResult(json);
        tvResult.setText("【" + desc + "结果】\n" + display);
    }

    /** 解析 BaseResponse JSON，格式化展示 data 内容 */
    private String formatResult(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            int code = obj.optInt("code", -1);
            String msg = obj.optString("msg", "");
            Object data = obj.has("data") ? obj.opt("data") : null;
            StringBuilder sb = new StringBuilder();
            sb.append("code=").append(code).append("  msg=").append(msg).append("\n");
            if (data == null || JSONObject.NULL.equals(data)) {
                sb.append("data=null");
                return sb.toString();
            }
            if (data instanceof JSONArray) {
                JSONArray arr = (JSONArray) data;
                sb.append("共 ").append(arr.length()).append(" 条：\n");
                for (int i = 0; i < arr.length(); i++) {
                    sb.append(i + 1).append(". ").append(formatItem(arr.optJSONObject(i))).append("\n");
                }
            } else if (data instanceof JSONObject) {
                sb.append(formatItem((JSONObject) data));
            } else {
                sb.append(data);
            }
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }

    /** 抽取常见字段展示（短信/通话/话簿/定位/电量等通用字段） */
    private String formatItem(JSONObject o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder();
        appendField(sb, o, "address");
        appendField(sb, o, "body");
        appendField(sb, o, "name");
        appendField(sb, o, "phone_number");
        appendField(sb, o, "phone");
        appendField(sb, o, "date");
        appendField(sb, o, "type");
        appendField(sb, o, "duration");
        appendField(sb, o, "latitude");
        appendField(sb, o, "longitude");
        appendField(sb, o, "level");
        appendField(sb, o, "temp");
        appendField(sb, o, "voltage");
        appendField(sb, o, "device_mark");
        if (sb.length() == 0) {
            return o.toString();
        }
        return sb.toString().trim();
    }

    private void appendField(StringBuilder sb, JSONObject o, String key) {
        if (o.has(key) && !o.isNull(key)) {
            Object v = o.opt(key);
            sb.append(key).append(": ").append(v).append("; ");
        }
    }

    // ==================== 对话框 ====================

    private void showAddContactDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_name_phone, null);
        EditText etName = v.findViewById(R.id.et_name);
        EditText etPhone = v.findViewById(R.id.et_phone);
        new AlertDialog.Builder(this)
                .setTitle("新增联系人")
                .setView(v)
                .setPositiveButton("添加", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String phone = etPhone.getText().toString().trim();
                    if (name.isEmpty() || phone.isEmpty()) {
                        toast("姓名和号码不能为空");
                        return;
                    }
                    String payload = "{\"name\":\"" + name + "\",\"phone_number\":\"" + phone + "\"}";
                    request(RelayCommands.CMD_CONTACT_ADD, payload, RelayCommands.RSP_CONTACT_ADD, "添加联系人");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showWolDialog() {
        final EditText input = new EditText(this);
        input.setHint("请输入MAC地址，如 00:11:22:33:44:55");
        new AlertDialog.Builder(this)
                .setTitle("远程唤醒(WOL)")
                .setView(input)
                .setPositiveButton("发送", (d, w) -> {
                    String mac = input.getText().toString().trim();
                    if (mac.isEmpty()) {
                        toast("MAC地址不能为空");
                        return;
                    }
                    String payload = "{\"mac\":\"" + mac + "\",\"ip\":\"\",\"port\":9}";
                    request(RelayCommands.CMD_WOL, payload, RelayCommands.RSP_WOL, "远程唤醒");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
