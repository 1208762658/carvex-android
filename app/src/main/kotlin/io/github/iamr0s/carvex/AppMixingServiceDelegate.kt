package io.github.iamr0s.carvex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.iamr0s.carvex.host.mixing.service.MixingBrand
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceDelegate
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceSession
import io.github.iamr0s.carvex.host.mixing.service.MixingSessionStatus
import io.github.iamr0s.carvex.hostregistry.HostRegistry
import io.github.iamr0s.carvex.session.CastSession
import io.github.iamr0s.carvex.session.SessionController
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.sessioncore.Stage
import io.github.iamr0s.carvex.sessioncore.progressText
import io.github.iamr0s.carvex.ui.activity.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/** Composition root: adapts app facilities to the independent CarLife service contract. */
internal class AppMixingServiceDelegate(private val service: Service) : MixingServiceDelegate {
    override val brand = MixingBrand.forFlavor(BuildConfig.FLAVOR)

    private val controller: CastSession = SessionController(service, LocalVideoDisplay, { context, events ->
        // A flavor always selects its own host, including when that host cannot be bound.
        val plugin = HostRegistry.byId(brand.pluginId)
            ?: error("No host plugin registered for flavor ${brand.id}")
        plugin.create(context, events)
    }, GlobalContext.get().get())

    override val session = object : MixingServiceSession {
        override val active: Boolean get() = controller.active
        // 宿主（CW）靠这条 Flow 判断会话该不该继续：一旦看到 failed=true 就会收掉投屏，
        // 我们也就同时失去画面源和连接窗口。但 CarVeX 的失败绝大多数是可重试的——
        // 车机待命不响应 page、栈未就绪、channel 被拒，这些下一轮就有可能成功。
        // 所以这里只把"必须由用户处理"的失败放出去（权限、未配对、连续失败熔断），
        // 其余重试期间一律报"连接中"，让 CW 保持会话，把窗口留给车机回连。
        override val status = BridgeStore.state.map { state ->
            val mustAskUser = state.stage == Stage.FAILED &&
                (state.error.orEmpty().contains("权限") ||
                    state.error.orEmpty().contains("配对") ||
                    state.detail.contains("已暂停重试"))
            val text = when {
                state.stage == Stage.RETRY -> (state.detail.ifBlank { "正在连接车机" })
                state.stage == Stage.FAILED && !mustAskUser -> "正在连接车机（自动重试中）"
                else -> state.progressText.ifBlank { state.stage.title }
            }
            MixingSessionStatus(text, mustAskUser)
        }

        override suspend fun start(fromHost: Boolean) {
            val options = BridgeStore.options.value
            if (ShizukuBluetoothMac.needed(options.phoneMac) && ShizukuBluetoothMac.granted()) {
                withContext(Dispatchers.IO) { ShizukuBluetoothMac.read() }?.let { mac ->
                    BridgeStore.configure(BridgeStore.options.value.copy(phoneMac = mac))
                    log("entry", "Shizuku read local Bluetooth MAC: $mac")
                }
            }
            controller.start(fromHost)
        }

        override suspend fun disconnect() = controller.disconnect()
        override fun close() = controller.close()
        override fun setVideoBitrate(value: Int) = controller.setVideoBitrate(value)
        override fun sendHostKey(code: Int) = controller.triggerCarWithKey(code)
        override fun startHostAudioProbe() = controller.startCarWithAudioProbe()
        override fun toggleProtocolAudioTest() = controller.toggleCarPlayAudioTest()
    }

    /**
     * 导航标定入口（临时调试面，标定完成后可整段删除）：
     * 让 adb 在不依赖 CarWith/高德的情况下直接驱动 5201/5202，把固件的转向类型扫出来。
     *
     * adb shell am broadcast -a io.github.iamr0s.carvex.NAV_TEST \
     *   --es road 测试路 --ei remain 1200 --ei to_maneuver 300 --ei type 3
     */
    private val navTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            fun optionalInt(name: String): Int? =
                if (intent.hasExtra(name)) intent.getIntExtra(name, -1) else null
            controller.pushNavigation(
                road = intent.getStringExtra("road"),
                remainMeters = optionalInt("remain"),
                toManeuverMeters = optionalInt("to_maneuver"),
                maneuverType = optionalInt("type"),
                state = optionalInt("state"),
                distanceUnits = optionalInt("units"),
            )
        }
    }

    init {
        ContextCompat.registerReceiver(
            service,
            navTestReceiver,
            IntentFilter("io.github.iamr0s.carvex.NAV_TEST"),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    init {
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("connection", "车机连接", NotificationManager.IMPORTANCE_LOW))
    }

    override fun missingPermissions(): List<String> = Permissions.missing(service)
    override fun log(tag: String, message: String) = BridgeStore.log(tag, message)

    override fun notification(text: String, disconnect: PendingIntent): Notification {
        val open = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(service, "connection")
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_bridge, "断开", disconnect).build()
    }
}
