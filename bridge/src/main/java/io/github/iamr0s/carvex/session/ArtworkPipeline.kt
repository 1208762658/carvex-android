package io.github.iamr0s.carvex.session

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** v14.17 (task 4)：CarLife/CarWith 专辑封面管线。
 *
 *  来源：宿主把封面放在 `carlife_vehicle_music_info` Intent 的 extra 里，键名在不同
 *  CarWith 版本上是 `cover` / `artwork` / `MediaAlbumArt` / `MediaArtwork` 之一，值可能是
 *  byte[]、Uri 字符串或 Bitmap。旧版本只记一行 "5001 artwork file transfer remains
 *  unimplemented" 就丢弃，所以仪表盘一直是空的。
 *
 *  本类只负责把任意来源统一成"车机可显示的 256×256 JPEG 70%"+ 稳定 token（MD5 前 4 字节）：
 *  - 居中裁正方形（车机封面槽是方的，直接拉伸会把画面压歪）；
 *  - 下采样时先按 inSampleSize 粗降，再精确缩到 256，避免大图 OOM；
 *  - 解码/压缩都在调用方的 IO 线程上执行，本类不做线程切换。
 */
object ArtworkPipeline {
    const val TARGET=256
    const val QUALITY=70
    private val KEYS=listOf("cover","artwork","MediaAlbumArt","MediaArtwork")

    data class Prepared(val token:Int,val width:Int,val height:Int,val bytes:ByteArray){
        val chunks=((bytes.size+ArtworkTransferChunk-1)/ArtworkTransferChunk).coerceAtLeast(1)
    }

    /** 与 protocol-carplay 的 ArtworkTransfer.MAX_CHUNK 对齐；这里只用于日志估算。 */
    private const val ArtworkTransferChunk=1900

    fun prepare(context:Context,intent:Intent):Prepared?{
        val extras=intent.extras?:return null
        val resolver=context.contentResolver
        for(key in KEYS){
            val value=extras.get(key) ?: continue
            val raw=when(value){
                is ByteArray->value
                is Bitmap->compress(value)
                is String->readUri(context,resolver,value)
                else->null
            } ?: continue
            return fromBytes(raw)
        }
        return null
    }

    /** Uri / 文件路径 / content:// → 原始图片字节。http(s) 不做网络拉取：会话建立期间
     *  走网络只会把封面线程拖住，交给宿主的机会更大。 */
    private fun readUri(context:Context,resolver:android.content.ContentResolver,value:String):ByteArray?{
        if(value.isBlank()||value.startsWith("http",true))return null
        return runCatching{
            val uri=Uri.parse(value)
            if(uri.scheme.isNullOrEmpty())java.io.File(value).readBytes()
            else resolver.openInputStream(uri)?.use{it.readBytes()}
        }.getOrNull()
    }

    fun fromBytes(raw:ByteArray):Prepared?{
        if(raw.isEmpty())return null
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(raw,0,raw.size,bounds)
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null
        val options=BitmapFactory.Options().apply{inSampleSize=sampleSize(bounds.outWidth,bounds.outHeight)}
        val decoded=BitmapFactory.decodeByteArray(raw,0,raw.size,options) ?: return null
        val square=cropSquare(decoded)
        val scaled=if(square.width==TARGET)square else Bitmap.createScaledBitmap(square,TARGET,TARGET,true)
        val jpeg=compress(scaled)
        if(square!==scaled)square.recycle()
        if(scaled!==decoded)scaled.recycle()
        decoded.recycle()
        val token=token(jpeg)
        return Prepared(token,TARGET,TARGET,jpeg)
    }

    private fun sampleSize(width:Int,height:Int):Int{
        var size=1
        var w=width
        var h=height
        while(w/2>=TARGET*2&&h/2>=TARGET*2){w/=2;h/=2;size*=2}
        return size
    }

    private fun cropSquare(source:Bitmap):Bitmap{
        val side=minOf(source.width,source.height)
        val x=(source.width-side)/2
        val y=(source.height-side)/2
        return Bitmap.createBitmap(source,x,y,side,side)
    }

    private fun compress(bitmap:Bitmap):ByteArray{
        val out=ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG,QUALITY,out)
        return out.toByteArray()
    }

    /** token 用内容哈希，同一张封面重复到达时不重复发（缓存键由调用方持有）。 */
    fun token(jpeg:ByteArray):Int{
        val digest=MessageDigest.getInstance("MD5").digest(jpeg)
        return ((digest[0].toInt() and 255) shl 24) or ((digest[1].toInt() and 255) shl 16) or
            ((digest[2].toInt() and 255) shl 8) or (digest[3].toInt() and 255)
    }
}
