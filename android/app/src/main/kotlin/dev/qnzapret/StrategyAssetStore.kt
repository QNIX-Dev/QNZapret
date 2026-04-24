package dev.qnzapret

import android.content.Context

internal data class StrategyAssetBundle(
    val hostlists: Map<String, HostlistMatcher>,
    val blobs: Map<String, ByteArray>,
) {
    val hostlistCount: Int
        get() = hostlists.size

    val blobCount: Int
        get() = blobs.size
}

internal object StrategyAssetStore {
    fun load(context: Context, profile: StrategyProfile): StrategyAssetBundle {
        val hostlists = profile.rules
            .flatMap { it.hostlists }
            .distinct()
            .associateWith { path -> AssetHostlistMatcher(context, path) }

        val blobs = profile.blobs.mapValues { (_, path) ->
            context.assets.open(path).use { it.readBytes() }
        }

        return StrategyAssetBundle(
            hostlists = hostlists,
            blobs = blobs,
        )
    }
}

