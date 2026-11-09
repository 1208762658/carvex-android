package io.github.iamr0s.carvex.carplay
import io.github.iamr0s.carvex.sessioncore.*

import io.github.iamr0s.carvex.carplay.core.*
import io.github.iamr0s.carvex.carplay.core.carplay.BuildConfig
import io.github.iamr0s.carvex.pcm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actual HomeKit Main/Alt compressed sender, anchored to receiver v280.33.8 raw-AU contract. */
class CarPlayCompressedAudio private constructor(private val socket:DatagramSocket,private val peer:InetAddress,
    private val port:Int,private val category:Int,private val streamType:Int,private val selected:CarPlayAudioPlan.Format,
    private val key:ByteArray,private val retransmit:AudioRtpRetransmit?,private val ownership:suspend ()->Unit,private val stopped:()->Unit,
    private val log:(String)->Unit,private val redEnabled:Boolean,
    private val canSend:()->Boolean,receiverHighAccuracyTimestamps:Boolean):NegotiatedAudioTransport {
    private val closed=AtomicBoolean(false)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val output=Channel<CompressedAudioEncoder.Event>(8)
    private lateinit var encoder:CompressedAudioEncoder
    override val ready get()=!closed.get()&&!socket.isClosed
    override val formats=mapOf(category to selected.decodedPcm)
    override val microphoneFormat:PcmFormat?=null
    private var sequence=Crypto.random.nextInt(65536)
    private val rtpClock=AudioRtpTimestampClock(streamType,receiverHighAccuracyTimestamps)
    private var nonce=0L
    @Volatile private var packets=0L
    @Volatile private var lastSentNanos=0L
    override val packetsSent:Long get()=packets
    override val lastPacketNanos:Long get()=lastSentNanos
    private var history:ByteArray?=null
    private var historyTimestamp=0L
    private var staleUnits=0L
    /** v14.17 (task 5)：category2 的 AU 过期到阈值说明车机没在消费这条流（94 日志 staleAU
     *  9→113、c2 age 34 s）。此后再发 silence 只会烧 CPU 并干扰主音频，直接停这条 lane。 */
    private val staleLaneLimit=60L
    private val laneStopped=AtomicBoolean(false)
    private fun noteStale(){
        staleUnits++
        if(category==2&&staleUnits>=staleLaneLimit&&laneStopped.compareAndSet(false,true))
            log("Audio category=2 stale AU budget exhausted staleAU="+staleUnits+
                "; stopping the split lane instead of keeping it alive with silence")
    }
    private var maximumQueueNanos=0L
    private var maximumWaitNanos=0L
    private var maximumWriteNanos=0L
    private var timingLoggedAt=0L
    private val redDiagnostic=AudioRedDiagnostic(BuildConfig.CARPLAY_PRIMARY_ONLY_RED,category,selected.codec,redEnabled)
    private val packetDiagnostics=AudioPacketDiagnostics(redEnabled,selected.codec)
    init {
        log("Audio RTP clock category="+category+" type="+streamType+
            " peerHighAccuracy="+receiverHighAccuracyTimestamps+" timestampMode="+rtpClock.mode.diagnosticName+
            "; source pacing independent; high-accuracy network-clock anchors not implemented")
    }
    private fun failed(t:Throwable){
        if(closed.get())return
        log("${"Compressed audio category="}${category}${" failed: "}${t.javaClass.simpleName}${" "}${t.message}${"; audible acceptance pending"}")
        close()
    }
    /** iPhone keeps a receiver audio stream warm with continuous RTP, including silence.
     *  With no PCM source submitted (sourcePCMBytes=0) this client would send nothing and the
     *  VW-866F drops the whole session ~10s later. Emit a 20ms silence block whenever no packet
     *  went out recently, so the receiver sees a live stream while awaiting real audio. */
    private fun startSilenceTicker(){
        val blockBytes=selected.rate/50*selected.channels*2
        val silence=ByteArray(blockBytes)
        var tick=0L
        scope.launch{
            while(isActive){
                delay(400)
                if(closed.get()||socket.isClosed)break
                val age=System.nanoTime()-lastSentNanos
                if(age>300_000_000L&&ready&&canSend()&&!laneStopped.get()){
                    if(encoder.submit(silence,selected.decodedPcm)){tick++
                        if(tick==1L||tick%20==0L)log("Audio silence keepalive category="+category+" type="+streamType+
                            " blocks="+tick+"; no source PCM, keeping stream alive")
                    }
                }
            }
        }
    }
    private fun startWriter(){scope.launch(start=CoroutineStart.UNDISPATCHED){
        var config=false;var ownershipTaken=false;var firstTiming=true
        val clock=AudioSendClock(selected.rate,300_000_000L)
        fun clearHistory(){history=null;historyTimestamp=0L}
        fun timing(now:Long){
            if(timingLoggedAt!=0L&&now-timingLoggedAt<5_000_000_000L)return
            timingLoggedAt=now
            log("Audio sender category="+category+" outputQueueMaxMs="+maximumQueueNanos/1_000_000+
                " pacingWaitMaxMs="+maximumWaitNanos/1_000_000+" udpSendMaxMs="+maximumWriteNanos/1_000_000+
                " staleAU="+staleUnits+" firstAuStartupMs="+clock.startupDelayNanos/1_000_000+
                (if(::encoder.isInitialized){val s=encoder.statistics();" encoderSubmitted="+s.submittedBlocks+" encoderDropped="+s.droppedBlocks+" encoderUnits="+s.accessUnits}else{""})+
                "; local timings only, receiver playout unmeasured")
            maximumQueueNanos=0;maximumWaitNanos=0;maximumWriteNanos=0
        }
        try{
            for(event in output){
                when(event){
                    is CompressedAudioEncoder.Event.Configuration->{
                        require(event.rate==selected.rate&&event.channels==selected.channels)
                        if(selected.codec in setOf(CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.Codec.AAC_ELD)){
                            val csd=event.csd["csd-0"]
                            if(csd!=null){AacConfiguration.validate(csd,selected);config=true}
                        }else if(selected.codec==CarPlayAudioPlan.Codec.OPUS)config=true
                        log("Audio encoder category="+category+" delaySamples="+event.delaySamples+
                            " paddingSamples="+event.paddingSamples+" rate="+event.rate)
                    }
                    is CompressedAudioEncoder.Event.CodecConfig->{
                        if(selected.codec in setOf(CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.Codec.AAC_ELD)){
                            AacConfiguration.validate(event.bytes,selected);config=true
                        }
                    }
                    is CompressedAudioEncoder.Event.AccessUnit->{
                        if(event.bytes.isEmpty())continue
                        check(config){"Compressed output before validated codec configuration"}
                        require(event.bytes.size<=1400){"Encoded packet exceeds local UDP payload budget; no guessed fragmentation"}
                        val samples=if(selected.codec==CarPlayAudioPlan.Codec.OPUS)CompressedAudioContract.opusSamples(event.bytes,selected.rate) else selected.framesPerPacket
                        require(samples==selected.framesPerPacket){"Encoded frame duration differs from receiver audioFormat"}
                        val plan=clock.plan(event.presentationTimeUs,event.sourceTimeNanos,event.encodedAtNanos,samples)
                        if(firstTiming){
                            firstTiming=false
                            log("Audio encoder timing category="+category+" codecPtsUs="+event.codecPresentationTimeUs+
                                " sourcePtsUs="+event.presentationTimeUs+" firstAuStartupMs="+clock.startupDelayNanos/1_000_000)
                        }
                        maximumQueueNanos=maxOf(maximumQueueNanos,System.nanoTime()-event.encodedAtNanos)
                        if(plan.discontinuity)clearHistory()
                        if(!canSend()||laneStopped.get()){clearHistory();continue}
                        if(!ownershipTaken){ownership();ownershipTaken=true}
                        if(clock.expired(plan,System.nanoTime())){
                            noteStale();clearHistory();timing(System.nanoTime());continue
                        }
                        val wait=plan.dueNanos-System.nanoTime()
                        if(wait>0){
                            maximumWaitNanos=maxOf(maximumWaitNanos,wait)
                            // CarWith 桥接下源时钟可能膨胀（due 间隔>21.3ms）：若严格按 due 强等待，
                            // output 积压会卡住编码 worker、input 块 100ms 过期被静默丢弃
                            // （91 日志实测丢 ~30% 采样 → 866F 播放卡顿）。改为编码完成即发：
                            // 发送节奏跟随编码产出，RTP 时间戳仍按源 PTS 推算。
                            if(wait>2_000_000L){/* encode-driven; do not stall the pipeline */}
                            else delay((wait+999999)/1000000)
                        }
                        check(ready)
                        if(!canSend()||laneStopped.get()){clearHistory();continue}
                        // Coroutine scheduling or ownership may have stalled while this AU was held.
                        // Do not replay it late, and do not make every later AU inherit that delay.
                        if(clock.expired(plan,System.nanoTime())){
                            noteStale();clearHistory();timing(System.nanoTime());continue
                        }
                        // Keep source gaps for pacing/staleness/RED, but select wire timestamps
                        // from the SETUP reply. No unsent AU consumes the continuous RTP clock.
                        val timestamp=rtpClock.timestamp(plan.timestamp)
                        check(nonce<Long.MAX_VALUE){"Audio nonce exhausted"}
                        val packet=redDiagnostic.packet(event.bytes,history,historyTimestamp,key,sequence,timestamp,nonce++,streamType)
                        val packetSequence=sequence
                        val writeBegan=System.nanoTime()
                        socket.send(DatagramPacket(packet,packet.size,peer,port))
                        rtpClock.sent(samples)
                        sequence=(sequence+1) and 65535;packets++;lastSentNanos=System.nanoTime()
                        if(redEnabled){history=event.bytes;historyTimestamp=timestamp}
                        retransmit?.record(packetSequence,packet)
                        maximumWriteNanos=maxOf(maximumWriteNanos,lastSentNanos-writeBegan)
                        packetDiagnostics.sent(packet,event.bytes,samples,lastSentNanos)?.let{
                            log("Audio packet category="+category+" "+it.summary())
                        }
                        timing(lastSentNanos)
                        if(packets==1L||packets%200==0L)log("${"Compressed RTP category="}${category}${" type="}${streamType}${" codec="}${selected.codec}${" packets="}${packets}${"; audible acceptance pending"}")
                    }
                }
            }
        }catch(t:CancellationException){throw t}
        catch(t:Throwable){failed(t)}
        finally{key.fill(0)}
    }}
    override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){
        require(category==this.category&&format==selected.decodedPcm&&format.accepts(pcm.size))
        check(ready){"Compressed stream closed"}
        if(!canSend()||laneStopped.get())return
        if(!encoder.submit(pcm,format))log("${"Compressed audio category="}${category}${" source block dropped; encoder queue full"}")
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        socket.close();retransmit?.close();if(::encoder.isInitialized)encoder.close();output.cancel();scope.cancel();stopped()
    }
    companion object {
        suspend fun create(socket:DatagramSocket,peer:InetAddress,port:Int,category:Int,type:Int,
            format:CarPlayAudioPlan.Format,key:ByteArray,retransmit:AudioRtpRetransmit?,ownership:suspend ()->Unit,stopped:()->Unit,
            log:(String)->Unit,canSend:()->Boolean={true},redEnabled:Boolean=false,
            receiverHighAccuracyTimestamps:Boolean=false):CarPlayCompressedAudio {
            require(format.codec in setOf(CarPlayAudioPlan.Codec.AAC_LC,CarPlayAudioPlan.Codec.AAC_ELD,CarPlayAudioPlan.Codec.OPUS))
            val transport=CarPlayCompressedAudio(socket,peer,port,category,type,format,key,retransmit,ownership,stopped,log,redEnabled,canSend,receiverHighAccuracyTimestamps)
            val bitrate=when(format.codec){CarPlayAudioPlan.Codec.AAC_LC->192000;CarPlayAudioPlan.Codec.AAC_ELD->if(format.rate<=24000)48000 else 96000;else->if(format.rate<=24000)48000 else 96000}
            try{
                log("Audio RED diagnostic category="+category+" codec="+format.codec+" negotiatedRED="+redEnabled+
                    " sendRED="+redEnabled+
                    " configuredPrimaryOnly="+transport.redDiagnostic.configured+" appliedPrimaryOnly="+transport.redDiagnostic.applied+
                    "; primary-only isolation; packet format fixed by validated SETUP negotiation")
                transport.startWriter()
                transport.startSilenceTicker()
                transport.encoder=CompressedAudioEncoder.create(format,bitrate,{event->
                    // Bounded, cancellable backpressure. Configuration and access units remain
                    // ordered; the encoder has already returned any MediaCodec output buffer.
                    transport.output.send(event)
                },transport::failed)
                check(transport.ready){"Encoder failed during startup"}
                log("${"Compressed encoder ready name="}${transport.encoder.encoderName}${" category="}${category}${" codec="}${format.codec}${"; network acceptance pending"}")
                return transport
            }catch(t:Throwable){transport.close();key.fill(0);throw t}
        }
    }
}
