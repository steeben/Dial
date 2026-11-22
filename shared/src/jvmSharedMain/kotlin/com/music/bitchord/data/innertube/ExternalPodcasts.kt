package com.music.bitchord.data.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Podcast episodes YouTube does not host: shows a listener added to YouTube
 * Music from an RSS feed (Library, "Add podcast", "Add RSS feed").
 *
 * Such an episode has an ordinary 11-character video id, an ordinary episode
 * row and an ordinary queue, so nothing before playback tells it apart. Its
 * audio is not on googlevideo, though. The `player` endpoint answers it only
 * for the signed-in listener whose library holds the feed, and the answer is
 * one plain format — `itag` 15, `audio/mpeg`, with a `url` that is the feed's
 * own enclosure (the podcast host, often behind a tracking redirect) — and
 * `videoDetails.isExternallyHostedPodcast: true`. No adaptive formats, no
 * cipher. Asked by any other client, or anonymously, the same id is "Video
 * unavailable".
 *
 * So the usual resolve finds nothing: InnerTubeX looks for YouTube's adaptive
 * audio, and NewPipe asks anonymously. [StreamResolver] asks the signed-in
 * `player` instead (see [Innertube.externalPodcastStream]) and plays the
 * enclosure URL directly.
 *
 * Ids are marked two ways, like [UploadedTracks]: by [InnertubeParser] when an
 * episode's picture comes from YouTube's podcast ingestion proxy (the art of an
 * RSS feed; YouTube's own episodes use i.ytimg.com), and by [StreamResolver]
 * when the ordinary resolve failed and the signed-in `player` said so. A marked
 * id is asked the signed-in way first, so it starts without the walk through
 * clients that cannot serve it.
 *
 * Held in memory only.
 */
object ExternalPodcasts {

    private const val MAX_REMEMBERED = 20_000

    /** Where YouTube Music serves the art of a podcast it ingested from an RSS feed. */
    private const val INGESTION_PROXY = "/youtube-podcasts-ingestion-proxy/"

    /** A browser's agent, for a fetch from a podcast host that is not YouTube's. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val ids = ConcurrentHashMap.newKeySet<String>()

    /** Stream URLs this object handed out, so their fetch is not dressed as a YouTube client. */
    private val urls = ConcurrentHashMap.newKeySet<String>()

    fun mark(videoId: String) {
        if (videoId.isBlank()) return
        if (ids.size >= MAX_REMEMBERED) ids.clear()
        ids.add(videoId)
    }

    fun contains(videoId: String): Boolean = videoId in ids

    /** Whether [thumbnailUrl] is the art of a podcast YouTube Music ingested from an RSS feed. */
    fun isIngestedArt(thumbnailUrl: String?): Boolean =
        thumbnailUrl?.contains(INGESTION_PROXY) == true

    /** Whether [url] is an enclosure URL handed out by [StreamResolver] for such an episode. */
    fun isStreamUrl(url: String): Boolean = url in urls

    internal fun rememberStreamUrl(url: String) {
        if (urls.size >= MAX_REMEMBERED) urls.clear()
        urls.add(url)
    }

    /** Forgets everything — an account switch is a different library of feeds. */
    fun clear() {
        ids.clear()
        urls.clear()
    }

    /** The feed's own audio for an episode, as the `player` endpoint names it. */
    class Enclosure(
        val url: String,
        val mimeType: String,
        val contentLength: Long?,
        val durationMs: Long?,
    ) {
        /** The bitrate, worked out from size and length; the format states none. */
        val kbps: Int
            get() {
                val bytes = contentLength ?: return 0
                val ms = durationMs?.takeIf { it > 0 } ?: return 0
                return (bytes * 8 / ms).toInt()
            }
    }

    /**
     * The enclosure in a `player` answer, or null when the answer is not for a
     * playable episode that YouTube does not host.
     */
    fun enclosureIn(player: JsonObject): Enclosure? {
        val status = player.playability()
        if (status != null && status != "OK") return null
        // The flag says so outright. Without it, a format that plays from a
        // host that is not YouTube's says the same thing.
        val flagged = player.isExternallyHosted()
        val candidates = player.formats().mapNotNull { format ->
            val url = format.mediaUrl() ?: return@mapNotNull null
            if (!flagged && url.isYouTubeMedia()) return@mapNotNull null
            format to url
        }
        val (format, url) = candidates.firstOrNull { (format, _) ->
            format.string("mimeType")?.startsWith("audio/") == true
        } ?: candidates.firstOrNull() ?: return null
        return Enclosure(
            url = url,
            mimeType = format.string("mimeType") ?: "audio/mpeg",
            contentLength = format.string("contentLength")?.toLongOrNull(),
            durationMs = format.string("approxDurationMs")?.toLongOrNull(),
        )
    }

    /**
     * What a `player` answer held, for the log when [enclosureIn] found
     * nothing in it: status, the flag, and each format's itag, type and host.
     * Hosts only, never a URL, which can carry a listener's session.
     */
    fun describe(player: JsonObject): String {
        val reason = ((player["playabilityStatus"] as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
        val formats = player.formats().joinToString(prefix = "[", postfix = "]") { format ->
            val host = format.mediaUrl()?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            "${format.string("itag")} ${format.string("mimeType")?.substringBefore(';')} " +
                (host ?: if (format.containsKey("signatureCipher")) "cipher" else "no url")
        }
        return "status=${player.playability()} reason=$reason " +
            "externallyHosted=${player.isExternallyHosted()} formats=$formats"
    }

    private fun JsonObject.playability(): String? =
        ((this["playabilityStatus"] as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.isExternallyHosted(): Boolean =
        ((this["videoDetails"] as? JsonObject)?.get("isExternallyHostedPodcast") as? JsonPrimitive)
            ?.booleanOrNull == true

    private fun JsonObject.formats(): List<JsonObject> {
        val streaming = this["streamingData"] as? JsonObject ?: return emptyList()
        return listOf("formats", "adaptiveFormats")
            .flatMap { key -> (streaming[key] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
    }

    /**
     * A format's own URL, or the one inside its `signatureCipher`. A feed's
     * enclosure needs no signature, so a cipher around one is just packaging;
     * a cipher around a googlevideo URL is not taken here (see [isYouTubeMedia]).
     */
    private fun JsonObject.mediaUrl(): String? {
        string("url")?.takeIf { it.startsWith("http") }?.let { return it }
        val cipher = string("signatureCipher") ?: return null
        return cipher.split('&')
            .firstOrNull { it.startsWith("url=") }
            ?.removePrefix("url=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.startsWith("http") && !it.isYouTubeMedia() }
    }

    private fun String.isYouTubeMedia(): Boolean {
        val host = runCatching { java.net.URI(this).host }.getOrNull()?.lowercase() ?: return false
        return host.endsWith("googlevideo.com") || host.endsWith("youtube.com")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
