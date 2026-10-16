// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsTest {
    @Test
    fun notesLoseHeadingsLinksAndMarks() = assertEquals(
        "Thanks to everyone\n· Fix the player\n· See docs",
        plainNotes("# NuvioTV 1.0.0 \r\n\r\nThanks to **everyone**\r\n\r\n## Fixes\r\n- Fix the `player`\r\n* See [docs](https://x)"),
    )

    @Test
    fun sourceNamesGithubRepoFdroidOrSite() {
        assertEquals("GitHub · NuvioMedia/NuvioTV", sourceOf("https://github.com/NuvioMedia/NuvioTV/releases/download/1.0.0/a.apk"))
        assertEquals("F-Droid", sourceOf("https://f-droid.org/repo/com.tailscale.ipn_298177930.apk"))
        assertEquals("strem.io", sourceOf("https://dl.strem.io/android/v1.10.4-androidTV/x.apk"))
    }

    @Test
    fun sizesInSiUnits() {
        assertEquals("74 MB", sizeText(73_746_627))
        assertEquals("2.5 MB", sizeText(2_472_237))
        assertEquals("800 kB", sizeText(800_000))
    }

    @Test
    fun fossCategoriesFoldIntoEightGroups() {
        assertEquals("Watch & listen", FossGroups.of("Streaming - IPTV"))
        assertEquals("Watch & listen", FossGroups.of("Music Player"))
        assertEquals("Network & privacy", FossGroups.of("DNS Proxy"))
        assertEquals("Home screen & input", FossGroups.of("Screen Saver"))
        assertEquals(FossGroups.OTHER, FossGroups.of("Something New"))
    }

    @Test
    fun groupsLargestFirstOtherLastNamesSorted() {
        fun app(label: String, category: String) =
            CatalogApp(label, label, "", category, 1, "1", "https://x", 1, "00", "00", null)
        val groups = FossGroups.group(listOf(
            app("Zed", "Something New"),
            app("Kodi", "Media Player"),
            app("ARVIO", "Streaming - Movies/TV/Anime"),
            app("Termux", "System Utilities"),
            app("A", "Unknown"),
            app("B", "Unknown 2"),
        ))
        assertEquals(listOf("Watch & listen", "Tools", FossGroups.OTHER), groups.map { it.first })
        assertEquals(listOf("ARVIO", "Kodi"), groups[0].second.map { it.label })
    }
}
