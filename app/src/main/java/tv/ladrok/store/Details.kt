// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import java.net.URI
import java.util.Locale

/**
 * Release notes as plain text for the App's page: Markdown headings dropped (they repeat the name
 * and version), links reduced to their text, emphasis and list marks removed, blank lines folded.
 */
fun plainNotes(markdown: String): String = markdown
    .replace("\r", "")
    .lines()
    .map { it.trim() }
    .filterNot { it.startsWith("#") || it.startsWith("<!--") || it.matches(Regex("[-*_=]{3,}")) }
    .map { line ->
        line
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "") // images
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1") // links
            .replace(Regex("<[^>]+>"), "") // HTML tags
            .replace(Regex("(\\*\\*|__|`)"), "")
            .replace(Regex("^[-*+]\\s+"), "· ")
            .trim()
    }
    .joinToString("\n")
    .replace(Regex("\n{2,}"), "\n")
    .trim()

/** Where an App's APK comes from, in a few words: "GitHub · owner/repo", "F-Droid", or the site. */
fun sourceOf(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val host = uri.host.orEmpty().lowercase(Locale.ROOT)
    val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    return when {
        host == "github.com" && parts.size >= 2 -> "GitHub · ${parts[0]}/${parts[1]}"
        host == "f-droid.org" || host.endsWith(".f-droid.org") -> "F-Droid"
        else -> host.removePrefix("www.").removePrefix("dl.")
    }
}

/** A download size the way a TV shows it: "74 MB", "2.5 MB", "800 kB" (SI units, like Android's Settings). */
fun sizeText(bytes: Long): String = when {
    bytes >= 10_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
    bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    else -> "${(bytes + 500) / 1000} kB"
}

/**
 * The FOSS Catalog's groups: the community list's 22 categories (kept as they are in foss.json)
 * folded into 8 a viewer recognises. A category the list adds later lands in "Other" until it is
 * mapped here.
 */
object FossGroups {
    const val OTHER = "Other"

    val ORDER = listOf(
        "Watch & listen",
        "Files & downloads",
        "Tools",
        "Network & privacy",
        "Home screen & input",
        "Photos & camera",
        "App stores",
        "Web browsers",
        OTHER,
    )

    private val groups = mapOf(
        "media player" to "Watch & listen",
        "music player" to "Watch & listen",
        "file browser" to "Files & downloads",
        "download manager" to "Files & downloads",
        "system utilities" to "Tools",
        "vpn" to "Network & privacy",
        "connectivity" to "Network & privacy",
        "dns proxy" to "Network & privacy",
        "launcher" to "Home screen & input",
        "keyboard" to "Home screen & input",
        "remote" to "Home screen & input",
        "screen saver" to "Home screen & input",
        "gallery" to "Photos & camera",
        "capturing" to "Photos & camera",
        "app store" to "App stores",
        "web browser" to "Web browsers",
    )

    /** The group a list category goes in. Every "Streaming - …" category is Watch & listen. */
    fun of(category: String): String {
        val key = category.trim().lowercase(Locale.ROOT)
        if (key.startsWith("streaming")) return "Watch & listen"
        return groups[key] ?: OTHER
    }

    /** [apps] by group, largest group first (ties in [ORDER], Other last), each sorted by name. Empty groups are left out. */
    fun group(apps: List<CatalogApp>): List<Pair<String, List<CatalogApp>>> =
        apps.groupBy { of(it.category) }
            .mapValues { (_, list) -> list.sortedBy { it.label.lowercase(Locale.ROOT) } }
            .toList()
            .sortedWith(compareByDescending<Pair<String, List<CatalogApp>>> { it.second.size }.thenBy { ORDER.indexOf(it.first) })
            .let { groups -> groups.filter { it.first != OTHER } + groups.filter { it.first == OTHER } }
}
