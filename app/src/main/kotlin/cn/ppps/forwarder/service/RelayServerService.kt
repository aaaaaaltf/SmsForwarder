package cn.ppps.forwarder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import cn.ppps.forwarder.App
import cn.ppps.forwarder.R
import cn.ppps.forwarder.activity.MainActivity
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.relay.RelayServerClient
import cn.ppps.forwarder.relay.RelayServerHandler
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.ACTION_STOP
import cn.ppps.forwarder.utils.FRONT_CHANNEL_ID
import cn.ppps.forwarder.utils.FRONT_CHANNEL_NAME
import cn.ppps.forwarder.utils.FRONT_NOTIFY_ID
import cn.ppps.forwarder.utils.RelaySettings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 被控端中继服务（前台服务）
 * 主动连接中继服务，接收控制端命令并响应。
 */
class RelayServerService : Service() {

    private val TAG = "RelayServerService"
    private var client: RelayServerClient? = null
    private var executor: ExecutorService? = null

    companion object {
        @Volatile
        var isRunning = false

        @Volatile
        var isConnected = false

        /** 手机控制端是否已连接中继（由中继 sfsys0000000 广播维护） */
        @Volatile
        var isControllerOnline = false

        fun start(context: Context) {
            val intent = Intent(context, RelayServerService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RelayServerService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 11+ 需要按 manifest 声明的前台服务类型启动（camera 类型用于后台摄像头推流）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(FRONT_NOTIFY_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(FRONT_NOTIFY_ID, buildNotification())
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY
        Log.i(TAG, "onStartCommand: ${intent.action}")
        when (intent.action) {
            ACTION_START -> startRelay()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startRelay() {
        if (client != null && isRunning) return
        isRunning = true
        executor = Executors.newFixedThreadPool(2)
        val c = RelayServerClient(
            host = RelaySettings.relayHost,
            port = RelaySettings.relayServerPort,
            onConnected = {
                isConnected = true
                Log.i(TAG, "被控端已连接中继")
            },
            onDisconnected = {
                isConnected = false
                Log.i(TAG, "被控端与中继断开")
            },
            onCommand = { cmd, payload ->
                // 命令处理放到线程池，避免阻塞接收循环
                executor?.execute {
                    try {
                        val result = RelayServerHandler.handle(cmd, payload)
                        if (result != null) {
                            client?.send(result.first, result.second)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "命令处理异常: ${e.message}")
                    }
                }
            },
        )
        c.start()
        client = c
        // 摄像头推流复用中继连接发送视频帧
        cn.ppps.forwarder.relay.CameraStreamManager.setClient(c)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false
        isConnected = false
        isControllerOnline = false
        client?.stop()
        client = null
        cn.ppps.forwarder.relay.CameraStreamManager.setClient(null)
        cn.ppps.forwarder.relay.ScreenStreamManager.releaseProjection()
        executor?.shutdownNow()
        executor = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(FRONT_CHANNEL_ID, FRONT_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentTitle = getString(R.string.app_name)
        val contentText = String.format(getString(R.string.relay_server_running), RelaySettings.relayHost)
        val flags = if (Build.VERSION.SDK_INT >= 30) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, FRONT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_forwarder)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
