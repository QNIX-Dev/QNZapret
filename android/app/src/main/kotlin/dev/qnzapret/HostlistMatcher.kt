package dev.qnzapret

import android.content.Context
import java.util.Locale

internal interface HostlistMatcher {
    val path: String
    val loadedEntryCount: Int?

    fun matches(host: String?): Boolean
}

internal class AssetHostlistMatcher(
    private val context: Context,
    override val path: String,
) : HostlistMatcher {
    @Volatile
    private var entries: Set<String>? = null

    override val loadedEntryCount: Int?
        get() = entries?.size

    override fun matches(host: String?): Boolean {
        val normalizedHost = normalizeHost(host) ?: return false
        val loadedEntries = entries ?: loadEntries()

        if (loadedEntries.contains(normalizedHost)) {
            return true
        }

        var dotIndex = normalizedHost.indexOf('.')
        while (dotIndex >= 0 && dotIndex < normalizedHost.lastIndex) {
            val parentDomain = normalizedHost.substring(dotIndex + 1)
            if (loadedEntries.contains(parentDomain)) {
                return true
            }
            dotIndex = normalizedHost.indexOf('.', dotIndex + 1)
        }

        return false
    }

    private fun loadEntries(): Set<String> {
        synchronized(this) {
            entries?.let { return it }

            val loaded = context.assets.open(path).bufferedReader().useLines { lines ->
                lines
                    .mapNotNull(::normalizeHostlistLine)
                    .toHashSet()
            }

            entries = loaded
            return loaded
        }
    }

    private companion object {
        fun normalizeHostlistLine(line: String): String? {
            val withoutComment = line.substringBefore('#').trim()
            if (withoutComment.isBlank() || withoutComment.startsWith("!")) {
                return null
            }

            return normalizeHost(
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
}
