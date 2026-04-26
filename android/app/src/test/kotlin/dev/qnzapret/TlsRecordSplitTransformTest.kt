package dev.qnzapret

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TlsRecordSplitTransformTest {
    @Test
    fun splitsFirstTlsHandshakeRecord() {
        val payload = byteArrayOf(
            0x16,
            0x03,
            0x01,
            0x00,
            0x04,
            0x01,
            0x02,
            0x03,
            0x04,
        )

        val split = TlsRecordSplitTransform.splitFirstHandshakeRecord(payload, firstFragmentLength = 1)

        assertArrayEquals(
            byteArrayOf(
                0x16,
                0x03,
                0x01,
                0x00,
                0x01,
                0x01,
                0x16,
                0x03,
                0x01,
                0x00,
                0x03,
                0x02,
                0x03,
                0x04,
            ),
            split,
        )
    }

    @Test
    fun preservesTrailingRecords() {
        val payload = byteArrayOf(
            0x16,
            0x03,
            0x03,
            0x00,
            0x02,
            0x01,
            0x02,
            0x17,
            0x03,
            0x03,
            0x00,
            0x01,
            0x55,
        )

        val split = TlsRecordSplitTransform.splitFirstHandshakeRecord(payload, firstFragmentLength = 1)

        assertArrayEquals(
            byteArrayOf(
                0x16,
                0x03,
                0x03,
                0x00,
                0x01,
                0x01,
                0x16,
                0x03,
                0x03,
                0x00,
                0x01,
                0x02,
                0x17,
                0x03,
                0x03,
                0x00,
                0x01,
                0x55,
            ),
            split,
        )
    }

    @Test
    fun ignoresNonTlsPayload() {
        val payload = byteArrayOf(0x47, 0x45, 0x54, 0x20)

        assertNull(TlsRecordSplitTransform.splitFirstHandshakeRecord(payload, firstFragmentLength = 1))
    }
}
