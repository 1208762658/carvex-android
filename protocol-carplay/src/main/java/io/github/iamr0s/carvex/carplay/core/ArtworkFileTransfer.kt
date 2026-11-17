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
 * 类型由 `payload[1]` 的低 4 位决定（`0x10006de9c` 处的 `and w23, w24, #0xf`），
 * 其中类型 4（建立）的字段位置来自同一函数的固定偏移读取：
 *
 *   偏移 0..1   = transferId / 类型
 *   偏移 2..5   = 保留（对端不读）
 *   偏移 6..9   = 文件总长，BE32
 *   偏移 10..11 = 文件类型，取值 0..8（>8 直接进错误分支）
 *   偏移 12..19 = 8 字节的 bufferID 一类
 *   偏移 20..   = 文件名，NUL 结尾，最多 0x3c 字节
 *
 * 早先的 `[id][4][u64 总长]` 只有 10 字节，对端在偏移 10 读文件类型时越界拿到垃圾值，
 * 直接落进"未知类型"分支——传输对象没被创建，后面所有数据块因此被静默丢弃。
 * 所以建立包必须按上表铺满到文件名之后。
 *
 * 三类操作（本实现只用到这三个）：
 *   - `4` 建立：按上表的完整字段布局；
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
    // iOS 侧 `_iAP2FileTransferDelete` 对应线上的 type 6。iPhone 在新封面到来时
    // 会先把上一张的传输 cancel 掉（日志串 `#Artwork %d: cancel previous transfer`），
    // 即车机这一端只保留一个有效的封面传输槽。我们从来没做这一步。
    const val TYPE_DELETE=6

    /** 建立包里对端会当"文件类型"读走的枚举值；0..8 之外对端直接判错。 */
    const val FILE_TYPE_ARTWORK=4
    /** 文件名上限：对端只拷 0x3c 字节。 */
    private const val NAME_LIMIT=0x3c

    fun start(
        transferId:Byte,
        totalBytes:Long,
        fileType:Int=FILE_TYPE_ARTWORK,
        fileName:String="artwork.jpg",
        /** 偏移 12..19 的 8 字节。iOS 侧 "Failed to allocate file transfer buffer ID!" 说明
         *  这里填的是真实分配的 buffer ID；先按 0 试，探针版本用来 A/B。 */
        bufferId:Long=0,
        /** iOS 走 `iAP2FileTransferTypeData`（"数据"而不是"文件"）时，Start 的长度只有 10：
         *  即 `[id][4][保留 4B][总长 BE32]`，不带文件类型/8 字节尾/文件名。866F 也许只认同这一种。 */
        short:Boolean=false
    ):ByteArray {
        require(totalBytes in 0..0xffffffffL){"file-transfer size must fit BE32: $totalBytes"}
        require(fileType in 0..8){"file type out of the peer's accepted range: $fileType"}
        val name=fileName.take(NAME_LIMIT).toByteArray(Charsets.US_ASCII)
        val head=java.io.ByteArrayOutputStream()
        head.write(transferId.toInt())
        head.write(TYPE_START)
        head.write(ByteArray(4))                 // 2..5  保留
        head.write(IapWire.be32(totalBytes))     // 6..9  总长
        if(short)return head.toByteArray()
        head.write(IapWire.be16(fileType))       // 10..11 文件类型
        head.write(IapWire.be64(bufferId))       // 12..19 bufferID 一类
        head.write(name)                         // 20..  文件名
        head.write(0)                            // NUL 结尾
        return head.toByteArray()
    }

    fun chunk(transferId:Byte,body:ByteArray):ByteArray {
        require(body.isNotEmpty()){"empty file-transfer chunk"}
        return byteArrayOf(transferId,TYPE_CHUNK.toByte())+body
    }

    fun complete(transferId:Byte):ByteArray =
        byteArrayOf(transferId,TYPE_COMPLETE.toByte())

    fun cancel(transferId:Byte):ByteArray =
        byteArrayOf(transferId,TYPE_DELETE.toByte())

    /** 一次传输大致要发多少帧（建立 + 分块 + 结束），只用于日志与预算判断。 */
    /** 分块正文长度：统一按调用方给的每帧上限切，且不跨过总长。 */
    fun chunks(body:ByteArray,perPacket:Int):List<ByteArray> {
        require(perPacket>0)
        return (0 until body.size step perPacket).map{
            body.copyOfRange(it,minOf(body.size,it+perPacket))
        }
    }

    fun packetCount(payloadBytes:Int,maxPacketBytes:Int):Int {
        require(maxPacketBytes>=3){"iAP session MTU too small for file transfer"}
        val perPacket=maxPacketBytes-2
        val chunks=(payloadBytes+perPacket-1)/perPacket
        return chunks+2
    }
}
