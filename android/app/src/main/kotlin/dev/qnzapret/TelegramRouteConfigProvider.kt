package dev.qnzapret

import android.content.Context
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal data class TelegramRouteConfigSnapshot(
    val config: TelegramWebSocketRouteConfig,
    val status: TelegramRouteStatus,
    val message: String,
    val localDomainCount: Int,
    val publicDomainCount: Int,
    val activeDomain: String? = null,
)

internal enum class TelegramRouteStatus {
    MISSING,
    PROBING,
    READY,
    FAILED,
}

internal class TelegramRouteConfigProvider(
    private val service: VpnService,
    private val onSnapshotUpdated: ((TelegramRouteConfigSnapshot) -> Unit)? = null,
) {
    private val refreshRunning = AtomicBoolean(false)

    @Volatile
    private var currentSnapshot: TelegramRouteConfigSnapshot = missingSnapshot()

    @Synchronized
    fun loadAndProbeAsync(): TelegramRouteConfigSnapshot {
        val localConfig = loadLocalConfig()
        val cachedPublicDomains = loadCachedPublicDomains()
        val initialSnapshot = buildInitialSnapshot(localConfig, cachedPublicDomains)
        currentSnapshot = initialSnapshot
        Log.d(LOG_TAG, "telegram route provider load source=signed count=0 status=placeholder")
        startBackgroundRefresh()
        return initialSnapshot
    }

    private fun startBackgroundRefresh() {
        if (!refreshRunning.compareAndSet(false, true)) {
            return
        }
        Thread(
            {
                try {
                    val localConfig = loadLocalConfig()
                    val publicDomains = loadPublicDomains()
                    val probingSnapshot = buildInitialSnapshot(localConfig, publicDomains)
                    publish(probingSnapshot)
                    if (probingSnapshot.config.cfDomains.isEmpty()) {
                        Log.d(LOG_TAG, "telegram route provider load source=combined count=0 status=missing")
                        return@Thread
                    }
                    val probe = probeDomains(probingSnapshot.config)
                    val resultSnapshot = if (probe != null) {
                        probingSnapshot.copy(
                            status = TelegramRouteStatus.READY,
                            message = "Telegram route ready: ${probe.domain}.",
                            activeDomain = probe.domain,
                        )
                    } else {
                        probingSnapshot.copy(
                            status = TelegramRouteStatus.FAILED,
                            message = "Telegram route failed: ${probingSnapshot.config.cfDomains.size} доменов загружены, probe не дал HTTP 101.",
                        )
                    }
                    publish(resultSnapshot)
                } finally {
                    refreshRunning.set(false)
                }
            },
            "QNZapretTgRouteProvider",
        ).apply { isDaemon = true }.start()
    }

    private fun buildInitialSnapshot(
        localConfig: LocalRouteConfig,
        publicDomains: List<String>,
    ): TelegramRouteConfigSnapshot {
        val localDomains = normalizeDomains(localConfig.domains)
        val domains = normalizeDomains(localDomains + publicDomains)
        val localDomainCount = localDomains.size.coerceAtMost(domains.size)
        val publicDomainCount = (domains.size - localDomainCount).coerceAtLeast(0)

        if (domains.isEmpty()) {
            return missingSnapshot(
                tlsVerify = localConfig.tlsVerify,
                cfPriority = localConfig.cfPriority,
            )
        }

        return TelegramRouteConfigSnapshot(
            config = TelegramWebSocketRouteConfig(
                cfDomains = domains,
                cfPriority = localConfig.cfPriority,
                tlsVerify = localConfig.tlsVerify,
                localDomainCount = localDomainCount,
            ),
            status = TelegramRouteStatus.PROBING,
            message = "Telegram route probing: ${domains.size} доменов.",
            localDomainCount = localDomainCount,
            publicDomainCount = publicDomainCount,
        )
    }

    fun currentConfig(): TelegramWebSocketRouteConfig = currentSnapshot.config

    fun currentSnapshot(): TelegramRouteConfigSnapshot = currentSnapshot

    private fun publish(snapshot: TelegramRouteConfigSnapshot) {
        currentSnapshot = snapshot
        onSnapshotUpdated?.invoke(snapshot)
    }

    private fun loadLocalConfig(): LocalRouteConfig {
        val configFile = routeConfigFiles().firstOrNull { file -> file.exists() }
            ?: return LocalRouteConfig()
        return try {
            val json = JSONObject(configFile.readText())
            val domains = normalizeDomains(json.optJSONArray("cfDomains").toStringList())
            val priority = json.optBoolean("cfPriority", true)
            val tlsVerify = json.optBoolean("tlsVerify", true)
            Log.d(
                LOG_TAG,
                "telegram route provider load source=local count=${domains.size} " +
                    "path=${configFile.absolutePath} cfPriority=$priority tlsVerify=$tlsVerify",
            )
            LocalRouteConfig(
                domains = domains,
                cfPriority = priority,
                tlsVerify = tlsVerify,
            )
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "telegram route provider load source=local count=0 path=${configFile.absolutePath} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            LocalRouteConfig()
        }
    }

    private fun loadPublicDomains(): List<String> {
        val cache = readPublicCache()
        if (cache != null && cache.isFresh) {
            Log.d(
                LOG_TAG,
                "telegram route provider load source=cache count=${cache.domains.size} " +
                    "ageMs=${System.currentTimeMillis() - cache.fetchedAtMs}",
            )
            return cache.domains
        }

        val fetched = fetchPublicDomains()
        if (fetched.isNotEmpty()) {
            writePublicCache(fetched)
            Log.d(LOG_TAG, "telegram route provider load source=upstream count=${fetched.size}")
            return fetched
        }

        if (cache != null && cache.domains.isNotEmpty()) {
            Log.d(
                LOG_TAG,
                "telegram route provider load source=cache count=${cache.domains.size} stale=true",
            )
            return cache.domains
        }

        Log.d(LOG_TAG, "telegram route provider load source=upstream count=0 fallback=local_only")
        return emptyList()
    }

    private fun loadCachedPublicDomains(): List<String> {
        val cache = readPublicCache() ?: return emptyList()
        Log.d(
            LOG_TAG,
            "telegram route provider load source=cache count=${cache.domains.size} " +
                "stale=${!cache.isFresh}",
        )
        return cache.domains
    }

    private fun fetchPublicDomains(): List<String> {
        val url = "$PUBLIC_DOMAINS_URL?${SystemClock.elapsedRealtime()}"
        Log.d(LOG_TAG, "telegram route provider fetch start url=$PUBLIC_DOMAINS_URL")
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "QNZapret-Android")
                setRequestProperty("Accept", "text/plain")
            }
            connection.inputStream.use { input ->
                val text = input.bufferedReader(Charsets.UTF_8).readText()
                val encoded = text.lineSequence()
                    .map { line -> line.trim() }
                    .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
                    .toList()
                val decoded = normalizeDomains(encoded.mapNotNull { value ->
                    try {
                        TelegramRouteDomainCodec.decodeFlowsealDomain(value)
                    } catch (error: Exception) {
                        Log.d(
                            LOG_TAG,
                            "telegram route provider decode failed value=$value " +
                                "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
                        )
                        null
                    }
                })
                if (decoded.size < MIN_VALID_PUBLIC_DOMAINS) {
                    Log.d(
                        LOG_TAG,
                        "telegram route provider fetch ignored reason=low_quality " +
                            "encoded=${encoded.size} domains=${decoded.size}",
                    )
                    return emptyList()
                }
                Log.d(LOG_TAG, "telegram route provider fetch ok domains=${decoded.size}")
                decoded
            }
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "telegram route provider fetch failed url=$PUBLIC_DOMAINS_URL " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            emptyList()
        }
    }

    private fun readPublicCache(): PublicDomainCache? {
        val file = publicCacheFile()
        if (!file.exists()) {
            return null
        }
        return try {
            val json = JSONObject(file.readText())
            val fetchedAtMs = json.optLong("fetchedAtMs", 0L)
            val domains = normalizeDomains(json.optJSONArray("domains").toStringList())
            PublicDomainCache(
                domains = domains,
                fetchedAtMs = fetchedAtMs,
                isFresh = fetchedAtMs > 0L &&
                    System.currentTimeMillis() - fetchedAtMs <= PUBLIC_CACHE_TTL_MS,
            )
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "telegram route provider cache ignored path=${file.absolutePath} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            null
        }
    }

    private fun writePublicCache(domains: List<String>) {
        val file = publicCacheFile()
        runCatching {
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("fetchedAtMs", System.currentTimeMillis())
                .put("source", PUBLIC_DOMAINS_URL)
                .put("domains", JSONArray(domains))
            file.writeText(json.toString())
        }.onFailure { error ->
            Log.d(
                LOG_TAG,
                "telegram route provider cache write failed path=${file.absolutePath} " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
        }
    }

    private fun probeDomains(config: TelegramWebSocketRouteConfig): TelegramRouteProbeResult? {
        val probeDomains = config.cfDomains.take(PROBE_DOMAIN_LIMIT)
        for (domain in probeDomains) {
            for (dcId in PROBE_DC_IDS) {
                val result = TelegramWebSocketTransport.probeCloudflareDomain(
                    service = service,
                    domain = domain,
                    dcId = dcId,
                    mediaDc = dcId == MEDIA_PROBE_DC,
                    tlsVerify = config.tlsVerify,
                    timeoutMs = PROBE_TIMEOUT_MS,
                )
                if (result.success) {
                    return result
                }
            }
        }
        return null
    }

    private fun missingSnapshot(
        tlsVerify: Boolean = true,
        cfPriority: Boolean = true,
    ): TelegramRouteConfigSnapshot {
        return TelegramRouteConfigSnapshot(
            config = TelegramWebSocketRouteConfig(
                cfDomains = emptyList(),
                cfPriority = cfPriority,
                tlsVerify = tlsVerify,
            ),
            status = TelegramRouteStatus.MISSING,
            message = "Telegram route config missing.",
            localDomainCount = 0,
            publicDomainCount = 0,
        )
    }

    private fun routeConfigFiles(): List<File> {
        return buildList {
            service.getExternalFilesDir(null)?.let { root ->
                add(File(root, ROUTE_CONFIG_PATH))
            }
            add(File(service.filesDir, ROUTE_CONFIG_PATH))
            add(File(service.cacheDir, ROUTE_CONFIG_PATH))
        }
    }

    private fun publicCacheFile(): File = File(service.filesDir, PUBLIC_CACHE_PATH)

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }
    }

    private data class LocalRouteConfig(
        val domains: List<String> = emptyList(),
        val cfPriority: Boolean = true,
        val tlsVerify: Boolean = true,
    )

    private data class PublicDomainCache(
        val domains: List<String>,
        val fetchedAtMs: Long,
        val isFresh: Boolean,
    )

    private companion object {
        private const val LOG_TAG = "QNZapretTgCompat"
        private const val ROUTE_CONFIG_PATH = "qnzapret/telegram_compat.json"
        private const val PUBLIC_CACHE_PATH = "qnzapret/telegram_cf_domains_cache.json"
        private const val PUBLIC_DOMAINS_URL =
            "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
        private const val PUBLIC_CACHE_TTL_MS = 12 * 60 * 60 * 1000L
        private const val FETCH_TIMEOUT_MS = 5_000
        private const val PROBE_TIMEOUT_MS = 3_500
        private const val PROBE_DOMAIN_LIMIT = 6
        private const val MIN_VALID_PUBLIC_DOMAINS = 3
        private const val MEDIA_PROBE_DC = 4

        private val PROBE_DC_IDS = listOf(2, 4)
    }
}

internal object TelegramRouteDomainCodec {
    fun decodeFlowsealDomain(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.endsWith(".com", ignoreCase = true)) {
            return trimmed
        }
        val prefix = trimmed.dropLast(4)
        val alphaCount = prefix.count { char -> char.isLetter() }
        val decodedPrefix = buildString {
            prefix.forEach { char ->
                append(
                    if (char in 'a'..'z' || char in 'A'..'Z') {
                        val base = if (char >= 'a') 'a'.code else 'A'.code
                        (((char.code - base - alphaCount) % 26 + 26) % 26 + base).toChar()
                    } else {
                        char
                    },
                )
            }
        }
        return "$decodedPrefix.co.uk"
    }
}

private fun normalizeDomains(domains: List<String>): List<String> {
    val seen = linkedSetOf<String>()
    domains.forEach { domain ->
        val normalized = domain.trim()
            .trim('.')
            .lowercase(Locale.US)
        if (isValidDomain(normalized)) {
            seen += normalized
        }
    }
    return seen.toList()
}

private fun isValidDomain(domain: String): Boolean {
    if (domain.isEmpty() || domain.length > 253) {
        return false
    }
    val labels = domain.split('.')
    if (labels.size < 2) {
        return false
    }
    labels.forEach { label ->
        if (label.isEmpty() || label.length > 63) {
            return false
        }
        if (label.first() == '-' || label.last() == '-') {
            return false
        }
        if (!label.all { char -> char.isLetterOrDigit() || char == '-' }) {
            return false
        }
    }
    val tld = labels.last()
    return tld.length >= 2 && tld.any { char -> char.isLetter() }
}
