// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTest {
    private val app = CatalogApp(
        id = "com.nuvio.tv", label = "Nuvio", summary = "", category = "Video",
        versionCode = 10, versionName = "1.0", url = "", size = 0, sha256 = "", signer = "aa", icon = null,
    )

    @Test
    fun notInstalled() = assertEquals(Status.NotInstalled, Status.of(app, null))

    @Test
    fun olderVersionIsAnUpdate() =
        assertEquals(Status.UpdateAvailable("0.9", "1.0"), Status.of(app, InstalledPackage(9, "0.9", "aa")))

    @Test
    fun sameOrNewerIsUpToDate() {
        assertEquals(Status.UpToDate("1.0"), Status.of(app, InstalledPackage(10, "1.0", "aa")))
        assertEquals(Status.UpToDate("1.1-beta"), Status.of(app, InstalledPackage(11, "1.1-beta", "aa")))
    }

    @Test
    fun otherSignerCanNotBeUpdated() =
        assertEquals(Status.OtherSigner("0.9"), Status.of(app, InstalledPackage(9, "0.9", "bb")))
}
