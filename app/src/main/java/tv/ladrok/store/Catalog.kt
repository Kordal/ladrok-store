// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import org.json.JSONObject
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

/**
 * One App in the Catalog: a single APK the resolver already downloaded and checked in CI
 * (package name, signing certificate, TV launcher entry, ABI). The Store installs exactly this file.
 */
data class CatalogApp(
    /** The package name. */
    val id: String,
    val label: String,
    val summary: String,
    val category: String,
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val size: Long,
    /** SHA-256 of the APK, lowercase hex. */
    val sha256: String,
    /** SHA-256 of the APK's signing certificate, lowercase hex. */
    val signer: String,
    val icon: String?,
)

/** The curated list the Store shows, as published by the resolver. */
class Catalog(val generated: String, val apps: List<CatalogApp>) {

    /** The Apps grouped into rows by category, in Catalog order. */
    fun rows(): List<Pair<String, List<CatalogApp>>> = apps.groupBy { it.category }.toList()

    companion object {
        const val FORMAT = 1

        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val format = root.getInt("format")
            require(format == FORMAT) { "Catalog format $format; this Store reads $FORMAT. Update Ladrok Store." }
            val list = root.getJSONArray("apps")
            val apps = List(list.length()) { i ->
                val app = list.getJSONObject(i)
                CatalogApp(
                    id = app.getString("id"),
                    label = app.getString("label"),
                    summary = app.optString("summary"),
                    category = app.optString("category").ifEmpty { "Apps" },
                    versionCode = app.getLong("versionCode"),
                    versionName = app.getString("versionName"),
                    url = app.getString("url"),
                    size = app.getLong("size"),
                    sha256 = app.getString("sha256").lowercase(),
                    signer = app.getString("signer").lowercase(),
                    icon = app.optString("icon").ifEmpty { null },
                )
            }
            return Catalog(root.optString("generated"), apps)
        }

        /**
         * Whether [signature] over [data] was made with the private half of [key]. The resolver signs
         * with `openssl dgst -sha256 -sign`: PKCS#1 v1.5 for an RSA key, DER ECDSA for an EC key.
         */
        fun verify(data: ByteArray, signature: ByteArray, key: PublicKey): Boolean {
            val algorithm = when (key.algorithm) {
                "RSA" -> "SHA256withRSA"
                "EC" -> "SHA256withECDSA"
                else -> return false
            }
            return runCatching {
                Signature.getInstance(algorithm).run {
                    initVerify(key)
                    update(data)
                    verify(signature)
                }
            }.getOrDefault(false)
        }
    }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
