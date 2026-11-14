package io.github.iamr0s.carvex.carplay.core

/**
 * iAP2 文件传输（对端 SYN 里的 kind=1 会话）的数据面封包。
 *
 * 固件 `iap2_sessionRouter_processSessionData`（`0x10006b878`）把类型 1 会话的载荷直接交给
 * `iap2_sessionFileTransfer_handleDatagram`（`0x100183b8c`），那里**首字节就是传输 ID**，
 * 紧接着是操作类型字节，其余才是本次操作的正文。所以一帧数据面报文形如：
 *
 *   `[transferId][type][body...]`
 *
 * 三类操作（本实现只用到这三个）：
 *   - `4` 建立：正文是 u64 总长（大端），告诉对端这次要收多少字节；
 *   - `3` 数据：正文是本次分块；分块大小由调用方按会话 MTU 切，不能再套 CSM；
 *   - `5` 结束：没有正文。
 *
 * 这里只负责"怎么封"，不负责"发给谁、什么时候发"——投递在 `AirSession.artwork()`，
 * 会话号来自 [IapLink.sessionOfKind]`(1)`，缺会话时上层整段 no-op。
 */
object ArtworkFileTransfer {
    const val TYPE_START=4
    const val TYPE_CHUNK=3
    const val TYPE_COMPLETE=5

    fun start(transferId:Byte,totalBytes:Long):ByteArray {
        require(totalBytes in 0..Long.MAX_VALUE)
        return byteArrayOf(transferId,TYPE_START.toByte())+IapWire.be64(totalBytes)
    }

    fun chunk(transferId:Byte,body:ByteArray):ByteArray {
        require(body.isNotEmpty()){"empty file-transfer chunk"}
        return byteArrayOf(transferId,TYPE_CHUNK.toByte())+body
    }

    fun complete(transferId:Byte):ByteArray =
        byteArrayOf(transferId,TYPE_COMPLETE.toByte())

    /** 一次传输大致要发多少帧（建立 + 分块 + 结束），只用于日志与预算判断。 */
    fun packetCount(payloadBytes:Int,maxPacketBytes:Int):Int {
        require(maxPacketBytes>=3){"iAP session MTU too small for file transfer"}
        val perPacket=maxPacketBytes-2
        val chunks=(payloadBytes+perPacket-1)/perPacket
        return chunks+2
    }
}
