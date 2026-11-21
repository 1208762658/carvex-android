package io.github.iamr0s.carvex.session

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把 CarWith 递过来的封面归一成"车机能收的 JPEG 字节"。
 *
 * 实测输入（20058 / 2.4 日志）是 `carlife_vehicle_music_info` 的 extras：
 * `album,artist,cover,coverUrl,duration,song`。`cover` 可能是字节、Bitmap、本地路径或 content Uri，
 * `coverUrl` 可能是 http(s) 链接。这里只负责取到图并规整尺寸，不做任何猜车机格式的事。
 *
 * 出错一律返回 null，由调用方只记日志——封面失败绝不能影响正在播放的会话。
 */
internal object CarLifeArtwork {
    /** 封面显示区很小，iPhone 也只发缩略图；256 边足够，且能让整张图落在几十个分块内。 */
    private const val JPEG_QUALITY = 88
    /** v101(20123)：250 = 原生尺寸直出，停用 4.2 起的"放大到 600"实验遗留。
     *  背景：4.2 的尺寸对照实验从未完成（v99 之前文件在车机侧从未拼装成功，无结论）；
     *  v99 首次把 600x600 文件送进车机 Success→缓存路径后，两次冷车点火车机硬冻死
     *  （11-20 21:07 / 11-21 08:28 本地），v100 已用闸门定罪"文件规格"维度。
     *  重启封面线的第一变量选原生尺寸：CarWith 递来的 Bitmap 恒 250x250，本机直出
     *  零重采样（scaleDown 对 edge==maxEdge 原样返回）；250 级 JPEG 是车机从
     *  AVRCP/CarWith 原生封面就在处理的字节级，且体量比 600x600 砍约 4 倍，
     *  持久化足迹最小。URL 原图大于 250 时仍只缩小不放大。 */
    private val edgeRotation = java.util.concurrent.atomic.AtomicInteger(0)
    private fun nextMaxEdge(): Int { edgeRotation.getAndIncrement(); return 250 }
    /** 网络/文件读取上限：再大就不是封面了。 */
    private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    private val ARTWORK_KEYS = listOf("cover", "artwork", "MediaAlbumArt", "MediaArtwork")
    private val ARTWORK_URL_KEYS = listOf("coverUrl", "cover_url", "artworkUrl")

    fun normalize(context: Context, intent: Intent, log: (String) -> Unit): ByteArray? {
        val source = readSource(context, intent, log) ?: return null
        if (source.isEmpty()) { log("artwork source empty; nothing to send"); return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val looksJpeg = source.size > 4 && source[0] == 0xFF.toByte() && source[1] == 0xD8.toByte()
            log("artwork source not decodable width=" + bounds.outWidth + " height=" + bounds.outHeight +
                " looksJpeg=" + looksJpeg + " bytes=" + source.size)
            return if (looksJpeg) source else null
        }
        val maxEdge = nextMaxEdge()
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, maxEdge) }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: run {
            log("artwork decode failed after bounds " + bounds.outWidth + "x" + bounds.outHeight)
            return null
        }
        val scaled = scaleDown(bitmap, maxEdge)
        val sentWidth = scaled.width
        val sentHeight = scaled.height
        val jpeg = ByteArrayOutputStream().also {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }.toByteArray()
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()
        log("artwork normalized source=" + bounds.outWidth + "x" + bounds.outHeight +
            " sent=" + sentWidth + "x" + sentHeight + " maxEdge=" + maxEdge + " JPEG bytes=" + jpeg.size)
        return jpeg.takeIf { it.isNotEmpty() }
    }

    private fun sampleSize(bounds: BitmapFactory.Options, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** 4.2/4.3 暴露的问题：CarWith 递过来的 cover 只有 250x250，
     *  而 scaleDown 只会缩小、不会放大，于是 "maxEdge=600" 从来没生效过
     *  （日志里 sent=250x250 maxEdge=600），尺寸这个变量等于没测。
     *  改成双向缩放：小于目标边也放大到目标边，保证日志里的 sent 就是真实发出的尺寸。 */
    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge == maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / edge
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** 依次尝试所有已知字段；第一个能取到字节的就用，取不到就试下一个。
     *  v90(20104)：URL 字段（coverUrl 等）优先——5.8/5.9 实车链里 cover Bitmap 恒为
     *  250x250，只有放大损失；URL 通常是专辑原图，且首歌阶段 URL 往往比渲染好的
     *  Bitmap 更早可用（"第一首无封面"的一个候选成因）。字节字段保留为兜底：
     *  URL 拉取失败（无网/超时 3s/超限）自动落回 Bitmap，不碰控制会话。 */
    private fun readSource(context: Context, intent: Intent, log: (String) -> Unit): ByteArray? {
        val extras = intent.extras ?: return null
        for (key in ARTWORK_URL_KEYS) {
            val url = extras.getString(key)?.takeIf { it.isNotBlank() } ?: continue
            val bytes = runCatching { bytesOfUrl(url) }.getOrElse { error ->
                log("artwork url " + key + " unreadable: " + error.javaClass.simpleName)
                null
            }
            if (bytes != null && bytes.isNotEmpty()) {
                log("artwork source field=" + key + " type=url bytes=" + bytes.size)
                return bytes
            }
        }
        for (key in ARTWORK_KEYS) {
            if (!extras.containsKey(key)) continue
            val value = extras.get(key)
            val bytes = runCatching { bytesOf(context, value) }.getOrElse { error ->
                log("artwork field " + key + " unreadable: " + error.javaClass.simpleName)
                null
            }
            if (bytes != null && bytes.isNotEmpty()) {
                log("artwork source field=" + key + " type=" + (value?.javaClass?.simpleName ?: "null") +
                    " bytes=" + bytes.size)
                return bytes
            }
        }
        return null
    }

    private fun bytesOf(context: Context, value: Any?): ByteArray? = when (value) {
        null -> null
        is ByteArray -> value
        is Bitmap -> ByteArrayOutputStream().also {
            value.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        is Uri -> context.contentResolver.openInputStream(value)?.use { readCapped(it) }
        is String -> bytesOfString(context, value)
        is File -> readCapped(value.inputStream())
        else -> null
    }

    private fun bytesOfString(context: Context, value: String): ByteArray? {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) ->
                bytesOfUrl(trimmed)
            trimmed.startsWith("content://", true) ->
                context.contentResolver.openInputStream(Uri.parse(trimmed))?.use { readCapped(it) }
            trimmed.startsWith("file://", true) ->
                File(Uri.parse(trimmed).path ?: return null).takeIf { it.isFile }?.let { readCapped(it.inputStream()) }
            else -> File(trimmed).takeIf { it.isFile }?.let { readCapped(it.inputStream()) }
        }
    }

    private fun bytesOfUrl(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.instanceFollowRedirects = true
            connection.inputStream.use { readCapped(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(stream: java.io.InputStream): ByteArray = stream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (out.size() + read > MAX_SOURCE_BYTES) throw java.io.IOException("artwork source too large")
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
}
