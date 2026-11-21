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
 *   - `2` 数据：正文是本次分块（v99 修正，原 3 是车机发送方向的操作码）；
 *     末块 type|0x40 触发车机侧 Success，首块可选 type|0x80；
 *   - `5` 结束：没有正文。对 accessory→车机方向车机直接忽略（id<128 静默退出），
 *     保留发送只为兼容旧日志对齐。
 *
 * 这里只负责"怎么封"，不负责"发给谁、什么时候发"——投递在 `AirSession.artwork()`，
 * 会话号来自 [IapLink.sessionOfKind]`(1)`，缺会话时上层整段 no-op。
 */
object ArtworkFileTransfer {
    const val TYPE_START=4
    /** v101(20123)：数据面闸门默认开（v99 行为 type 2 + 0x80/0x40）。
     *
     *  时间线：v99 数据面让车机史上第一次真正拼装并入库了我们的封面文件，随后两次
     *  冷车点火首连车机在握手/媒体阶段硬冻死重启；v100 闸门默认关（v98 数据面）后
     *  车机稳定，定罪"600x600 文件规格"维度。v101 以新单变量重启封面线：文件规格
     *  改为原生 250x250（CarLifeArtwork.nextMaxEdge），数据面恢复开闸实测——
     *  用户无 adb，闸门翻转必须内建出包，无法运行时切换。
     *
     *  闸门关 = 精确恢复 v98 行为：分块 type 3（车机发送方向操作码，被静默丢弃）、
     *  不带任何旗标——车机永远拼不出文件，Success 路径不再触发，持久层不再收到
     *  我们的新文件。系统属性 `carvex.art.data.v99=0` 可强制关（诊断用）。
     *  关闭时 START/COMPLETE 照发（与 v98 完全一致），开/关只影响分块字节。 */
    val DATA_PLANE_V99: Boolean
        get()=System.getProperty("carvex.art.data.v99")?.trim()!="0"

    /** v98 数据面操作码：车机 `_iAP2FileTransferHandleRecv`（0x10006de9c）里 type 3 是
     *  它自己的发送/应答方向，accessory 发的 type 3 在 state=8 时仅切 state 8→9 并丢
     *  载荷（0x10006e328）——即"发出去、车机不收"的既有安全行为。 */
    const val TYPE_CHUNK_V98=3
    // v99(20121)：分块 type 3→2。固件 `_iAP2FileTransferHandleRecv`（0x10006de9c）里
    // type 2 才是 accessory→车机的数据分支（state {2,3,8,9} 接收，0x10006e4a4 分派）；
    // type 3 是车机自己的数据发送/应答方向——我们 state=8 时收到 type 3 只会切 state 8→9
    // 并丢载荷（0x10006e328），后续 type 3 直接落"unexpected state"日志分支。v98 及之前
    // 63/64 个分块全部被丢，文件从未在车机侧拼装，artwork 缓存恒空 → 框架绿方块。
    // v100(20122)：该常量仅在闸门 DATA_PLANE_V99 开启时生效，见 chunk()。
    const val TYPE_CHUNK=2
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

    /** v99：byte1 高位标志。末块 bit6(0x40)=触发 `_iAP2FileTransferSuccess`（0x10006ef6c：
     *  tbnz w24,#6 → state 0xb → 注册回调收到拼装完成的文件 → artwork 缓存填充）——
     *  这是 accessory→车机方向唯一的"完成"信号；独立 type5 结束包对 id<128 的传输
     *  在 0x10006e158 处静默退出（它是车机→手机方向的握手）。首块 bit7(0x80) 是车机
     *  自家发送路径的"首块"约定（SendBufferPacket 0x10006c1c4: orr w8, w22, #0x80），
     *  接收侧不校验，按协议对齐补上。 */
    const val CHUNK_FLAG_FIRST=0x80
    const val CHUNK_FLAG_LAST=0x40
    fun chunk(transferId:Byte,body:ByteArray,first:Boolean=false,last:Boolean=false):ByteArray {
        require(body.isNotEmpty()){"empty file-transfer chunk"}
        if(!DATA_PLANE_V99){
            // v100 闸门关（默认）：v98 数据面——type 3、无旗标。车机把分块静默丢弃，
            // Success 不触发，文件不入库。START/COMPLETE 照发，链路语义与 v98 全等。
            return byteArrayOf(transferId,TYPE_CHUNK_V98.toByte())+body
        }
        var type=TYPE_CHUNK
        if(first)type=type or CHUNK_FLAG_FIRST
        if(last)type=type or CHUNK_FLAG_LAST
        return byteArrayOf(transferId,type.toByte())+body
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
