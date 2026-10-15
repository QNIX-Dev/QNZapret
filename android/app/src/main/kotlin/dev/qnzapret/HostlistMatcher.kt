package dev.qnzapret

import android.content.Context
import java.util.Locale

internal object HostNameNormalizer {
    fun normalize(host: String?): String? {
        if (host.isNullOrBlank()) {
            return null
        }

        val normalized = host
            .trim()
            .trim('"', '\'')
            .substringAfter("://", host)
            .substringBefore('/')
            .substringBefore(':')
            .trimEnd('.')
            .lowercase(Locale.US)

        return normalized.takeIf { value ->
            value.isNotBlank() &&
                value.none { it.isWhitespace() } &&
                "." in value
        }
    }
}

internal interface HostlistMatcher {
    val path: String
    val loadedEntryCount: Int?

    fun matches(host: String?): Boolean
}

internal class AssetHostlistMatcher(
    private val context: Context,
    override val path: String,
) : HostlistMatcher {
    @Volatile private var index: CompactHostlistIndex? = null
    @Volatile private var indexAttempted = false
    private val fallbackCache = android.util.LruCache<String, Boolean>(512)

    override val loadedEntryCount: Int?
        get() = index?.size

    override fun matches(host: String?): Boolean {
        val normalizedHost = normalizeHost(host) ?: return false
        if (!indexAttempted) loadIndex()
        index?.let { return it.matches(normalizedHost) }
        synchronized(fallbackCache) { fallbackCache.get(normalizedHost)?.let { return it } }
        // Fallback для нестандартного asset: ограниченная память, без миллиона Strings.
        val matched = context.assets.open(path).bufferedReader().useLines { lines ->
            lines.mapNotNull(::normalizeHostlistLine).any { entry ->
                normalizedHost == entry || normalizedHost.endsWith(".$entry")
            }
        }
        synchronized(fallbackCache) { fallbackCache.put(normalizedHost, matched) }
        return matched
    }

    private fun loadIndex() = synchronized(this) {
        if (indexAttempted) return@synchronized
        index = runCatching {
            val manifest = context.assets.open("qnzapret/compact-hostlists/manifest.json")
                .bufferedReader().use { org.json.JSONObject(it.readText()) }
            val item = manifest.getJSONObject(path)
            val asset = item.getString("asset")
            require(asset.startsWith("qnzapret/compact-hostlists/") && !asset.contains(".."))
            val directory = java.io.File(context.filesDir, "hostlist-indexes").apply { mkdirs() }
            val file = java.io.File(directory, asset.substringAfterLast('/'))
            fun verified(): Boolean = file.exists() && file.length() == item.getLong("bytes") &&
                file.inputStream().use { input ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) } == item.getString("sha256")
                }
            if (!verified()) {
                val temporary = java.io.File(directory, file.name + ".tmp")
                context.assets.open(asset).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.renameTo(file)) { "Cannot publish hostlist index" }
            }
            check(verified()) { "Invalid cached hostlist index checksum" }
            CompactHostlistIndex.open(file)
        }.getOrNull()
        indexAttempted = true
    }

    private companion object {
        fun normalizeHostlistLine(line: String): String? {
            val withoutComment = line.substringBefore('#').trim()
            if (withoutComment.isBlank() || withoutComment.startsWith("!")) {
                return null
            }

            return HostNameNormalizer.normalize(
                withoutComment
                    .trim('"', '\'')
                    .removePrefix("||")
                    .removePrefix("*.")
                    .removePrefix(".")
                    .substringBefore('/')
                    .substringBefore('^')
                    .substringBefore('$')
                    .trim(),
            )
        }

        fun normalizeHost(host: String?): String? {
            return HostNameNormalizer.normalize(host)
        }
    }
}
