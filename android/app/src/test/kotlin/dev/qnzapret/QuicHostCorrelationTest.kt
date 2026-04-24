package dev.qnzapret

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuicHostCorrelationTest {
    @Test
    fun learnsHostFromDnsAResponse() {
        val address = InetAddress.getByName("142.250.74.196")
        val payload = dnsResponse(
            question = "www.google.com",
            answers = listOf(
                DnsAnswer.a("www.google.com", address),
            ),
        )

        val parsedAnswers = DnsMessageParser.parseAddressAnswers(payload)
        assertEquals(1, parsedAnswers.size)
        assertEquals("www.google.com", parsedAnswers.single().host)
        assertEquals(address, parsedAnswers.single().address)

        val correlation = QuicHostCorrelation()
        correlation.observeDnsResponse(payload, now = 1_000L)

        assertEquals("www.google.com", correlation.lookupHost(address, now = 2_000L))
    }

    @Test
    fun keepsQuestionHostAcrossCnameChain() {
        val address = InetAddress.getByName("142.250.74.206")
        val payload = dnsResponse(
            question = "www.youtube.com",
            answers = listOf(
                DnsAnswer.cname("www.youtube.com", "youtube-ui.l.google.com"),
                DnsAnswer.a("youtube-ui.l.google.com", address),
            ),
        )

        val parsedAnswers = DnsMessageParser.parseAddressAnswers(payload)
        assertEquals(1, parsedAnswers.size)
        assertEquals("www.youtube.com", parsedAnswers.single().host)

        val correlation = QuicHostCorrelation()
        correlation.observeDnsResponse(payload, now = 1_000L)

        assertEquals("www.youtube.com", correlation.lookupHost(address, now = 2_000L))
    }

    @Test
    fun learnsHostFromDnsAaaaResponse() {
        val address = InetAddress.getByName("2607:f8b0:400a:80b::200e")
        val payload = dnsResponse(
            question = "www.google.com",
            answers = listOf(
                DnsAnswer.aaaa("www.google.com", address),
            ),
        )

        val correlation = QuicHostCorrelation()
        correlation.observeDnsResponse(payload, now = 1_000L)

        assertEquals("www.google.com", correlation.lookupHost(address, now = 2_000L))
    }

    @Test
    fun expiresRememberedHost() {
        val address = InetAddress.getByName("203.0.113.10")
        val correlation = QuicHostCorrelation()

        correlation.rememberHost(
            address = address,
            host = "Example.COM.",
            now = 1_000L,
            ttlMs = 100L,
        )

        assertEquals("example.com", correlation.lookupHost(address, now = 1_050L))
        assertNull(correlation.lookupHost(address, now = 1_101L))
    }

    private data class DnsAnswer(
        val name: String,
        val type: Int,
        val ttlSeconds: Long,
        val data: ByteArray,
    ) {
        companion object {
            fun a(name: String, address: InetAddress): DnsAnswer {
                return DnsAnswer(
                    name = name,
                    type = DNS_TYPE_A,
                    ttlSeconds = 60L,
                    data = address.address,
                )
            }

            fun cname(name: String, canonicalName: String): DnsAnswer {
                return DnsAnswer(
                    name = name,
                    type = DNS_TYPE_CNAME,
                    ttlSeconds = 60L,
                    data = dnsNameBytes(canonicalName),
                )
            }

            fun aaaa(name: String, address: InetAddress): DnsAnswer {
                return DnsAnswer(
                    name = name,
                    type = DNS_TYPE_AAAA,
                    ttlSeconds = 60L,
                    data = address.address,
                )
            }
        }
    }

    private fun dnsResponse(question: String, answers: List<DnsAnswer>): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeShort(0x1234)
        output.writeShort(0x8180)
        output.writeShort(1)
        output.writeShort(answers.size)
        output.writeShort(0)
        output.writeShort(0)
        output.writeDnsName(question)
        output.writeShort(DNS_TYPE_A)
        output.writeShort(DNS_CLASS_IN)

        answers.forEach { answer ->
            output.writeDnsName(answer.name)
            output.writeShort(answer.type)
            output.writeShort(DNS_CLASS_IN)
            output.writeInt(answer.ttlSeconds)
            output.writeShort(answer.data.size)
            output.write(answer.data)
        }

        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeDnsName(host: String) {
        dnsNameBytes(host).forEach { value -> write(value.toInt() and 0xff) }
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeInt(value: Long) {
        write(((value ushr 24) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write((value and 0xff).toInt())
    }

    private companion object {
        private fun dnsNameBytes(host: String): ByteArray {
            val output = ByteArrayOutputStream()
            host.trimEnd('.').split('.').forEach { label ->
                output.write(label.length)
                label.encodeToByteArray().forEach { value -> output.write(value.toInt() and 0xff) }
            }
            output.write(0)
            return output.toByteArray()
        }

        private const val DNS_TYPE_A = 1
        private const val DNS_TYPE_CNAME = 5
        private const val DNS_TYPE_AAAA = 28
        private const val DNS_CLASS_IN = 1
    }
}
