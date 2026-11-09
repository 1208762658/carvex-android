package io.github.iamr0s.carvex.sessioncore

import android.bluetooth.BluetoothSocket

/**
 * 蓝牙角色发布状态的进程级门控。
 *
 * CarVeX 的 WiAP Source 记录（`…cafe` / `…0a77`）由 :app 层的常驻发布器
 * （WiapSourcePublisher）在进程生命周期内保持注册，这样 866F 一类"发现驱动"
 * 车机在蓝牙链路建立后立即执行的 SDP 扫描（0.3~1s 窗口）能命中手机记录。
 *
 * 协议/编排模块（:protocol-carplay / :bridge）不依赖 :app，因此通过本门控读取
 * "常驻 Source 发布是否在线"：在线时会话内不再重复注册同名 UUID 的 listener，
 * 避免 Android 公共 API 创建两条同 UUID 记录（两个不同 SCN）让车机取到错误通道。
 */
object BluetoothRoleGate {
    /** 由 :app 在启动时注入：返回常驻 WiAP Source 发布器当前是否持有在线 server socket。 */
    @Volatile
    var persistentSourceActive: (() -> Boolean)? = null

    /**
     * 由 :app 在启动时注入：取走常驻发布器 accept 到的车机入站连接（没有则返回 null）。
     * 866F 一类车机的原生路径是"车机主动连接手机的 WiAP Source 通道"；
     * 常驻发布器一直 accept，会话启动时优先使用这条已建立的连接。
     */
    @Volatile
    var takePendingInbound: (() -> BluetoothSocket?)? = null

    /** 常驻 WiAP Source 记录当前是否可被对端 SDP 搜索命中。 */
    fun sourcePublished(): Boolean = persistentSourceActive?.invoke() == true

    /** 供测试/诊断重置。 */
    fun resetForTest() {
        persistentSourceActive = null
        takePendingInbound = null
    }
}
