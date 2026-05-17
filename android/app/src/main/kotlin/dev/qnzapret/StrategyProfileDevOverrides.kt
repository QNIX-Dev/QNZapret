package dev.qnzapret

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

internal object StrategyProfileDevOverrides {
    fun apply(context: Context, profile: StrategyProfile): StrategyProfile {
        val source = relayConfigCandidates(context).firstOrNull { file ->
            file.isFile && file.canRead()
        } ?: return profile

        return try {
            val policies = StrategyProfileCodec.endpointPoliciesFromJson(source.readText(Charsets.UTF_8))
            if (policies.isEmpty()) {
                Log.d(LOG_TAG, "telegram relay override ignored path=${source.safePath()} reason=no_endpoint_policies")
                profile
            } else {
                Log.d(
                    LOG_TAG,
                    "telegram relay override loaded path=${source.safePath()} policies=${policies.size} " +
                        "routes=${policies.joinToString(separator = ",") { it.safeRouteLabel() }}",
                )
                profile.copy(endpointPolicies = policies)
            }
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "telegram relay override failed path=${source.safePath()} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            profile
        }
    }

    private fun relayConfigCandidates(context: Context): List<File> {
        return buildList {
            context.getExternalFilesDir(null)?.let { externalFiles ->
                add(File(externalFiles, RELAY_CONFIG_RELATIVE_PATH))
            }
            add(File(context.filesDir, RELAY_CONFIG_RELATIVE_PATH))
            add(File(context.cacheDir, RELAY_CONFIG_RELATIVE_PATH))
        }
    }

    private fun StrategyEndpointPolicy.safeRouteLabel(): String {
        return "${id.ifBlank { "-" }}:${route.protocol.wireValue}@${route.host}:${route.port}"
    }

    private fun File.safePath(): String {
        return try {
            canonicalPath
        } catch (_: IOException) {
            absolutePath
        }
    }

    private const val LOG_TAG = "QNZapretService"
    private const val RELAY_CONFIG_RELATIVE_PATH = "qnzapret/telegram_relay.json"
}
