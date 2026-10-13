package dev.qnzapret

/** Only transformations that preserve the application byte stream are safe here. */
internal object NonRootTcpTransform {
    fun isTelegramWebTls(host: String?): Boolean =
        host?.lowercase()?.trimEnd('.')?.endsWith(".web.telegram.org") == true

    fun apply(payload: ByteArray, protocol: StrategyProtocol?, actions: List<ResolvedStrategyAction>): List<ByteArray> {
        var chunks = listOf(payload)
        // FAKE needs independent raw TCP sequence/checksum handling, unavailable
        // to this non-root socket path. Never prepend fake bytes to a real stream.
        actions.filter { it.kind == StrategyActionKind.SPLIT }.forEach { action ->
            val position = action.position ?: 1
            chunks = chunks.flatMap { chunk ->
                val tls = if (protocol == StrategyProtocol.TLS) {
                    TlsRecordSplitTransform.splitFirstHandshakeRecord(chunk, position)
                } else null
                when {
                    // Separate writes, not two records concatenated into one write:
                    // a small ClientHello otherwise remains in one TCP segment.
                    tls != null -> splitTlsWrites(chunk, tls, position)
                    position <= 0 || position >= chunk.size -> listOf(chunk)
                    else -> listOf(chunk.copyOfRange(0, position), chunk.copyOfRange(position, chunk.size))
                }
            }
        }
        return chunks
    }

    private fun splitTlsWrites(original: ByteArray, tls: ByteArray, position: Int): List<ByteArray> {
        val firstEnd = 5 + position
        val sni = TlsClientHelloDetector.detectSniRange(original)
        val originalMid = sni?.let { it.first + (it.last - it.first + 1) / 2 }
        val wireMid = originalMid?.let { it + if (it >= firstEnd) 5 else 0 }
        // Keep the hostname itself across writes: splitting only the handshake
        // type left the full SNI in one small mobile ClientHello segment.
        val boundaries = if (wireMid != null && wireMid > firstEnd && wireMid < tls.size) {
            listOf(0, firstEnd, wireMid, tls.size)
        } else listOf(0, firstEnd, tls.size)
        return boundaries.zipWithNext { start, end -> tls.copyOfRange(start, end) }
    }
}
