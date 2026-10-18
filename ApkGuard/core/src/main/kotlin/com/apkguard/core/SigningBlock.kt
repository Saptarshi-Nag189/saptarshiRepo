package com.apkguard.core

/**
 * Detects which APK signature schemes are present. This does not verify
 * signatures cryptographically; Android's PackageManager does that on-device.
 */
object SigningBlock {
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    const val ID_V2 = 0x7109871aL
    const val ID_V3 = 0xf05368c0L
    const val ID_V31 = 0x1b93ad61L

    data class Schemes(val v1: Boolean, val v2: Boolean, val v3: Boolean, val v31: Boolean) {
        val any: Boolean get() = v1 || v2 || v3 || v31
    }

    fun detect(zip: ZipArchive): Schemes {
        val v1 = zip.entries.any {
            val n = it.name.uppercase()
            n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
        }
        val ids = blockIds(zip)
        return Schemes(v1, ID_V2 in ids, ID_V3 in ids, ID_V31 in ids)
    }

    private fun blockIds(zip: ZipArchive): Set<Long> {
        val cd = zip.centralDirOffset
        if (cd < 32) return emptySet()
        val footer = zip.readBytesAt(cd - 24, 24)
        if (!footer.copyOfRange(8, 24).contentEquals(MAGIC)) return emptySet()
        val size = ZipArchive.le64(footer, 0)
        // Block = u64 size | pairs | u64 size | magic. Sanity-limit to 64 MiB.
        if (size < 24 || size > 64L * 1024 * 1024 || size + 8 > cd) return emptySet()
        val start = cd - size - 8
        val block = zip.readBytesAt(start, (size + 8).toInt())
        if (ZipArchive.le64(block, 0) != size) return emptySet()
        val ids = HashSet<Long>()
        var p = 8
        val end = block.size - 24
        while (p + 12 <= end) {
            val len = ZipArchive.le64(block, p)
            if (len < 4 || len > end - p - 8) break
            ids.add(ZipArchive.le32(block, p + 8))
            p += 8 + len.toInt()
        }
        return ids
    }
}
