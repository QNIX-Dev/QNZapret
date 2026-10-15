package dev.qnzapret

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CompactHostlistIndexTest {
    private fun index(vararg domains: String): CompactHostlistIndex {
        val values = domains.toSortedSet().map { it.toByteArray(Charsets.UTF_8) }
        val data = ByteBuffer.allocate(12 + (values.size + 1) * 4 + values.sumOf { it.size })
            .order(ByteOrder.LITTLE_ENDIAN)
        data.put(byteArrayOf(81, 78, 90, 72, 76, 49, 0, 0)).putInt(values.size)
        var offset = 0
        data.putInt(offset)
        values.forEach { offset += it.size; data.putInt(offset) }
        values.forEach { data.put(it) }
        return CompactHostlistIndex.fromBytes(data.array())
    }

    @Test fun exactAndParentMatchWithoutSubstringFalsePositives() {
        val index = index("example.com", "google.com", "z.example.net")
        assertEquals(3, index.size)
        assertTrue(index.matches("example.com"))
        assertTrue(index.matches("a.b.example.com"))
        assertTrue(index.matches("z.example.net"))
        assertFalse(index.matches("notexample.com"))
        assertFalse(index.matches("example.com.evil.net"))
        assertFalse(index.matches("missing.invalid"))
    }

    @Test fun emptyAndSingleEntryIndexes() {
        assertFalse(index().matches("example.com"))
        assertTrue(index("example.com").matches("sub.example.com"))
        assertFalse(index("example.com").matches("another.com"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedData() { CompactHostlistIndex.fromBytes(ByteArray(12)) }

    @Test fun generatedLargeCanonicalIndexLoadsWithExactCount() {
        val root = java.io.File("../../build/app/generated/compactHostlists/qnzapret/compact-hostlists")
            .takeIf { it.exists() } ?: java.io.File("../build/app/generated/compactHostlists/qnzapret/compact-hostlists")
        val file = root.listFiles()!!.first { it.name.startsWith("list-general.txt-") }
        val large = CompactHostlistIndex.open(file)
        assertTrue(large.size > 1_000_000)
        assertFalse(large.matches("definitely-not-a-listed-host.invalid"))
    }
}
