package dev.qnzapret

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress

internal object UnderlyingNetworkSelector {
    @Suppress("DEPRECATION")
    fun select(context: Context): Network? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return connectivityManager.allNetworks
            .mapNotNull { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    return@mapNotNull null
                }
                network to score(capabilities)
            }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    fun resolveDnsServers(context: Context, network: Network): List<InetAddress> {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return connectivityManager.getLinkProperties(network)
            ?.dnsServers
            .orEmpty()
            .filter { dnsServer -> !dnsServer.isAnyLocalAddress && !dnsServer.isLoopbackAddress }
            .distinctBy { dnsServer -> dnsServer.hostAddress }
    }

    private fun score(capabilities: NetworkCapabilities): Int {
        var score = 0
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            score += 100
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            score += 50
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            score += 45
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            score += 35
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            score += 5
        }
        return score
    }
}
