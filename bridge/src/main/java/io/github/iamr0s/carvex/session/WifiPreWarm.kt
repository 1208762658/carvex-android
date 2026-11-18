package io.github.iamr0s.carvex.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import io.github.iamr0s.carvex.sessioncore.BridgeStore

/**
 * 息屏重连的 Wi-Fi 预热门。
 *
 * 依据 5.2device-log.txt（20086，2026-11-18）：
 *   - 三次 attempt 启动瞬间都是 `wifi observation: wifi=absent ip=none (active=cellular)`；
 *   - 在 Wi-Fi absent 状态下发起 RFCOMM 的那次，车机 11.3s 后回
 *     `read failed, socket might closed or timeout, read ret: -1`（peer-rejected），
 *     紧接着 `ACL_DISCONNECTED`，raw channel 1..6 兜底全部失败，整次 attempt 报废；
 *   - Wi-Fi 已在的两次 attempt，`peer matched` 只用 0.29s / 0.48s，DETECT 零超时。
 *
 * 所以只做一件事：出站前等"WLAN 真正拿到 IPv4 链路"，最多 8 秒；超时也放行，
 * 只记录不阻断（与 BluetoothReadyGate 同样的观测优先原则）。
 * 开关：setprop carvex.wifi.preWarm 0 关闭。
 */
internal object WifiPreWarm {

    private fun prop(key:String,def:String):String = runCatching {
        val c=Class.forName("android.os.SystemProperties")
        c.getMethod("get",String::class.java,String::class.java).invoke(null,key,def) as String
    }.getOrDefault(def)

    val enabled:Boolean get() = prop("carvex.wifi.preWarm","1")!="0"

    private fun wifiV4(context:Context):Pair<Boolean,Int>{
        val cm=context.getSystemService(ConnectivityManager::class.java)?:return false to -1
        val active=cm.activeNetwork
        val caps=active?.let{cm.getNetworkCapabilities(it)}
        if(active==null||caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return false to 0
        val lp=cm.getLinkProperties(active)
        val v4=lp?.linkAddresses?.count{it.address is java.net.Inet4Address}?:0
        return true to v4
    }

    fun await(context:Context,id:String,timeoutMs:Long){
        if(!enabled){BridgeStore.log(id,"wifi prewarm: disabled by carvex.wifi.preWarm; bluetooth may outrun WLAN");return}
        val started=SystemClock.elapsedRealtime()
        var lastOnWifi:Boolean?=null
        while(true){
            val (onWifi,v4)=wifiV4(context)
            if(onWifi&&v4>0){
                BridgeStore.log(id,"wifi prewarm: ready wifi=true ipv4="+v4+" waitedMs="+(SystemClock.elapsedRealtime()-started))
                return
            }
            if(lastOnWifi!=onWifi){
                lastOnWifi=onWifi
                BridgeStore.log(id,"wifi prewarm: waiting wifi="+onWifi+" ipv4="+v4+
                    " waitedMs="+(SystemClock.elapsedRealtime()-started))
            }
            if(SystemClock.elapsedRealtime()-started>=timeoutMs){
                BridgeStore.log(id,"wifi prewarm: timeout after "+timeoutMs+"ms wifi="+onWifi+" ipv4="+v4+
                    "; proceeding anyway (5.2 evidence: car drops ACL when WLAN is absent)")
                return
            }
            runCatching{Thread.sleep(200)}
        }
    }
}
