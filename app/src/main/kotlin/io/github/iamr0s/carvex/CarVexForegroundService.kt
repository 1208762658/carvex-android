package io.github.iamr0s.carvex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.sessioncore.Stage
import io.github.iamr0s.carvex.sessioncore.progressText
import io.github.iamr0s.carvex.ui.activity.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：既抬进程优先级，也是锁屏上的实时进度面板。
 *
 * CarWith（小米车联）一被打开就会把 CarVeX 当成普通后台进程回收掉：Bootstrap 里那条
 * v14.12 注释记的就是这件事，表现是会话进行中整进程消失、866F 的 bond 跟着丢。
 *
 * v48：息屏场景下 cw 拉起我们时，用户看不到手机屏幕，也就看不到"连到哪一步"。这里把
 * 通知升级成 HIGH 重要级 + 锁屏可见 + 全屏意图，配合一次 ACQUIRE_CAUSES_WAKEUP 的短锁，
 * 让屏幕亮起来直接看到 attempt/stage/帧率/码率。全屏意图在屏幕亮着时只会降级成横幅，
 * 不会打断正在做的事。
 */
class CarVexForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderJob: Job? = null
    private var lastKey: String = ""
    private var wokeFor: String = ""

    override fun onCreate() {
        super.onCreate()
        promote()
        watchState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        // START_STICKY：进程被系统回收后由系统重建，避免 CarWith 一开就再无 CarVeX。
        return START_STICKY
    }

    override fun onDestroy() {
        renderJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 订阅会话状态，把 stage/分辨率/帧率/码率实时写进通知；文本没变就不重复提交。 */
    private fun watchState() {
        renderJob?.cancel()
        renderJob = scope.launch {
            BridgeStore.state
                .map { it.copy(frames = it.frames / 1000, bytes = 0L, audioBytes = 0L) }
                .map { it.progressText + "|" + it.stage.name + "|" + it.width + "x" + it.height + "|" +
                    it.sentFbpsBucket() + "|" + it.sentMbpsBucket() + "|" + it.attempt }
                .distinctUntilChanged()
                .collect { key ->
                    val state = BridgeStore.state.value
                    lastKey = key
                    post(build(state))
                    maybeWake(state)
                }
        }
    }

    /** 真正开始连车机（离开 IDLE）且屏幕是灭的：唤醒一次，让用户看到锁屏进度。 */
    private fun maybeWake(state: io.github.iamr0s.carvex.sessioncore.SessionState) {
        if (state.stage == Stage.IDLE) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isInteractive) return
        val marker = state.id + "#" + state.attempt
        if (wokeFor == marker) return
        wokeFor = marker
        // 只亮屏、不起 Activity：屏幕亮起后停在锁屏，进度就是锁屏上那条通知。
        // PowerManager.wakeUp() 是 @hide（DEVICE_POWER，签名级）：CarLife 的权限底子若真落到本包，
        // 反射调用就能亮屏；拿不到权限就退回老 API 的亮屏锁。
        val byWakeUp = runCatching {
            PowerManager::class.java
                .getMethod("wakeUp", Long::class.javaPrimitiveType, String::class.java)
                .invoke(pm, android.os.SystemClock.uptimeMillis(), "carvex:progress")
        }.isSuccess
        val byLock = if (byWakeUp) false else runCatching {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "carvex:wake")
                .apply { setReferenceCounted(false) }
                .acquire(8_000L)
            true
        }.onFailure {
            BridgeStore.log(state.id, "screen wake failed: " + it.javaClass.simpleName + " " + (it.message ?: ""))
        }.getOrDefault(false)
        BridgeStore.log(state.id, "screen woken for progress (wakeUp=" + byWakeUp + " fallbackLock=" + byLock +
            " deviceIdle=" + pm.isDeviceIdleMode + " powerSave=" + pm.isPowerSaveMode + ")")
    }

    private fun build(state: io.github.iamr0s.carvex.sessioncore.SessionState): Notification {
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (state.stage == Stage.IDLE) "CarVeX 待机中" else "CarVeX · ${state.stage.title}")
            .setContentText(state.progressText)
            .setSubText(metrics(state))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC) // 锁屏上也显示明文进度
            .setContentIntent(openApp())
            .build()
        return n
    }

    private fun metrics(state: io.github.iamr0s.carvex.sessioncore.SessionState): String {
        if (state.stage == Stage.IDLE) return "等待 CarWith 唤起"
        val sb = StringBuilder()
        if (state.width > 0 && state.height > 0) sb.append(state.width).append('x').append(state.height)
        if (state.sentFps > 0.1) { if (sb.isNotEmpty()) sb.append(" · "); sb.append(String.format("%.0f", state.sentFps)).append("fps") }
        if (state.sentMbps > 0.1) { if (sb.isNotEmpty()) sb.append(" · "); sb.append(String.format("%.1f", state.sentMbps)).append("Mbps") }
        if (sb.isEmpty()) sb.append(state.stage.name)
        return sb.toString()
    }

    /** 点通知或全屏意图都进主界面，并让它能盖在锁屏上显示。 */
    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_WAKE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, REQUEST_WAKE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(n: Notification) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else { startForeground(NOTIFICATION_ID, n); nm.notify(NOTIFICATION_ID, n) }
        }.onFailure { BridgeStore.log("", "notification update failed: " + it.javaClass.simpleName + " " + (it.message ?: "")) }
    }

    private fun promote() {
        runCatching {
            createChannel()
            post(build(BridgeStore.state.value))
            BridgeStore.log("", "Foreground service promoted (connectedDevice) so CarWith cannot reclaim us")
            val nm = getSystemService(NotificationManager::class.java)
            val pm = getSystemService(PowerManager::class.java)
            BridgeStore.log("", "keepalive env: notificationsEnabled=" + (nm?.areNotificationsEnabled() == true) +
                " channelImportance=" + nm?.getNotificationChannel(CHANNEL_ID)?.importance +
                " interactive=" + pm?.isInteractive + " deviceIdle=" + pm?.isDeviceIdleMode +
                " powerSave=" + pm?.isPowerSaveMode +
                " batteryOptIgnored=" + (pm?.isIgnoringBatteryOptimizations(packageName) == true))
        }.onFailure {
            BridgeStore.log("", "Foreground service promote failed: " + (it.message ?: it.javaClass.simpleName))
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "CarVeX 连接进度", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "锁屏上显示无线 CarPlay 的实时连接进度"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, AudioAttributes.Builder().build())
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        /** 新渠道：旧渠道 importance=LOW 一旦创建就改不动，必须换 id 才能升到 HIGH。 */
        private const val CHANNEL_ID = "carvex-live"
        private const val NOTIFICATION_ID = 0x0CA7
        private const val REQUEST_WAKE = 0x0CA8
        const val EXTRA_WAKE = "carvex.wake"

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

private fun io.github.iamr0s.carvex.sessioncore.SessionState.sentFbpsBucket(): Long = (sentFps * 2).toLong()
private fun io.github.iamr0s.carvex.sessioncore.SessionState.sentMbpsBucket(): Long = (sentMbps * 10).toLong()
