package io.github.iamr0s.carvex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.iamr0s.carvex.sessioncore.BridgeStore

/**
 * 常驻前台服务，只为把进程优先级抬到前台档。
 *
 * CarWith（小米车联）一被打开就会把 CarVeX 当成普通后台进程回收掉：Bootstrap 里那条
 * v14.12 注释记的就是这件事，表现是会话进行中整进程消失、866F 的 bond 跟着丢。此前
 * 工程里没有任何前台服务，纯后台进程在 CarWith 申请虚拟屏 / 编码器 / 音频那套重资源
 * 时，是最先被 LMK 挑中的对象。
 *
 * 这里声明 connectedDevice 类型：它对应的正是蓝牙外设连接这类长时任务，且在 Android
 * 14 之后属于可以后台拉起的前台服务类型之一。通知只是附属品，即便用户没给通知权限、
 * 通知不可见，前台服务状态本身仍然生效。
 */
class CarVexForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        promote()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        // START_STICKY：进程被系统回收后由系统重建，避免 CarWith 一开就再无 CarVeX。
        return START_STICKY
    }

    private fun promote() {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CarVeX 连接保活",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持 CarVeX 在 CarWith 启动时不被系统回收"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

            val notification: Notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CarVeX 运行中")
                .setContentText("保持无线 CarPlay 连接进程存活")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            BridgeStore.log("", "Foreground service promoted (connectedDevice) so CarWith cannot reclaim us")
        }.onFailure {
            BridgeStore.log("", "Foreground service promote failed: " + (it.message ?: it.javaClass.simpleName))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "carvex-keepalive"
        private const val NOTIFICATION_ID = 0x0CA7

        /** 幂等拉起；调用方不必判断是否已在运行。 */
        fun ensure(context: Context) {
            runCatching {
                val intent = Intent(context, CarVexForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure {
                BridgeStore.log("", "Foreground service start failed: " + (it.message ?: it.javaClass.simpleName))
            }
        }
    }
}
