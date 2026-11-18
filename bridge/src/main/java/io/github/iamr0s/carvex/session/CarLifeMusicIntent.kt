package io.github.iamr0s.carvex.session

import android.content.Intent
import io.github.iamr0s.carvex.carplay.core.NowPlayingUpdate
import org.json.JSONObject

/**
 * Normalizes the vehicle-music Intent without treating private field names
 * as an iAP2 schema. Only fields with a known Now Playing analogue are
 * copied. Artwork and playback-state values remain diagnostic until their
 * wire mapping is independently confirmed.
 */
internal object CarLifeMusicIntent {
    private const val ACTION = "carlife_vehicle_music_info"

    fun parse(intent: Intent): NowPlayingUpdate.Track? {
        val json = intent.getStringExtra("params")?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }
        val extras = intent.extras?.let { bundle ->
            bundle.keySet().associateWith { key -> bundle.get(key) }
        }.orEmpty()
        return parsePayload(intent.action, extras, json)
    }

    internal fun parsePayload(action: String?, values: Map<String, Any?>,
                              json: JSONObject? = null): NowPlayingUpdate.Track? {
        if (action != ACTION) return null
        fun string(vararg names: String): String? {
            for (name in names) {
                val direct = values[name]
                if (direct is String) return direct
                val nested = json?.takeIf { it.has(name) && !it.isNull(name) }?.optString(name)
                if (nested != null) return nested
            }
            return null
        }
        fun number(vararg names: String): Long? {
            for (name in names) {
                val value = when (val direct = values[name]) {
                    is Number -> direct.toLong()
                    is String -> direct.toLongOrNull()
                    else -> null
                } ?: json?.optLong(name, Long.MIN_VALUE)?.takeUnless { it == Long.MIN_VALUE }
                if (value != null) return value
            }
            return null
        }
        val title = string("song", "title", "MediaSongName", "song_name")
        val artist = string("artist", "MediaArtistName", "artist_name")
        val album = string("album", "MediaAlbumName", "album_name")
        val app = string("source", "app", "MediaAPPName", "MediaAppName")
        val duration = number("duration", "duration_ms", "MediaSongDuration")
            ?.takeIf { it in 1..0xffffffffL }
        val elapsed = number("progress", "progress_ms", "MediaSongPlayTime")
            ?.takeIf { it in 0..0xffffffffL }
        if (title == null && artist == null && album == null && app == null && duration == null && elapsed == null) {
            return null
        }
        // CarWith 不给 persistentId，我们一直发 0，整段会话里车机看到的永远是"同一个媒体项"。
        // 封面是按媒体项缓存/取回的：若它按 ID 记住"这一项没有图"，后面封面传得再成功也只有文字会变。
        // 这里按歌名+艺人+专辑派生一个稳定 64 位 ID（同一首歌恒定，换歌即换）。
        val derivedId = java.security.MessageDigest.getInstance("SHA-256")
            .digest(((title ?: "") + "\u0000" + (artist ?: "") + "\u0000" + (album ?: "")).toByteArray())
            .let { java.nio.ByteBuffer.wrap(it).long and 0x7fffffffffffffffL }
        return NowPlayingUpdate.Track(
            title = NowPlayingUpdate.textOrNull(title),
            artist = NowPlayingUpdate.textOrNull(artist),
            album = NowPlayingUpdate.textOrNull(album),
            durationMs = duration,
            elapsedMs = elapsed,
            appName = NowPlayingUpdate.textOrNull(app),
            persistentId = derivedId,
        )
    }

    fun hasArtwork(intent: Intent): Boolean =
        intent.action == ACTION && listOf("cover", "artwork", "MediaAlbumArt", "MediaArtwork").any {
            intent.extras?.containsKey(it) == true
        }
}
