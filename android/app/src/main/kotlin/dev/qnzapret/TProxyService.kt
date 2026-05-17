package dev.qnzapret

import androidx.annotation.Keep

@Keep
internal object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @Keep
    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @Keep
    @JvmStatic
    external fun TProxyStopService()

    @Keep
    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
