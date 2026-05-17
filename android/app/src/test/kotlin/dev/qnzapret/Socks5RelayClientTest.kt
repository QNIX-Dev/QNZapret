package dev.qnzapret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class Socks5RelayClientTest {
    @Test
    fun noAuthConnectSupportsIpv4Target() {
        withSocksServer(handler = { server ->
            val input = server.getInputStream()
            val output = server.getOutputStream()
            assertEquals(5, input.readByte())
            assertEquals(1, input.readByte())
            assertEquals(0, input.readByte())
            output.write(byteArrayOf(5, 0))
            output.flush()

            assertEquals(5, input.readByte())
            assertEquals(1, input.readByte())
            assertEquals(0, input.readByte())
            assertEquals(1, input.readByte())
            assertEquals(listOf(149, 154, 167, 41), input.readBytesExact(4).map { it.toInt() and 0xff })
            assertEquals(443, input.readPort())
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
        }) { port ->
            Socket(InetAddress.getLoopbackAddress(), port).use { client ->
                val result = Socks5RelayClient.connect(
                    socket = client,
                    target = Socks5RelayTarget(
                        host = "149.154.167.41",
                        port = 443,
                        inetAddress = InetAddress.getByName("149.154.167.41"),
                    ),
                    auth = null,
                    timeoutMs = 1_000,
                )
                assertEquals(0, result.replyCode)
            }
        }
    }

    @Test
    fun usernamePasswordAuthConnectSupportsDomainTarget() {
        withSocksServer(handler = { server ->
            val input = server.getInputStream()
            val output = server.getOutputStream()
            assertEquals(5, input.readByte())
            assertEquals(2, input.readByte())
            assertEquals(0, input.readByte())
            assertEquals(2, input.readByte())
            output.write(byteArrayOf(5, 2))
            output.flush()

            assertEquals(1, input.readByte())
            val username = String(input.readBytesExact(input.readByte()), Charsets.UTF_8)
            val password = String(input.readBytesExact(input.readByte()), Charsets.UTF_8)
            assertEquals("user", username)
            assertEquals("pass", password)
            output.write(byteArrayOf(1, 0))
            output.flush()

            assertEquals(5, input.readByte())
            assertEquals(1, input.readByte())
            assertEquals(0, input.readByte())
            assertEquals(3, input.readByte())
            val domain = String(input.readBytesExact(input.readByte()), Charsets.US_ASCII)
            assertEquals("telegram.org", domain)
            assertEquals(443, input.readPort())
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
        }) { port ->
            Socket(InetAddress.getLoopbackAddress(), port).use { client ->
                val result = Socks5RelayClient.connect(
                    socket = client,
                    target = Socks5RelayTarget(
                        host = "telegram.org",
                        port = 443,
                        inetAddress = null,
                    ),
                    auth = Socks5RelayAuth(username = "user", password = "pass"),
                    timeoutMs = 1_000,
                )
                assertEquals(0, result.replyCode)
            }
        }
    }

    @Test
    fun relayReplyFailureMapsToTargetFailed() {
        withSocksServer(handler = { server ->
            val input = server.getInputStream()
            val output = server.getOutputStream()
            input.readBytesExact(3)
            output.write(byteArrayOf(5, 0))
            output.flush()
            input.readBytesExact(10)
            output.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
        }) { port ->
            Socket(InetAddress.getLoopbackAddress(), port).use { client ->
                val error = runCatching {
                    Socks5RelayClient.connect(
                        socket = client,
                        target = Socks5RelayTarget(
                            host = "149.154.167.41",
                            port = 443,
                            inetAddress = InetAddress.getByName("149.154.167.41"),
                        ),
                        auth = null,
                        timeoutMs = 1_000,
                    )
                }.exceptionOrNull()

                assertTrue(error is Socks5RelayException)
                assertEquals(Socks5RelayClient.RELAY_TARGET_FAILED, (error as Socks5RelayException).errorCode)
            }
        }
    }

    private fun withSocksServer(handler: (Socket) -> Unit, block: (Int) -> Unit) {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                serverSocket.use { server ->
                    server.accept().use(handler)
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        thread.start()

        try {
            block(serverSocket.localPort)
            thread.join(1_000)
            failure.get()?.let { throw AssertionError("SOCKS5 relay test server failed", it) }
        } finally {
            serverSocket.close()
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
}
