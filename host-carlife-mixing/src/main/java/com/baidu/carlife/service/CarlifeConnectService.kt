package com.baidu.carlife.service
import android.app.*
import android.content.*
import android.os.*
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceDelegate
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceOwner
import io.github.iamr0s.carvex.host.mixing.service.MixingServiceSession
import kotlinx.coroutines.*
import java.util.UUID

class CarlifeConnectService:Service() {
    private lateinit var delegate:MixingServiceDelegate
    private val controller:MixingServiceSession get()=delegate.session
    private val binder=Binder()
    private var disconnectJob:Job?=null
    private var sessionJob:Job?=null
    private val prefs:android.content.SharedPreferences get()=getSharedPreferences("carvex_entry",android.content.Context.MODE_PRIVATE)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    override fun onCreate(){
        super.onCreate()
        val owner=application as? MixingServiceOwner
            ?: error("Application must implement MixingServiceOwner")
        delegate=owner.createMixingServiceDelegate(this)
        activeInstance=this
    }
    override fun onBind(intent:Intent?):IBinder=binder // KeepAlive does not establish a session.
    /** 只记录宿主 extras 的 key 和类型，不记录值。 */
    @Suppress("DEPRECATION")
    private fun Intent.describeExtras():String {
        val extras=extras?:return ""
        return extras.keySet().filterNot{it=="internal_token"}.joinToString(","){key->key+"="+(extras.get(key)?.javaClass?.simpleName?:"null")}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        // 落在任何提前返回之前：被拒的请求也要留痕。
        delegate.log("entry","Intent extras: "+(intent?.describeExtras()?:"none"))
        val internal=intent?.getStringExtra("internal_token")==token(this)
        if(internal&&intent?.action==STOP){
            // 用户主动断开：清除“异常中断”恢复标记，避免重启后被自动恢复。
            prefs.edit().remove(KEY_INTERRUPTED).apply()
            if(disconnectJob?.isActive!=true)disconnectJob=scope.launch{
                try{sessionJob?.cancelAndJoin();controller.disconnect()}
                catch(t:CancellationException){throw t}
                catch(t:Exception){delegate.log("entry","Local disconnect failed "+t.javaClass.simpleName+" "+(t.message?:""))}
                finally{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            }
            return START_NOT_STICKY
        }
        if(disconnectJob?.isActive==true){delegate.log("entry","Connection request ignored while disconnecting");return START_NOT_STICKY}
        val hostRequest = delegate.brand.acceptsHostIntent(
            intent?.getStringExtra("bindPackageName"),
            intent?.getStringExtra("bindClassName"),
            intent?.getStringExtra("manufacturer"),
            intent?.getIntExtra("usb_type", -1) ?: -1,
            intent?.getBooleanExtra("is_box", false) ?: false,
        )
        val interrupted=prefs.getBoolean(KEY_INTERRUPTED,false)
        // v14.12 (2026-11-03)：90device-log 复现 CarWith（小米车联）打开时系统重启 CarVeX
        // 进程——健康 CarPlay 会话连同蓝牙 socket 被销毁、866F 蓝牙 bond 失效（需重新配对）。
        // 重启后如果 intent 为 null（START_STICKY 重建）或任何有效入口到达，且存在中断标记，
        // 自动恢复会话（fromHost=false=App 模式 CarPlay，而非 CarWith 接管），配合
        // Bootstrap 的自动 re-bond 与蓝牙失败持续重试，让“关掉 CarWith 后自动重连”。
        if(!((intent!=null&&(internal&&intent.action==CONNECT))||hostRequest||(intent==null&&interrupted))){
            delegate.log("entry","Unsupported or invalid service entry")
            if(!controller.active&&sessionJob?.isActive!=true)stopSelf(startId)
            return START_NOT_STICKY
        }
        val missing=delegate.missingPermissions()
        if(missing.isNotEmpty()){delegate.log("entry","Required permission missing: "+missing.joinToString());stopSelf(startId);return START_NOT_STICKY}
        try{startForeground(1,notification(if(interrupted)"自动恢复连接" else "准备连接"))}catch(t:Exception){delegate.log("entry","Foreground start failed "+t.javaClass.simpleName);stopSelf(startId);return START_NOT_STICKY}
        if(interrupted&&hostRequest){
            // CarWith 打开触发了 hostRequest，但之前会话是被中断的 CarPlay：恢复 App 模式，
            // 不让 CarWith 接管（用户场景=打开 CarWith 后 CarPlay 断联，期望恢复 CarPlay）。
            delegate.log("entry","Auto-recover CarPlay session interrupted by process death; ignoring CarWith host takeover")
            if(!controller.active&&sessionJob?.isActive!=true)startSession(false)
        }else if(!controller.active&&sessionJob?.isActive!=true)startSession(hostRequest)
        // 有效入口使用粘性启动：系统/进程被杀后重建服务，配合中断标记自动恢复。
        return START_STICKY
    }
    /** The application owns preparation and session implementation; the service owns their lifetime. */
    private fun startSession(hostRequest:Boolean){
        sessionJob=scope.launch{
            try {
                controller.start(hostRequest)
                controller.status.distinctUntilChanged().collect{status->
                    // 会话已启动（含自动恢复）：清除中断标记，避免下次进程死亡时误恢复已结束的会话。
                    if(controller.active)prefs.edit().remove(KEY_INTERRUPTED).apply()
                    getSystemService(NotificationManager::class.java).notify(1,notification(status.text))
                    if(status.failed){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
                }
            }catch(t:CancellationException){throw t}
            catch(t:Exception){
                delegate.log("entry","Session start failed "+t.javaClass.simpleName+" "+(t.message?:""))
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }
        }
    }
    private fun notification(text:String):Notification {
        val stop=PendingIntent.getService(this,1,Intent(this,CarlifeConnectService::class.java).setAction(STOP).putExtra("internal_token",token(this)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return delegate.notification(text,stop)
    }
    override fun onDestroy(){
        if(activeInstance===this)activeInstance=null
        // v14.12：非用户主动断开时进程/服务死亡 → 记中断标记，供重启后自动恢复。
        if(controller.active||sessionJob?.isActive==true){
            prefs.edit().putBoolean(KEY_INTERRUPTED,true).apply()
            delegate.log("entry","Session interrupted by service/process death; recovery marker set")
        }
        scope.cancel()
        try{if(::delegate.isInitialized)controller.close()}finally{super.onDestroy()}
    }
    companion object {
        const val CONNECT="io.github.iamr0s.carvex.CONNECT";const val STOP="io.github.iamr0s.carvex.STOP"
        private const val KEY_INTERRUPTED="interrupted_session"
        @Synchronized private fun token(c:Context):String {
            val p=c.getSharedPreferences("entry",Context.MODE_PRIVATE)
            return p.getString("token",null)?:UUID.randomUUID().toString().also{p.edit().putString("token",it).apply()}
        }
        fun connect(c:Context){c.startForegroundService(Intent(c,CarlifeConnectService::class.java).setAction(CONNECT).putExtra("internal_token",token(c)))}
        fun disconnect(c:Context){c.startService(Intent(c,CarlifeConnectService::class.java).setAction(STOP).putExtra("internal_token",token(c)))}
        @Volatile private var activeInstance:CarlifeConnectService?=null
        fun setVideoBitrate(value:Int){activeInstance?.controller?.setVideoBitrate(value)}
        /** 手动模拟触发一个 CarWith 键码（按键调节区的“模拟”按钮）。 */
        fun triggerCarWithKey(code:Int){activeInstance?.controller?.sendHostKey(code)}
        fun startCarWithAudioProbe(){
            activeInstance?.let{it.controller.startHostAudioProbe()}
        }
        fun toggleCarPlayAudioTest(){
            activeInstance?.let{it.controller.toggleProtocolAudioTest()}
        }
    }
}
