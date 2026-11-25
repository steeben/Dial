package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chips above a radio queue, trimmed from YouTube Music's `next` response. */
class RadioStylesTest {

    private fun chip(label: String, playlistId: String?, params: String? = null, selected: Boolean = false) = """
        { "chipCloudChipRenderer": {
            "text": { "runs": [ { "text": "$label" } ] },
            "navigationEndpoint": { "queueUpdateCommand": {
                "queueUpdateSection": "QUEUE_UPDATE_SECTION_QUEUE",
                "fetchContentsCommand": { "watchEndpoint": {
                    ${playlistId?.let { "\"playlistId\": \"$it\"" } ?: "\"videoId\": \"x\""}
                    ${params?.let { ", \"params\": \"$it\"" }.orEmpty()}
                } }
            } },
            "isSelected": $selected,
            "uniqueId": "$label"
        } }
    """.trimIndent()

    private fun queue(vararg chips: String) = Json.parseToJsonElement(
        """
        { "contents": { "singleColumnMusicWatchNextResultsRenderer": { "tabbedRenderer": {
            "watchNextTabbedResultsRenderer": { "tabs": [ { "tabRenderer": { "content": {
                "musicQueueRenderer": {
                    "content": { "playlistPanelRenderer": { "contents": [] } },
                    "subHeaderChipCloud": { "chipCloudRenderer": { "chips": [ ${chips.joinToString(",")} ] } }
                }
            } } } ] }
        } } } }
        """.trimIndent(),
    )

    @Test
    fun `each chip is a style with the queue that gives its songs`() {
        val styles = InnertubeParser.parseRadioStyles(
            queue(
                chip("All", "RDAMVMabc", "ggUM", selected = true),
                chip("Popular", "RDATpYvabc", "ggU4"),
                chip("Deep cuts", "RDATpXvabc", "ggU0"),
                chip("1990s", "RDATd_vabc"),
            ),
        )
        assertEquals(listOf("All", "Popular", "Deep cuts", "1990s"), styles.map { it.label })
        assertEquals(listOf("All", "Popular", "Deep cuts", "1990s"), styles.map { it.id })
        assertEquals("RDATpYvabc", styles[1].target.playlistId)
        assertEquals("ggU4", styles[1].target.params)
        assertNull(styles[3].target.params)
    }

    @Test
    fun `the selected chip is the default style`() {
        val styles = InnertubeParser.parseRadioStyles(
            queue(chip("All", "RDAMVMabc", selected = true), chip("Popular", "RDATpYvabc")),
        )
        assertTrue(styles[0].isDefault)
        assertFalse(styles[1].isDefault)
    }

    @Test
    fun `a chip with no queue behind it is left out`() {
        val styles = InnertubeParser.parseRadioStyles(
            queue(chip("All", "RDAMVMabc", selected = true), chip("Broken", null)),
        )
        assertEquals(listOf("All"), styles.map { it.label })
    }

    @Test
    fun `a queue that has no chips has no styles`() {
        val album = Json.parseToJsonElement(
            """{ "contents": { "musicQueueRenderer": { "content": { "playlistPanelRenderer": { "contents": [] } } } } }""",
        )
        assertTrue(InnertubeParser.parseRadioStyles(album).isEmpty())
        assertTrue(InnertubeParser.parseRadioStyles(Json.parseToJsonElement("{}")).isEmpty())
    }
}
