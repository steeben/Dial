package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Episodes of a podcast added to YouTube Music from an RSS feed. The `player`
 * answers below are cut down from live ones (signed-in WEB_REMIX, November
 * 2026): an RSS feed's episode, and an episode YouTube hosts itself.
 */
class ExternalPodcastsTest {

    @AfterTest
    fun reset() = ExternalPodcasts.clear()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val feedEpisode = json(
        """
        {
          "playabilityStatus": { "status": "OK", "playableInEmbed": true },
          "streamingData": {
            "expiresInSeconds": "21540",
            "formats": [ {
              "itag": 15,
              "url": "https://tracking.example.com/track/prfx.example.com/e/host.example.com/episodes/1/audio/128/default.mp3?size=17739090",
              "mimeType": "audio/mpeg",
              "contentLength": "17739090",
              "approxDurationMs": "1108000"
            } ]
          },
          "videoDetails": {
            "videoId": "VWWiutLZYE8",
            "musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
            "isExternallyHostedPodcast": true
          }
        }
        """,
    )

    @Test
    fun feedEpisode_givesTheFeedsOwnAudio() {
        val enclosure = assertNotNull(ExternalPodcasts.enclosureIn(feedEpisode))
        assertTrue(enclosure.url.startsWith("https://tracking.example.com/"))
        assertEquals("audio/mpeg", enclosure.mimeType)
        assertEquals(17739090L, enclosure.contentLength)
        // 17.7 MB over 1108 s is a 128 kbps MP3.
        assertEquals(128, enclosure.kbps)
    }

    @Test
    fun youTubeHostedEpisode_isLeftToTheUsualResolve() {
        val hosted = json(
            """
            {
              "playabilityStatus": { "status": "OK" },
              "streamingData": { "adaptiveFormats": [ { "itag": 251, "mimeType": "audio/webm; codecs=\"opus\"", "signatureCipher": "s=x" } ] },
              "videoDetails": { "videoId": "TwrYkSd78h8", "musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" }
            }
            """,
        )
        assertNull(ExternalPodcasts.enclosureIn(hosted))
    }

    @Test
    fun anonymousRefusal_hasNoEnclosure() {
        val refused = json(
            """{ "playabilityStatus": { "status": "ERROR", "reason": "Video unavailable" } }""",
        )
        assertNull(ExternalPodcasts.enclosureIn(refused))
    }

    @Test
    fun feedAudioWithoutTheFlag_isStillTheFeeds() {
        val unflagged = json(
            """
            {
              "playabilityStatus": { "status": "OK" },
              "streamingData": { "formats": [ { "itag": 15, "mimeType": "audio/mpeg",
                "url": "https://www.ivoox.com/episode_mf_184081969_feed_1.mp3?d=1" } ] },
              "videoDetails": { "videoId": "nIT404chFzE" }
            }
            """,
        )
        assertEquals(
            "https://www.ivoox.com/episode_mf_184081969_feed_1.mp3?d=1",
            ExternalPodcasts.enclosureIn(unflagged)?.url,
        )
    }

    @Test
    fun feedAudioInsideACipher_isUnwrapped() {
        val wrapped = json(
            """
            {
              "playabilityStatus": { "status": "OK" },
              "streamingData": { "formats": [ { "itag": 15, "mimeType": "audio/mpeg",
                "signatureCipher": "s=abc&sp=sig&url=https%3A%2F%2Fwww.ivoox.com%2Fa_mf_1_feed_1.mp3%3Fd%3D2" } ] },
              "videoDetails": { "isExternallyHostedPodcast": true }
            }
            """,
        )
        assertEquals("https://www.ivoox.com/a_mf_1_feed_1.mp3?d=2", ExternalPodcasts.enclosureIn(wrapped)?.url)
    }

    @Test
    fun youTubeUrlWithoutTheFlag_isNotAnEnclosure() {
        val ordinary = json(
            """
            {
              "playabilityStatus": { "status": "OK" },
              "streamingData": { "formats": [ { "itag": 18, "mimeType": "video/mp4",
                "url": "https://rr1---sn-abc.googlevideo.com/videoplayback?itag=18" } ] },
              "videoDetails": { "videoId": "dQw4w9WgXcQ" }
            }
            """,
        )
        assertNull(ExternalPodcasts.enclosureIn(ordinary))
    }

    @Test
    fun describe_namesStatusFlagAndHostsOnly() {
        val text = ExternalPodcasts.describe(feedEpisode)
        assertTrue("status=OK" in text, text)
        assertTrue("externallyHosted=true" in text, text)
        assertTrue("15 audio/mpeg tracking.example.com" in text, text)
        assertFalse("/track/" in text, text)
        val refused = ExternalPodcasts.describe(
            json("""{ "playabilityStatus": { "status": "ERROR", "reason": "Video unavailable" } }"""),
        )
        assertTrue("status=ERROR reason=Video unavailable" in refused, refused)
    }

    @Test
    fun registry_remembersAndForgets() {
        assertFalse(ExternalPodcasts.contains("VWWiutLZYE8"))
        ExternalPodcasts.mark("VWWiutLZYE8")
        ExternalPodcasts.rememberStreamUrl("https://host.example.com/a.mp3")
        assertTrue(ExternalPodcasts.contains("VWWiutLZYE8"))
        assertTrue(ExternalPodcasts.isStreamUrl("https://host.example.com/a.mp3"))
        ExternalPodcasts.clear()
        assertFalse(ExternalPodcasts.contains("VWWiutLZYE8"))
        assertFalse(ExternalPodcasts.isStreamUrl("https://host.example.com/a.mp3"))
    }

    private fun showRow(videoId: String, art: String) = """
        {
          "musicMultiRowListItemRenderer": {
            "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [ { "url": "$art", "width": 544, "height": 544 } ] } } },
            "onTap": { "watchEndpoint": { "videoId": "$videoId", "params": "8gEDmAEI",
              "watchEndpointMusicSupportedConfigs": { "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" } } } },
            "title": { "runs": [ { "text": "An episode" } ] },
            "subtitle": { "runs": [ { "text": "Nov 19" } ] }
          }
        }"""

    @Test
    fun showPage_marksEpisodesWithIngestedArt() {
        val page = Json.parseToJsonElement(
            """{ "contents": [
                ${showRow("rss00000001", "https://lh3.googleusercontent.com/youtube-podcasts-ingestion-proxy/ALXq=w544-h544-l90-rj")},
                ${showRow("yt000000001", "https://i.ytimg.com/vi/yt000000001/hqdefault.jpg")}
            ] }""",
        )
        val songs = InnertubeParser.collectSongsDeep(page, "Planet Money")

        assertEquals(2, songs.size)
        assertTrue(ExternalPodcasts.contains("rss00000001"))
        assertFalse(ExternalPodcasts.contains("yt000000001"))
    }
}
