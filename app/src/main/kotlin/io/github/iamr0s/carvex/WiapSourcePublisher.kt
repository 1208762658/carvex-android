package io.github.iamr0s.carvex

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.iamr0s.carvex.sessioncore.BluetoothRoleGate
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻 WiAP Source 发布器 —— 866F 适配的核心补丁。
 *
 * ## 为什么需要它（三份 btsnoop 实证）
 *
 * 866F（MIB3，VW BT 5384）的无线 CarPlay 是**发现驱动**的：
 * 蓝牙链路建立后约 0.3~1s 内，车机做一次 SDP 发现扫描，搜索手机侧的
 * WiAP Source 记录（plain legacy `…cafe`，以及 HiCar 0xFE35/0xFE36）。
 * 而 CarVeX 会话的 listener 只在用户点"连接"后才注册，必然晚于该窗口，
 * 手机对车机的每次发现查询都返回 0 条记录 → 车机判定"没有 WiAP Source"，
 * 其 WiAP Sink 服务器（通道 15）保持未绑定，随后对手机连入一律回 DM。
 *
 * 本类把 WiAP Source 记录（`…cafe` / `…0a77`）的发布提升到**进程生命周期**：
 * 蓝牙开启即注册、蓝牙关闭即释放、重开自动补注册。车机在任何时刻的发现
 * 查询都能命中手机记录。
 *
 * ## 公共 API 能做到什么 / 不能做什么（carvex research 71）
 *
 * - 能：`listenUsingRfcommWithServiceRecord` 发布的记录带 ServiceClassIDList
 *   （含该 UUID）+ L2CAP/RFCOMM 通道属性，**能被对端 SDP 搜索命中**，满足
 *   车机"发现 WiAP Source"的需求。
 * - 不能：legacy/v2 两条记录共享同一通道（Android 独立分配 SCN）；没有
 *   attribute 0x0009（BluetoothProfileDescriptorList）。这些平台缺口由会话内
 *   出站探测（连车机公告的 Sink 通道）继续兜底，本类只解决"被发现"。
 *
 * ## 与会话内 listener 的关系
 *
 * 常驻发布在线时，:protocol-carplay 的 Bootstrap 通过 [BluetoothRoleGate]
 * 感知状态并跳过会话内同名 listener 注册，避免同 UUID 双记录；出站探测不变。
 * 会话结束后常驻发布保持在线，供下一次会话与车机重复发现。
 *
 * ## 入站连接接收（866F 原生路径）
 *
 * 866F 的正常流程是**车机主动连接手机的 WiAP Source 通道**（而非手机连车机
 * Sink）。本类在发布记录的同时**常驻 accept**：车机连入的连接被缓存为
 * "入站预取 socket"，会话启动时经 [BluetoothRoleGate.takePendingInbound]
 * 优先取用，直接进入 iAP 握手。
 */
@SuppressLint("MissingPermission")
object WiapSourcePublisher {
    private val LEGACY = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    private val V2 = UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77")
    private val LEGACY_NAME = "Wireless iAP"
    private val V2_NAME = "Wireless iAP v2"

    @Volatile private var legacySocket: BluetoothServerSocket? = null
    @Volatile private var v2Socket: BluetoothServerSocket? = null
    private val started = AtomicBoolean(false)

    /** 车机主动连入、尚未被会话取用的 socket。 */
    @Volatile private var pendingInbound: BluetoothSocket? = null
    private val pendingLock = Any()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    BridgeStore.log("", "Persistent WiAP source: Bluetooth ON, (re)registering records")
                    ensureActive(context)
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
                    releaseAll("bluetooth off")
            }
        }
    }

    /** Application 启动时调用一次：注册蓝牙状态监听并立即尝试激活。 */
    @Synchronized
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        app.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        BluetoothRoleGate.persistentSourceActive = { active() }
        BluetoothRoleGate.takePendingInbound = { takePending() }
        BridgeStore.log("", "Persistent WiAP source publisher started; waiting for Bluetooth")
        ensureActive(app)
    }

    /**
     * 幂等激活：蓝牙未开/缺权限时记日志并等待下一次触发（蓝牙开启广播、
     * MainActivity 回到前台）。已注册的 socket 保持不动。
     */
    @Synchronized
    fun ensureActive(context: Context) {
        if (legacySocket != null && v2Socket != null) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            BridgeStore.log("", "Persistent WiAP source inactive: Bluetooth off")
            return
        }
        if (!Permissions.granted(context)) {
            BridgeStore.log("", "Persistent WiAP source inactive: required permissions missing")
            return
        }
        if (legacySocket == null) {
            legacySocket = runCatching {
                adapter.listenUsingRfcommWithServiceRecord(LEGACY_NAME, LEGACY)
            }.onSuccess {
                BridgeStore.log("", "Persistent WiAP source LEGACY (cafe) publication active; car discovery will hit")
                spawnAcceptLoop(it)
            }.onFailure {
                BridgeStore.log("", "Persistent WiAP source LEGACY registration failed: ${it.message}")
            }.getOrNull()
        }
        if (v2Socket == null) {
            v2Socket = runCatching {
                adapter.listenUsingRfcommWithServiceRecord(V2_NAME, V2)
            }.onSuccess {
                BridgeStore.log("", "Persistent WiAP source V2 (0a77) publication active")
                spawnAcceptLoop(it)
            }.onFailure {
                BridgeStore.log("", "Persistent WiAP source V2 registration failed: ${it.message}")
            }.getOrNull()
        }
    }

    /**
     * 常驻 accept 循环：车机主动连接 WiAP Source 通道时接住连接并缓存，
     * 供下一次会话优先取用。server socket 关闭（releaseAll）会令 accept
     * 抛异常而退出循环。
     */
    private fun spawnAcceptLoop(socket: BluetoothServerSocket) {
        val thread = Thread {
            // server socket 被 close（releaseAll / 蓝牙关闭）时 accept 抛异常 → 退出循环。
            while (true) {
                val accepted = runCatching { socket.accept() }.getOrNull() ?: break
                if (accepted.remoteDevice.address.isNullOrEmpty()) {
                    runCatching { accepted.close() }
                    continue
                }
                synchronized(pendingLock) {
                    pendingInbound?.takeIf { it.isConnected }?.let { runCatching { it.close() } }
                    pendingInbound = accepted
                }
                BridgeStore.log(
                    "",
                    "Persistent WiAP source accepted inbound connection from ${accepted.remoteDevice.address}; " +
                        "queued for session (866F native path)"
                )
            }
        }
        thread.isDaemon = true
        thread.name = "wiap-accept"
        thread.start()
    }

    /** 取走车机入站预取 socket（未连接/不存在则返回 null）。幂等，取走后清空槽位。 */
    fun takePending(): BluetoothSocket? = synchronized(pendingLock) {
        val s = pendingInbound
        pendingInbound = null
        s?.takeIf { it.isConnected }
    }

    /** 至少一条 WiAP Source 记录在线（可被 SDP 命中）。 */
    @Synchronized
    fun active(): Boolean = legacySocket != null || v2Socket != null

    @Synchronized
    fun releaseAll(reason: String) {
        runCatching { legacySocket?.close() }
        runCatching { v2Socket?.close() }
        legacySocket = null
        v2Socket = null
        synchronized(pendingLock) {
            pendingInbound?.let { runCatching { it.close() } }
            pendingInbound = null
        }
        BridgeStore.log("", "Persistent WiAP source publication released ($reason)")
    }
}
