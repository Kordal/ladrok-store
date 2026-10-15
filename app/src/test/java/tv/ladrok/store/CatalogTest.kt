// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class CatalogTest {
    private val json = """
        {"format": 1, "generated": "2026-10-15T12:00:00Z", "apps": [
          {"id": "com.nuvio.tv", "label": "Nuvio", "summary": "Streams", "category": "Video",
           "versionCode": 42, "versionName": "1.2.0", "url": "https://example.com/nuvio.apk", "size": 1000,
           "sha256": "AB12", "signer": "CD34", "icon": "https://example.com/nuvio.png"},
          {"id": "com.tailscale.ipn", "label": "Tailscale", "category": "Network",
           "versionCode": 7, "versionName": "1.90", "url": "https://example.com/ts.apk", "size": 2000,
           "sha256": "ef", "signer": "01"},
          {"id": "com.stremio.one", "label": "Stremio", "category": "Video",
           "versionCode": 3, "versionName": "1.9", "url": "https://example.com/s.apk", "size": 3000,
           "sha256": "aa", "signer": "bb", "icon": ""}
        ]}
    """.trimIndent()

    @Test
    fun parsesApps() {
        val catalog = Catalog.parse(json)
        assertEquals(3, catalog.apps.size)
        val nuvio = catalog.apps[0]
        assertEquals("com.nuvio.tv", nuvio.id)
        assertEquals(42L, nuvio.versionCode)
        assertEquals("ab12", nuvio.sha256) // hashes compare in lowercase
        assertEquals("cd34", nuvio.signer)
        assertEquals("", catalog.apps[1].summary)
        assertNull(catalog.apps[2].icon) // an empty icon means none
    }

    @Test
    fun rowsKeepCatalogOrder() {
        val rows = Catalog.parse(json).rows()
        assertEquals(listOf("Video", "Network"), rows.map { it.first })
        assertEquals(listOf("com.nuvio.tv", "com.stremio.one"), rows[0].second.map { it.id })
    }

    @Test
    fun refusesOtherFormats() {
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse("""{"format": 2, "apps": []}""") }
    }

    @Test
    fun verifiesRsaAndEcSignatures() {
        for ((algorithm, signing) in listOf("RSA" to "SHA256withRSA", "EC" to "SHA256withECDSA")) {
            val keys = KeyPairGenerator.getInstance(algorithm).genKeyPair()
            val data = json.toByteArray()
            val signature = Signature.getInstance(signing).run {
                initSign(keys.private)
                update(data)
                sign()
            }
            assertTrue(algorithm, Catalog.verify(data, signature, keys.public))
            val tampered = json.replace("1.2.0", "6.6.6").toByteArray()
            assertFalse(algorithm, Catalog.verify(tampered, signature, keys.public))
            val other = KeyPairGenerator.getInstance(algorithm).genKeyPair()
            assertFalse(algorithm, Catalog.verify(data, signature, other.public))
        }
    }

    @Test
    fun hexIsLowercase() {
        assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())
    }
}
