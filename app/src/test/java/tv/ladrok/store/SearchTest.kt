// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun app(id: String, label: String, summary: String = "", category: String = "Apps") =
        CatalogApp(id, label, summary, category, 1, "1", "https://x/$id.apk", 1, "00", "00", null)

    private val catalogs = mapOf(
        Shelf.CURATED to Catalog("", listOf(app("org.jellyfin.androidtv", "Jellyfin", "Your media server", "Video"))),
        Shelf.FOSS to Catalog("", listOf(
            app("org.xbmc.kodi", "Kodi", "Media center for your Jellyfin and local files", "Video"),
            app("org.jellyfin.androidtv", "Jellyfin", "Duplicate of the curated one"),
            app("com.termux", "Termux", "Terminal emulator", "Tools"),
            app("de.blinkt.openvpn", "OpenVPN für Android", "VPN", "Netzwerk"),
        )),
    )

    private fun ids(query: String) = search(query, catalogs).map { it.app.id }

    @Test
    fun matchesNameFirstThenOtherText() =
        assertEquals(listOf("org.jellyfin.androidtv", "org.xbmc.kodi"), ids("jelly"))

    @Test
    fun anAppInBothCatalogsShowsOnceAsCurated() {
        val found = search("jellyfin", catalogs).filter { it.app.id == "org.jellyfin.androidtv" }
        assertEquals(listOf(Shelf.CURATED), found.map { it.shelf })
    }

    @Test
    fun everyWordMustMatchIgnoringCaseAndAccents() {
        assertEquals(listOf("de.blinkt.openvpn"), ids("FUR vpn"))
        assertEquals(listOf("org.xbmc.kodi"), ids("media local"))
    }

    @Test
    fun matchesCategoryAndPackageName() {
        assertEquals(listOf("com.termux"), ids("tools"))
        assertEquals(listOf("org.xbmc.kodi"), ids("xbmc"))
    }

    @Test
    fun blankQueryFindsNothing() = assertTrue(ids("  ").isEmpty())
}
