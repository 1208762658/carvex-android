package io.github.iamr0s.carvex.carplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * 出站前的"蓝牙栈就绪门" + 失败指纹。
 *
 * 依据（400device-log.txt，v14.15 原包实测，2026-11-10）：
 *   - 9 次出站失败的 connect 全部在 91–110ms 内抛
 *     `read failed, socket might closed or timeout, read ret: -1`；
 *   - 成功那次握手用了 5.79s（00:16:09.105597 → 00:16:14.899852）；
 *   - 期间 BluetoothAdapter 实际在 off/on 重启了两轮：
 *     00:15:24.396 off → 00:15:26.806 on → 00:16:03.598 off → 00:16:09.080 on。
 *
 * 结论：100ms 级的 read ret:-1 不是句柄泄漏，是"栈还没起来就开连"。
 * 所以这里只做两件事，都不改变协议行为：
 *   1. awaitReady：等 STATE_ON 且目标设备 UUID 可读，才允许出站；
 *   2. classify：把失败按耗时/状态分成 stack-off / stack-not-ready / peer-rejected，
 *      让下一轮日志能直接看出"又撞窗口了"还是"车机真的拒了"。
 */
object BluetoothReadyGate {

    /** 首次出站前最多等多久（超时也放行，只记录，不阻断） */
    const val READY_TIMEOUT_MS = 8_000L

    private const val STABLE_POLL_MS = 200L

    /** 低于此耗时的 read ret:-1 判为"栈未就绪"（正常 RFCOMM 握手是秒级） */
    private const val FAST_FAIL_MS = 300L

    /** 开关：adb shell setprop carvex.bluetooth.readyGate 0 即回到 v14.15 的"立刻出站"行为 */
    private fun prop(key: String, def: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, def) as String
    }.getOrDefault(def)

    val enabled: Boolean get() = prop("carvex.bluetooth.readyGate", "1") != "0"

    fun stateLabel(adapter: BluetoothAdapter?): String = when (adapter?.state) {
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> "UNKNOWN"
    }

    /**
     * 等到"可以出站"为止。返回 true 表示已就绪；false 表示超时放行或 adapter 缺失。
     * 只在超时时返回 false，调用方仍然继续连接 —— 观测优先，不因为门没开就把连接掐掉。
     */
    suspend fun awaitReady(
        adapter: BluetoothAdapter?,
        device: BluetoothDevice?,
        timeoutMs: Long,
        log: (String) -> Unit,
    ): Boolean {
        if (adapter == null) {
            log("bluetooth ready gate: skipped adapter=unavailable")
            return false
        }
        if (!enabled) {
            log("bluetooth ready gate: disabled by carvex.bluetooth.readyGate; outbound proceeds immediately")
            return false
        }
        val startedAt = SystemClock.elapsedRealtime()
        val until = startedAt + timeoutMs
        var lastReported = Int.MIN_VALUE

        while (true) {
            val state = adapter.state
            val uuids = runCatching { device?.uuids }.getOrNull()
            val uuidCount = uuids?.size ?: -1

            if (state == BluetoothAdapter.STATE_ON && uuids != null && uuids.isNotEmpty()) {
                log("bluetooth ready gate: passed state=ON uuidCount=$uuidCount waitedMs=" +
                    (SystemClock.elapsedRealtime() - startedAt))
                return true
            }

            if (state != lastReported) {
                lastReported = state
                log("bluetooth ready gate: waiting state=" + stateLabel(adapter) +
                    " uuidCount=$uuidCount waitedMs=" + (SystemClock.elapsedRealtime() - startedAt))
            }

            if (SystemClock.elapsedRealtime() >= until) {
                log("bluetooth ready gate: timeout after ${timeoutMs}ms state=" + stateLabel(adapter) +
                    " uuidCount=$uuidCount; proceeding anyway")
                return false
            }
            delay(STABLE_POLL_MS)
        }
    }

    /**
     * 给失败分类，直接写进日志。取值：
     *   stack-off        蓝牙状态不是 ON（栈正在关/开）
     *   stack-not-ready  报 read ret:-1 但耗时 <300ms（栈起来了但 RFCOMM 层还没就绪）
     *   peer-rejected    报 read ret:-1 且耗时正常（>300ms，对端/链路真的拒了）
     *   other            其它异常
     */
    fun classify(elapsedMs: Long, cause: Throwable?, adapter: BluetoothAdapter?): String {
        val message = cause?.message.orEmpty()
        val deadSocket = message.contains("read ret: -1", ignoreCase = true) ||
            message.contains("socket might closed", ignoreCase = true) ||
            message.contains("bt socket closed", ignoreCase = true)

        val stateNotOn = adapter == null || adapter.state != BluetoothAdapter.STATE_ON

        return when {
            stateNotOn && deadSocket -> "stack-off"
            deadSocket && elapsedMs < FAST_FAIL_MS -> "stack-not-ready"
            deadSocket -> "peer-rejected"
            else -> "other"
        }
    }
}
