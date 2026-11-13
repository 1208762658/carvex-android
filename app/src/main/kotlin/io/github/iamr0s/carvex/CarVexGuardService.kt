package io.github.iamr0s.carvex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.iamr0s.carvex.sessioncore.BridgeStore

/**
 * 独立进程（`:guard`）的守护服务。
 *
 * CarVeXForegroundService 只能把进程抬到前台档，挡不住 CarWith 启动时对同名 CarLife
 * 应用的强清理：2.1 日志里 14:17 那个持有会话、且已经打过后台提升的进程，在 14:18:33
 * CarWith 发起 `CarlifeConnectService` 的同时被静默杀掉，会话正跑到 video=704。
 *
 * 前台服务对 force-stop 无解，但独立进程可以在主进程消失后立刻把它拉回来：
 * 这里每 5 秒 bindService(BIND_AUTO_CREATE) 一次主进程的前台服务，绑定断开即视为
 * 主进程已死，下一拍重新 bind 会把进程重新创建出来。主进程侧 BridgeApp.onCreate
 * 再拉起前台服务并重建常驻发布，所以恢复是"新进程 + 重新注册"，不是靠旧状态。
 *
 * 反向也做：主进程 onCreate 会 startService 拉起本进程，两边互为看护。
 */
class CarVexGuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var bound = false
    private var attempts = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            BridgeStore.log("", "Guard: main process attached (attempt=$attempts)")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            BridgeStore.log("", "Guard: main process lost; will re-bind")
        }

        override fun onBindingDied(name: ComponentName?) {
            bound = false
        }

        override fun onNullBinding(name: ComponentName?) {
            // CarVexForegroundService.onBind 返回 null：进程起来了就算成功。
            bound = true
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!bound) {
                attempts++
                runCatching {
                    bindService(
                        Intent(this@CarVexGuardService, CarVexForegroundService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }.onFailure {
                    BridgeStore.log("", "Guard: bind failed " + (it.message ?: it.javaClass.simpleName))
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        promote()
        handler.postDelayed(tick, 500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { if (bound) unbindService(connection) }
        super.onDestroy()
    }

    private fun promote() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CarVeX 守护", NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = "独立进程看护，主进程被清理后自动拉起"
                        setShowBadge(false)
                    },
            )
            val notification: Notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CarVeX 守护中")
                .setContentText("主进程被 CarWith 清理后会自动恢复")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            BridgeStore.log("", "Guard service running in :guard process")
        }.onFailure {
            BridgeStore.log("", "Guard promote failed: " + (it.message ?: it.javaClass.simpleName))
        }
    }

    companion object {
        private const val CHANNEL_ID = "carvex-guard"
        private const val NOTIFICATION_ID = 0x0CA8
        private const val TICK_MS = 5_000L

        fun ensure(context: Context) {
            runCatching {
                val intent = Intent(context, CarVexGuardService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure {
                BridgeStore.log("", "Guard start failed: " + (it.message ?: it.javaClass.simpleName))
            }
        }
    }
}
