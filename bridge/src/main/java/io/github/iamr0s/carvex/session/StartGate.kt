package io.github.iamr0s.carvex.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import kotlinx.coroutines.delay

/**
 * 启动前闸门（UI 可配）。
 *
 * 背景（5.2device-log.txt，20086，2026-11-18）：CarWith 检测到蓝牙连接后会把 CarVeX 拉起来，
 * 并由它自己去连车机热点。CarVeX 侧一上来就发起 RFCOMM 时，日志里是
 *   `wifi observation: wifi=absent ip=none (active=cellular)`，
 * 车机在 WLAN 不可达的前提下 11.3s 后回 `read failed ... peer-rejected`，
 * 紧接着 `ACL_DISCONNECTED`，raw channel 1..6 兜底全部失效。
 * 而 Wi-Fi 已在的两次 attempt，`peer matched` 只要 0.29/0.48s。
 *
 * 所以这里提供两个互相独立的选项（都只等在启动阶段，不触碰 CarWith 的热点关联流程）：
 *  1. start_gate  ：等到"蓝牙 ACL 已连接 + WLAN 已拿到 IPv4"才发起连接；超时放行只记录；
 *  2. start_delay ：被拉起后先静默等待 N 秒再走原流程（N=0 表示不延时）。
 *
 * 开关用应用内的 Settings 页，值存在与 UI 同一个 SharedPreferences（"settings"）。
 * 也支持系统属性覆盖，便于现场排查：
 *   carvex.startGate=0 / carvex.startDelay=0 / carvex.startGate.timeoutMs=15000
 */
internal object StartGate {

    const val PREF = "settings"
    const val KEY_GATE = "start_gate"
    const val KEY_DELAY = "start_delay"
    const val MAX_DELAY_SEC = 30
    private const val POLL_MS = 200L
    private const val DEFAULT_TIMEOUT_MS = 15_000L

    private fun sysProp(key: String, def: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, key, def) as String
    }.getOrDefault(def)

    fun gateEnabled(context: Context): Boolean {
        val forced = sysProp("carvex.startGate", "")
        if (forced.isNotEmpty()) return forced != "0"
        return runCatching { prefs(context).getBoolean(KEY_GATE, true) }.getOrDefault(true)
    }

    fun delaySeconds(context: Context): Int {
        val forced = sysProp("carvex.startDelay", "").toIntOrNull()
        val value = forced ?: runCatching { prefs(context).getInt(KEY_DELAY, 0) }.getOrDefault(0)
        return value.coerceIn(0, MAX_DELAY_SEC)
    }

    private fun timeoutMs(): Long =
        sysProp("carvex.startGate.timeoutMs", "").toLongOrNull()?.coerceIn(1_000L, 60_000L)
            ?: DEFAULT_TIMEOUT_MS

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 蓝牙侧：adapter 已开，且目标车机的 ACL 处于连接态。 */
    private fun bluetoothState(context: Context, target: String): Pair<Boolean, String> {
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        }.getOrNull() ?: return false to "no-adapter"
        if (adapter.state != BluetoothAdapter.STATE_ON) return false to "off"
        if (!BluetoothAdapter.checkBluetoothAddress(target)) return false to "no-target"
        val device = runCatching { adapter.getRemoteDevice(target) }.getOrNull()
            ?: return false to "no-device"
        // isConnected 是 @hide API，系统签名可用；取不到就退回"已配对"判据，并如实标注。
        val connected = runCatching {
            val m = device.javaClass.getMethod("isConnected")
            m.invoke(device) as Boolean
        }.getOrNull()
        if (connected == null) {
            val bonded = runCatching { device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED }
                .getOrDefault(false)
            return bonded to "bonded-only"
        }
        return connected to (if (connected) "acl-connected" else "acl-idle")
    }

    /** Wi-Fi 侧：当前默认网络是 WLAN 且已拿到 IPv4 租约。 */
    private fun wifiState(context: Context): Pair<Boolean, String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false to "no-cm"
        val active = cm.activeNetwork ?: return false to "no-network"
        val caps = cm.getNetworkCapabilities(active) ?: return false to "no-caps"
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false to "not-wifi"
        val v4 = cm.getLinkProperties(active)?.linkAddresses?.count { it.address is java.net.Inet4Address } ?: 0
        return (v4 > 0) to ("wifi-ipv4=$v4")
    }

    /**
     * 在当前协程里等到可以发起连接。任何一项超时都放行（只记录），
     * 绝不让"门没开"变成"永远不连"。
     */
    suspend fun await(context: Context, id: String, target: String, fromHost: Boolean) {
        val delaySec = delaySeconds(context)
        if (delaySec > 0) {
            BridgeStore.log(id, "start gate: delay ${delaySec}s before connecting (setting start_delay)")
            val until = SystemClock.elapsedRealtime() + delaySec * 1000L
            while (SystemClock.elapsedRealtime() < until) delay(POLL_MS)
        }
        if (!gateEnabled(context)) {
            BridgeStore.log(id, "start gate: disabled; connecting immediately" +
                (if (fromHost) "" else " (manual attempt)"))
            return
        }
        val started = SystemClock.elapsedRealtime()
        val deadline = started + timeoutMs()
        var lastLabel: String? = null
        while (true) {
            val (bt, btLabel) = bluetoothState(context, target)
            val (wifi, wifiLabel) = wifiState(context)
            if (bt && wifi) {
                BridgeStore.log(id, "start gate: ready bt=$btLabel wifi=$wifiLabel waitedMs=" +
                    (SystemClock.elapsedRealtime() - started))
                return
            }
            val label = "bt=$btLabel wifi=$wifiLabel"
            if (label != lastLabel) {
                lastLabel = label
                BridgeStore.log(id, "start gate: waiting $label waitedMs=" +
                    (SystemClock.elapsedRealtime() - started))
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                BridgeStore.log(id, "start gate: timeout after " + timeoutMs() + "ms $label" +
                    "; proceeding anyway (5.2 evidence: car drops ACL when WLAN is absent)")
                return
            }
            delay(POLL_MS)
        }
    }
}
