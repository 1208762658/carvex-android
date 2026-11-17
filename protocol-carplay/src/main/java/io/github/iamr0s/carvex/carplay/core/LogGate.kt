package io.github.iamr0s.carvex.carplay.core

/**
 * 日志降噪闸门。4.6 一次驾驶 7025 行，光 `Control exchange phase` 就 1626 行，
 * 真正有用的（artwork / inbound / ack）被淹掉了。
 *
 * 默认安静（quiet=1）：丢掉"每帧/每事件"级别的心跳类日志；
 * 需要逐帧对齐协议时，设置系统属性 `carvex.log.quiet=0` 即可恢复全量。
 * 只降噪、不改变任何业务行为——日志缺失绝不会影响会话。
 */
object LogGate {
    private val quiet: Boolean = System.getProperty("carvex.log.quiet")?.trim() != "0"

    /** 逐帧/逐事件类：默认丢弃。 */
    fun dropPerFrame(message: String): Boolean {
        if (!quiet) return false
        return message.startsWith("Control exchange phase")
            || message.startsWith("RTSP event method")
            || message.startsWith("OBSERVE rtsp command type")
            || message.startsWith("Event notification omitted")
    }
}
