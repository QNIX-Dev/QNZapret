package dev.qnzapret

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket

internal data class Socks5RelayTarget(
    val host: String,
    val port: Int,
    val inetAddress: InetAddress?,
)

internal data class Socks5RelayAuth(
    val username: String?,
    val password: String?,
)

internal data class Socks5RelayResult(
    val replyCode: Int,
)

internal class Socks5RelayException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal object Socks5RelayClient {
    fun connect(
        socket: Socket,
        target: Socks5RelayTarget,
        auth: Socks5RelayAuth?,
        timeoutMs: Int,
    ): Socks5RelayResult {
        val previousTimeoutMs = socket.soTimeout
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val credentials = auth?.takeIf { relayAuth ->
                !relayAuth.username.isNullOrEmpty() || !relayAuth.password.isNullOrEmpty()
            }

            val methods = if (credentials == null) {
                byteArrayOf(SOCKS_AUTH_NO_AUTH.toByte())
            } else {
                byteArrayOf(SOCKS_AUTH_NO_AUTH.toByte(), SOCKS_AUTH_USERNAME_PASSWORD.toByte())
            }
            output.write(SOCKS_VERSION)
            output.write(methods.size)
            output.write(methods)
            output.flush()

            val version = input.readByte()
            val method = input.readByte()
            if (version != SOCKS_VERSION) {
                throw Socks5RelayException(RELAY_PROTOCOL_ERROR, "SOCKS5 relay returned version $version")
            }
            when (method) {
                SOCKS_AUTH_NO_AUTH -> Unit
                SOCKS_AUTH_USERNAME_PASSWORD -> {
                    if (credentials == null) {
                        throw Socks5RelayException(RELAY_AUTH_FAILED, "SOCKS5 relay requested missing credentials")
                    }
                    authenticate(input, output, credentials)
                }
                SOCKS_AUTH_NO_ACCEPTABLE_METHODS -> {
                    throw Socks5RelayException(RELAY_AUTH_FAILED, "SOCKS5 relay rejected auth methods")
                }
                else -> {
                    throw Socks5RelayException(RELAY_PROTOCOL_ERROR, "SOCKS5 relay selected auth method $method")
                }
            }

            writeConnectRequest(output, target)
            val replyVersion = input.readByte()
            val replyCode = input.readByte()
            input.readByte()
            val addressType = input.readByte()
            if (replyVersion != SOCKS_VERSION) {
                throw Socks5RelayException(RELAY_PROTOCOL_ERROR, "SOCKS5 relay reply version $replyVersion")
            }
            if (replyCode != SOCKS_REPLY_SUCCEEDED) {
                throw Socks5RelayException(RELAY_TARGET_FAILED, "SOCKS5 relay target reply=$replyCode")
            }
            readBoundAddress(input, addressType)
            input.readPort()
            return Socks5RelayResult(replyCode = replyCode)
        } catch (error: Socks5RelayException) {
            throw error
        } catch (error: IOException) {
            throw Socks5RelayException(
                RELAY_PROTOCOL_ERROR,
                "SOCKS5 relay handshake failed: ${error.javaClass.simpleName}",
                error,
            )
        } finally {
            socket.soTimeout = previousTimeoutMs
        }
    }

    private fun authenticate(
        input: InputStream,
        output: java.io.OutputStream,
        auth: Socks5RelayAuth?,
    ) {
        val username = auth?.username.orEmpty().toByteArray(Charsets.UTF_8)
        val password = auth?.password.orEmpty().toByteArray(Charsets.UTF_8)
        if (username.size > BYTE_MASK || password.size > BYTE_MASK) {
            throw Socks5RelayException(RELAY_AUTH_FAILED, "SOCKS5 username/password is too long")
        }

        output.write(SOCKS_USERNAME_PASSWORD_VERSION)
        output.write(username.size)
        output.write(username)
        output.write(password.size)
        output.write(password)
        output.flush()

        val version = input.readByte()
        val status = input.readByte()
        if (version != SOCKS_USERNAME_PASSWORD_VERSION || status != SOCKS_AUTH_STATUS_SUCCESS) {
            throw Socks5RelayException(RELAY_AUTH_FAILED, "SOCKS5 username/password auth failed")
        }
    }

    private fun writeConnectRequest(
        output: java.io.OutputStream,
        target: Socks5RelayTarget,
    ) {
        output.write(SOCKS_VERSION)
        output.write(SOCKS_CMD_CONNECT)
        output.write(0)
        when (val address = target.inetAddress) {
            is Inet4Address -> {
                output.write(SOCKS_ATYP_IPV4)
                output.write(address.address)
            }
            is Inet6Address -> {
                output.write(SOCKS_ATYP_IPV6)
                output.write(address.address)
            }
            else -> {
                val domain = IDN.toASCII(target.host).toByteArray(Charsets.US_ASCII)
                if (domain.isEmpty() || domain.size > BYTE_MASK) {
                    throw Socks5RelayException(RELAY_PROTOCOL_ERROR, "SOCKS5 target domain length=${domain.size}")
                }
                output.write(SOCKS_ATYP_DOMAIN)
                output.write(domain.size)
                output.write(domain)
            }
        }
        output.write((target.port ushr 8) and BYTE_MASK)
        output.write(target.port and BYTE_MASK)
        output.flush()
    }

    private fun readBoundAddress(input: InputStream, addressType: Int) {
        when (addressType) {
            SOCKS_ATYP_IPV4 -> input.readBytesExact(IPV4_BYTES)
            SOCKS_ATYP_DOMAIN -> input.readBytesExact(input.readByte())
            SOCKS_ATYP_IPV6 -> input.readBytesExact(IPV6_BYTES)
            else -> throw Socks5RelayException(RELAY_PROTOCOL_ERROR, "SOCKS5 relay reply address type $addressType")
        }
    }

    private fun InputStream.readByte(): Int {
        val value = read()
        if (value < 0) {
            throw EOFException()
        }
        return value
    }

    private fun InputStream.readBytesExact(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(result, offset, length - offset)
            if (read < 0) {
                throw EOFException()
            }
            offset += read
        }
        return result
    }

    private fun InputStream.readPort(): Int {
        return (readByte() shl 8) or readByte()
    }

    const val RELAY_AUTH_FAILED = "relay_auth_failed"
    const val RELAY_CONNECT_FAILED = "relay_connect_failed"
    const val RELAY_TARGET_FAILED = "relay_target_failed"
    const val RELAY_PROTOCOL_ERROR = "relay_protocol_error"

    private const val SOCKS_VERSION = 0x05
    private const val SOCKS_AUTH_NO_AUTH = 0x00
    private const val SOCKS_AUTH_USERNAME_PASSWORD = 0x02
    private const val SOCKS_AUTH_NO_ACCEPTABLE_METHODS = 0xff
    private const val SOCKS_USERNAME_PASSWORD_VERSION = 0x01
    private const val SOCKS_AUTH_STATUS_SUCCESS = 0x00
    private const val SOCKS_CMD_CONNECT = 0x01
    private const val SOCKS_ATYP_IPV4 = 0x01
    private const val SOCKS_ATYP_DOMAIN = 0x03
    private const val SOCKS_ATYP_IPV6 = 0x04
    private const val SOCKS_REPLY_SUCCEEDED = 0x00
    private const val BYTE_MASK = 0xff
    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
}
