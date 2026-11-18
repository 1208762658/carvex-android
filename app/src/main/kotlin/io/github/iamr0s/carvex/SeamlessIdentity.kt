package io.github.iamr0s.carvex

import android.bluetooth.BluetoothAdapter
import android.content.Context
import io.github.iamr0s.carvex.sessioncore.BridgeStore

/**
 * 无感连接实验：把本机在车机侧的身份改成 iPhone。
 *
 * ## 依据（5.4 实机日志，bee2bae1）
 *
 * 车机 App-Connect 里我们的条目是 `REDMI K90 Pro Max`，下面挂一个独立的
 * `Apple CarPlay` 按钮，用户必须手点它，车机才在 **04:54:50.088 主动重启
 * iAP link**（`iAP link restart epoch=2`），重启后 Identify 才变成
 * `messageFeatures=[22] wifiAnnouncement=true`，进入无线 CarPlay 模式。
 *
 * 反证：我们在此之前已经发过 0x4e0d / 0x4e0e / 0x4e0b / 0x4300，车机一律
 * 无反应；点击前后车机发来的消息序列也没有新增。⇒ 车机"是否进无线模式"
 * 不取决于手机发了什么命令，而取决于它把我们归到哪一类设备。
 *
 * 唯一还没被排除的可控变量，就是**上报名**：866F 的 App-Connect 用蓝牙名
 * 归类设备，iPhone 走"自动/免选"路径，其他手机走"手动选 CarPlay"路径。
 *
 * ## 为什么只改名字（单一变量）
 *
 * 不动协议、不动广播内容、不动 GATT 服务：改名是成本最低、可逆的一步。
 * 若无效，下一版再把 `caff` SDP 记录 / BLE WiAP 记录加进实验组。
 *
 * ## 安全边界
 *
 * 改名走系统特权 API，任何异常都必须吞掉并落日志（[GlobalRule 4]：宁可
 * 功能失效，不允许未捕获异常把进程带崩）。
 */
object SeamlessIdentity {
    /** 上报名。只用 "iPhone"，不带型号：带型号容易被车机当作特定机型校验。 */
    const val IPHONE_NAME = "iPhone"

    private const val PREF = "settings"
    private const val KEY = "seamless_iphone"

    /** 开关默认开：本版就是拿它做实验，用户可在设置页一键关掉。 */
    fun enabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }.getOrDefault(true)

    /**
     * 把蓝牙适配器名字改成 iPhone（幂等：已经是目标名就跳过，避免每次
     * 蓝牙事件都写一次导致车机侧一直在做 SDP 重扫）。
     *
     * 返回是否处于 iPhone 身份，供上层日志说明"车机看到的到底是谁"。
     */
    fun applyBluetoothName(context: Context, adapter: BluetoothAdapter?, id: String = ""): Boolean {
        if (!enabled(context)) {
            BridgeStore.log(id, "[carvex.seamless] identity switch off; keeping host Bluetooth name")
            return false
        }
        if (adapter == null || !adapter.isEnabled) {
            BridgeStore.log(id, "[carvex.seamless] identity pending: Bluetooth adapter unavailable/off")
            return true
        }
        val current = runCatching { adapter.name }.getOrNull()
        if (current == IPHONE_NAME) {
            BridgeStore.log(id, "[carvex.seamless] identity stable: Bluetooth name already \"$IPHONE_NAME\"")
            return true
        }
        val ok = runCatching { adapter.name = IPHONE_NAME; true }.getOrElse { error ->
            BridgeStore.log(id, "[carvex.seamless] identity rejected: setName failed ${error.javaClass.simpleName}: ${error.message}")
            false
        }
        if (!ok) return false
        val now = runCatching { adapter.name }.getOrNull()
        BridgeStore.log(
            id,
            "[carvex.seamless] identity applied: Bluetooth name \"$current\" -> \"$now\" " +
                "(car App-Connect should classify this device as iPhone)",
        )
        return now == IPHONE_NAME
    }
}
