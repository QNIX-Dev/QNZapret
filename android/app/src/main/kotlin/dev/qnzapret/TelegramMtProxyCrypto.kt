package dev.qnzapret

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

internal data class TelegramMtProxyHandshake(
    val dcId: Int,
    val rawDcId: Int,
    val mediaDc: Boolean,
    val protocol: TelegramMtProxyTransportProtocol,
    val clientToProxyCipher: TelegramCtrCipher,
    val proxyToClientCipher: TelegramCtrCipher,
)

internal data class TelegramUpstreamObfuscation(
    val initPayload: ByteArray,
    val proxyToUpstreamCipher: TelegramCtrCipher,
    val upstreamToProxyCipher: TelegramCtrCipher,
)

internal class UnsupportedTelegramMtProxyTransportException(val markerHex: String) :
    IllegalArgumentException("Unsupported Telegram MTProxy transport marker marker=$markerHex")

internal enum class TelegramMtProxyTransportProtocol(val wireValue: String, val marker: ByteArray) {
    ABRIDGED("abridged", byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())),
    INTERMEDIATE("intermediate", byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())),
    PADDED_INTERMEDIATE("padded_intermediate", byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())),
}

internal class TelegramCtrCipher private constructor(private val cipher: Cipher) {
    @Synchronized
    fun transform(data: ByteArray, length: Int = data.size): ByteArray {
        return cipher.update(data, 0, length) ?: ByteArray(0)
    }

    companion object {
        fun create(key: ByteArray, iv: ByteArray): TelegramCtrCipher {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv),
            )
            return TelegramCtrCipher(cipher)
        }
    }
}

internal object TelegramMtProxyCrypto {
    fun acceptClient(initPayload: ByteArray, secretHex: String): TelegramMtProxyHandshake {
        require(initPayload.size == OBFUSCATION_INIT_SIZE) {
            "Telegram MTProxy init must be 64 bytes"
        }
        val secret = secretHexToBytes(secretHex)
        val reversed = initPayload.reversedArray()
        val clientToProxyKey = sha256(initPayload.copyOfRange(8, 40) + secret)
        val clientToProxyIv = initPayload.copyOfRange(40, 56)
        val proxyToClientKey = sha256(reversed.copyOfRange(8, 40) + secret)
        val proxyToClientIv = reversed.copyOfRange(40, 56)
        val clientToProxyCipher = TelegramCtrCipher.create(clientToProxyKey, clientToProxyIv)
        val decryptedInit = clientToProxyCipher.transform(initPayload)
        val protocol = protocolFromMarker(decryptedInit.copyOfRange(56, 60))
        val dc = dcIdFromEncryptedInit(decryptedInit)

        return TelegramMtProxyHandshake(
            dcId = dc.logicalId,
            rawDcId = dc.rawId,
            mediaDc = dc.rawId < 0,
            protocol = protocol,
            clientToProxyCipher = clientToProxyCipher,
            proxyToClientCipher = TelegramCtrCipher.create(proxyToClientKey, proxyToClientIv),
        )
    }

    fun createUpstream(
        protocol: TelegramMtProxyTransportProtocol,
        dcId: Int,
        mediaDc: Boolean,
    ): TelegramUpstreamObfuscation {
        val initPayload = generateInitPayload(
            protocol = protocol,
            dcId = dcId,
            mediaDc = mediaDc,
        )
        val reversed = initPayload.reversedArray()
        val proxyToUpstreamCipher = TelegramCtrCipher.create(
            key = initPayload.copyOfRange(8, 40),
            iv = initPayload.copyOfRange(40, 56),
        )
        val upstreamToProxyCipher = TelegramCtrCipher.create(
            key = reversed.copyOfRange(8, 40),
            iv = reversed.copyOfRange(40, 56),
        )
        val encryptedInit = proxyToUpstreamCipher.transform(initPayload)
        val finalInit = initPayload.copyOf()
        encryptedInit.copyInto(finalInit, destinationOffset = 56, startIndex = 56, endIndex = 64)
        return TelegramUpstreamObfuscation(
            initPayload = finalInit,
            proxyToUpstreamCipher = proxyToUpstreamCipher,
            upstreamToProxyCipher = upstreamToProxyCipher,
        )
    }

    fun secretHexToBytes(secretHex: String): ByteArray {
        val normalized = secretHex.trim()
        require(normalized.length == 32) { "Telegram MTProxy secret must be 32 hex chars" }
        val bytes = ByteArray(16)
        for (index in bytes.indices) {
            val start = index * 2
            bytes[index] = normalized.substring(start, start + 2).toInt(16).toByte()
        }
        return bytes
    }

    fun generateSecretHex(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateInitPayload(
        protocol: TelegramMtProxyTransportProtocol,
        dcId: Int,
        mediaDc: Boolean,
    ): ByteArray {
        val random = SecureRandom()
        val rawDcId = if (mediaDc) -dcId else dcId
        while (true) {
            val init = ByteArray(OBFUSCATION_INIT_SIZE)
            random.nextBytes(init)
            protocol.marker.copyInto(init, destinationOffset = 56)
            init[60] = (rawDcId and 0xff).toByte()
            init[61] = ((rawDcId shr 8) and 0xff).toByte()
            if (isForbiddenInit(init)) {
                continue
            }
            return init
        }
    }

    private fun protocolFromMarker(marker: ByteArray): TelegramMtProxyTransportProtocol {
        return TelegramMtProxyTransportProtocol.values().firstOrNull { protocol ->
            protocol.marker.contentEquals(marker)
        } ?: throw UnsupportedTelegramMtProxyTransportException(marker.toHex())
    }

    private fun dcIdFromEncryptedInit(decryptedInit: ByteArray): TelegramDcId {
        val raw = (decryptedInit[60].toInt() and 0xff) or (decryptedInit[61].toInt() shl 8)
        val signed = raw.toShort().toInt()
        val absDc = abs(signed)
        val logical = if (absDc > TEST_DC_OFFSET) absDc - TEST_DC_OFFSET else absDc
        return TelegramDcId(logicalId = logical, rawId = signed)
    }

    private fun isForbiddenInit(init: ByteArray): Boolean {
        if (init[0] == 0xef.toByte()) {
            return true
        }
        val first = init.copyOfRange(0, 4)
        val second = init.copyOfRange(4, 8)
        return FORBIDDEN_FIRST_INTS.any { it.contentEquals(first) } ||
            second.all { it == 0.toByte() }
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private const val OBFUSCATION_INIT_SIZE = 64
    private const val TEST_DC_OFFSET = 10000

    private data class TelegramDcId(
        val logicalId: Int,
        val rawId: Int,
    )

    private val FORBIDDEN_FIRST_INTS = listOf(
        byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte()),
        byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte()),
        byteArrayOf('P'.code.toByte(), 'O'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte()),
        byteArrayOf('G'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), ' '.code.toByte()),
        byteArrayOf('H'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte()),
        byteArrayOf('O'.code.toByte(), 'P'.code.toByte(), 'T'.code.toByte(), 'I'.code.toByte()),
    )
}

private fun ByteArray.toHex(): String {
    return joinToString("") { byte -> "%02x".format(byte) }
}
