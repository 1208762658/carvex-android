package io.github.iamr0s.carvex.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address

/**
 * 只做观测，不参与任何判定。
 *
 * 现场反馈"先连车机热点、再开蓝牙成功率会高一点"，但同样会失败，所以它是概率因子、
 * 不是充分条件。要判断它到底值多少，只能把每次尝试那一刻的 Wi-Fi 状态固定下来：
 * 成功与失败的样本都带同一个字段，才能把"偶然觉得高一点"变成可数的比例。
 *
 * 输出示例：
 * `wifi=joined ip=10.173.189.60 ssid="VW-5384" carSubnet=true`
 * `wifi=absent ip=none (active=cellular)`
 */
object WifiObservation {
    fun describe(context:Context):String{
        val cm=context.getSystemService(ConnectivityManager::class.java)
            ?:return "wifi=unknown"
        val network=cm.activeNetwork?:return "wifi=absent ip=none (no active network)"
        val caps=cm.getNetworkCapabilities(network)
        val isWifi=caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        if(!isWifi){
            val kind=when{
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->"cellular"
                else->"other"
            }
            return "wifi=absent ip=none (active=$kind)"
        }
        val ip=cm.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull{it.address is Inet4Address}?.address?.hostAddress
        val ssid=runCatching{
            @Suppress("DEPRECATION")
            context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        }.getOrNull()?.trim('"')
        return "wifi=joined ip="+(ip?:"none")+" ssid="+(ssid?:"none")+
            " carSubnet="+((ip!=null)&&ip.startsWith("10.173.189."))
    }
}
