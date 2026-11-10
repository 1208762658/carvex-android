package io.github.iamr0s.carvex.carplay

import android.bluetooth.BluetoothAdapter
import kotlinx.coroutines.delay

/**
 * 866F 回连的唯一实测有效手段：把手机蓝牙关掉、停一会儿、再开。
 *
 * 三份现场日志给出的是同一个结论：
 * - 20003：一条会话断掉后，04:33:40–04:35:39 连续 11 次重连全部 `read ret: -1`，
 *   耗时 33–953ms，中间隔过 86 秒也没用；
 * - 20003.1：蓝牙+Wi-Fi 关约 1 分钟再开，04:42:19 起的第一次尝试就成功，握手 7.04s，
 *   随后连跑 1 分 55 秒；
 * - 20003.2：关约 30 秒再开，前两次仍失败（447ms / 4.9s），第三次 388ms 成功。
 *
 * 失败不是"栈没就绪"（ready gate 每次都 passed waitedMs=0），而是那条已经断掉的
 * ACL/RFCOMM 还挂在车机侧；只有本端蓝牙整体关闭，车机才会感知链路消失并清掉会话状态。
 *
 * 设计约束（都是现场教训）：
 * - 连续失败 2 次才动手，第一次失败照旧快速返回，不拖慢正常连接；
 * - 全局冷却 90s，绝不自己重入；
 * - disable/enable 需要系统级权限，拿不到就记一行日志并永久停用，绝不反复尝试；
 * - 只动蓝牙，不改协议、不碰配对记录（配对必须保留）。
 */
object BluetoothSoftReset {
    /** 触发阈值：同一进程内连续这么多次"全部候选失败"才考虑重置。 */
    private const val FAILURES_BEFORE_RESET=2
    /** 两次重置之间的最小间隔。 */
    private const val COOLDOWN_MS=90_000L
    /** 关闭后保持多久：>=30s 即可，日志里 30s 与 60s 都成功过。 */
    private const val HOLD_MS=30_000L
    /** 等 OFF / 等 ON 的上限。 */
    private const val OFF_TIMEOUT_MS=8_000L
    private const val ON_TIMEOUT_MS=12_000L

    private val lock=Any()
    private var consecutiveFailures=0
    private var lastReserveAt=0L
    /** disable() 被系统拒绝过一次之后就不再尝试（普通应用没有改蓝牙状态的权力）。 */
    private var privileged=false
    private var privilegedKnown=false

    /** 每次"出站全部候选失败"调用一次。 */
    fun noteFailure()=synchronized(lock){consecutiveFailures++}

    /** 连上之后清计数，避免把下一次正常断线误判成"连着失败"。 */
    fun noteSuccess()=synchronized(lock){consecutiveFailures=0}

    /**
     * 需要动手时调用。true 表示这次确实执行了关闭→保持→打开，
     * 调用方可以期待下一次尝试回到正常握手时长。
     */
    suspend fun reset(adapter:BluetoothAdapter,log:(String)->Unit):Boolean{
        val permit=synchronized(lock){
            val now=android.os.SystemClock.elapsedRealtime()
            when{
                privilegedKnown&&!privileged->false
                consecutiveFailures<FAILURES_BEFORE_RESET->false
                now-lastReserveAt<COOLDOWN_MS->{
                    // 冷却期内不打印，避免刷屏
                    false
                }
                else->{
                    lastReserveAt=now;consecutiveFailures=0;true
                }
            }
        }
        if(!permit){
            synchronized(lock){
                if(privilegedKnown&&!privileged)return false
            }
            log("bluetooth auto reset: skipped (attempts="+consecutiveFailures+
                " cooldownRemainingMs="+(COOLDOWN_MS-(android.os.SystemClock.elapsedRealtime()-lastReserveAt))+")")
            return false
        }
        val started=android.os.SystemClock.elapsedRealtime()
        if(adapter.state!=BluetoothAdapter.STATE_ON){
            log("bluetooth auto reset: skipped, adapter state="+stateName(adapter.state))
            return false
        }
        val disabled=runCatching{adapter.disable()}
        if(disabled.isFailure||disabled.getOrNull()!=true){
            val reason=disabled.exceptionOrNull()?.let{it.javaClass.simpleName+": "+(it.message?:"")}
                ?:"disable() returned false"
            log("bluetooth auto reset: unavailable ($reason); no privilege to change adapter state, staying off")
            synchronized(lock){privileged=false;privilegedKnown=true}
            return false
        }
        synchronized(lock){privileged=true;privilegedKnown=true}
        log("bluetooth auto reset: disable requested; hold="+HOLD_MS+"ms")
        if(!awaitState(adapter,BluetoothAdapter.STATE_OFF,OFF_TIMEOUT_MS)){
            log("bluetooth auto reset: adapter never reached OFF within "+OFF_TIMEOUT_MS+"ms; aborting")
            return false
        }
        log("bluetooth auto reset: adapter OFF")
        delay(HOLD_MS)
        var enabled=runCatching{adapter.enable()}.getOrDefault(false)
        if(!enabled){
            // 关掉之后必须尽力打开，否则用户整块蓝牙都没了。
            delay(1_000)
            enabled=runCatching{adapter.enable()}.getOrDefault(false)
        }
        if(!enabled){
            log("bluetooth auto reset: enable refused twice; adapter stays OFF (manual recovery required)")
            return false
        }
        log("bluetooth auto reset: enable requested")
        if(!awaitState(adapter,BluetoothAdapter.STATE_ON,ON_TIMEOUT_MS)){
            log("bluetooth auto reset: adapter never reached ON within "+ON_TIMEOUT_MS+"ms")
            return false
        }
        // STATE_ON 立刻重连仍会撞上"栈刚起来"的窗口，给一个短 grace。
        delay(2_000)
        log("bluetooth auto reset: done in "+(android.os.SystemClock.elapsedRealtime()-started)+"ms; ready for retry")
        return true
    }

    private suspend fun awaitState(adapter:BluetoothAdapter,target:Int,timeoutMs:Long):Boolean{
        var waited=0L
        while(waited<timeoutMs){
            if(adapter.state==target)return true
            delay(500);waited+=500
        }
        return adapter.state==target
    }

    private fun stateName(state:Int)=when(state){
        BluetoothAdapter.STATE_OFF->"OFF"
        BluetoothAdapter.STATE_TURNING_ON->"TURNING_ON"
        BluetoothAdapter.STATE_ON->"ON"
        BluetoothAdapter.STATE_TURNING_OFF->"TURNING_OFF"
        else->"UNKNOWN($state)"
    }
}
