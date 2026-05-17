package dev.qnzapret

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet6Address
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

    fun supportsIpv6(context: Context, network: Network): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return false
        return linkProperties.hasUsableIpv6Address() && linkProperties.hasIpv6DefaultRoute()
    }

    fun describeLink(context: Context, network: Network): String {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return "link=unavailable"
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return "link=unavailable"
        val addresses = linkProperties.linkAddresses.joinToString(separator = ",") { address ->
            address.address.hostAddress ?: "-"
        }.ifBlank { "-" }
        val dns = linkProperties.dnsServers.joinToString(separator = ",") { dnsServer ->
            dnsServer.hostAddress ?: "-"
        }.ifBlank { "-" }
        val privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            linkProperties.privateDnsServerName ?: "-"
        } else {
            "api<28"
        }
        return "addresses=$addresses dns=$dns privateDns=$privateDns"
    }

    private fun LinkProperties.hasUsableIpv6Address(): Boolean {
        return linkAddresses.any { linkAddress ->
            val address = linkAddress.address
            address is Inet6Address &&
                !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress
        }
    }

    private fun LinkProperties.hasIpv6DefaultRoute(): Boolean {
        return routes.any { route ->
            val destination = route.destination ?: return@any false
            destination.address is Inet6Address && destination.prefixLength == 0
        }
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
