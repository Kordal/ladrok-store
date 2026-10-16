// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import java.text.Normalizer
import java.util.Locale

/** A search result: the App and the Catalog it is in. */
data class Found(val app: CatalogApp, val shelf: Shelf)

/**
 * The Apps of [catalogs] that match [query]: every word of it appears in the App's name, summary,
 * category or package name, ignoring case and accents. Name matches come first, then the curated
 * Catalog before the FOSS one, then Catalog order. An App in both Catalogs shows once, as curated.
 */
fun search(query: String, catalogs: Map<Shelf, Catalog>): List<Found> {
    val words = fold(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val seen = HashSet<String>()
    val found = ArrayList<Pair<Found, Boolean>>()
    for (shelf in Shelf.entries) {
        for (app in catalogs[shelf]?.apps.orEmpty()) {
            if (app.id in seen) continue
            val name = fold(app.label)
            val text = name + " " + fold(app.summary) + " " + fold(app.category) + " " + app.id.lowercase(Locale.ROOT)
            if (words.all { it in text }) {
                seen += app.id
                found += Found(app, shelf) to words.all { it in name }
            }
        }
    }
    // sortedBy is stable: within each group, curated before FOSS and Catalog order stay.
    return found.sortedBy { (_, inName) -> if (inName) 0 else 1 }.map { it.first }
}

private val marks = Regex("\\p{Mn}+")

/** Lowercase, without accents, with anything but letters and digits as spaces. */
internal fun fold(text: String): String =
    marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
