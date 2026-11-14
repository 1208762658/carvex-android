package io.github.iamr0s.carvex.session

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import io.github.iamr0s.carvex.sessioncore.BridgeStore

/**
 * 会话期间的息屏保活。
 *
 * 现有保活只有 CarVexForegroundService：前台服务抬的是"进程优先级"，它不阻止 CPU 休眠。
 * 手机息屏后内核进 suspend、系统进 Doze，iAP2/RFCOMM/RTSP 那些线程全部停摆，WiFi 也会被
 * 挂起 —— 表现就是"亮屏时一直好，息屏一阵就断"。
 *
 * 这里补两个锁，只在会话存活期间持有：
 *  - PARTIAL_WAKE_LOCK：让 CPU 不休眠，socket 的读超时/timer 才有机会跑；
 *  - WIFI_MODE_FULL_HIGH_PERF：让 WiFi 射频不跟着屏幕降档。真 iPhone 无线 CarPlay 同样
 *    保持这一档，否则 AirPlay 分片会积压。
 *
 * 不设超时、不做引用计数：锁随进程死亡自动释放，所以崩溃/被杀不会留下泄漏；正常路径由
 * SessionController 的 finally 释放。空闲无会话时一把都不持有，待机耗电不受影响。
 */
internal object SessionWakeLock {
    private var cpu:PowerManager.WakeLock?=null
    private var wifi:WifiManager.WifiLock?=null

    fun acquire(context:Context, id:String) {
        release(id, "reacquire")
        val pm=context.getSystemService(PowerManager::class.java)
        val wm=context.applicationContext.getSystemService(WifiManager::class.java)
        val cpuLock=runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carvex:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { BridgeStore.log(id,"wakelock cpu failed: "+it.javaClass.simpleName+" "+(it.message?:"") ) }.getOrNull()
        // HIGH_PERF 在 API 29 之后被标废弃但仍是最高 WiFi 档；老系统上不支持时退回 FULL。
        val wifiLock=runCatching {
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "carvex:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.recoverCatching {
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "carvex:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { BridgeStore.log(id,"wakelock wifi failed: "+it.javaClass.simpleName+" "+(it.message?:"")) }.getOrNull()
        cpu=cpuLock
        wifi=wifiLock
        BridgeStore.log(id,"wakelock acquired cpu="+(cpuLock?.isHeld==true)+" wifi="+(wifiLock?.isHeld==true)+" so the session survives screen-off")
    }

    fun release(id:String, reason:String="session closed") {
        runCatching { cpu?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        val heldCpu=cpu!=null; val heldWifi=wifi!=null
        cpu=null
        wifi=null
        if(heldCpu||heldWifi) BridgeStore.log(id,"wakelock released ("+reason+")")
    }
}
