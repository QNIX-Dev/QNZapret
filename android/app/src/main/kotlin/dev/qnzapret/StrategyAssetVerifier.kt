package dev.qnzapret

import android.content.Context
import java.io.IOException

internal data class StrategyAssetReport(
    val checkedPaths: Set<String>,
    val missingPaths: Set<String>,
) {
    val presentCount: Int
        get() = checkedPaths.size - missingPaths.size

    val missingCount: Int
        get() = missingPaths.size

    val isComplete: Boolean
        get() = missingPaths.isEmpty()
}

internal object StrategyAssetVerifier {
    fun verify(context: Context, profile: StrategyProfile): StrategyAssetReport {
        val paths = buildSet {
            addAll(profile.blobs.values)
            profile.rules.forEach { rule -> addAll(rule.hostlists) }
        }

        val missing = paths.filterNot { path -> context.assets.exists(path) }.toSet()
        return StrategyAssetReport(
            checkedPaths = paths,
            missingPaths = missing,
        )
    }

    private fun android.content.res.AssetManager.exists(path: String): Boolean {
        return try {
            open(path).use { }
            true
        } catch (_: IOException) {
            false
        }
    }
}
