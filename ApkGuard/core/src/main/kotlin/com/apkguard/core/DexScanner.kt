package com.apkguard.core

/**
 * Reads the string table of a DEX file without loading or executing any code.
 * Class names, method names and string literals all live in this table, so
 * it is enough to spot suspicious API usage and hard-coded endpoints.
 */
object DexScanner {
    private const val MAX_STRINGS = 3_000_000
    private const val MAX_STRING_BYTES = 64 * 1024

    class Result(val stringCount: Int, val malformed: String?)

    fun isDex(b: ByteArray): Boolean =
        b.size >= 8 && b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() &&
            b[2] == 'x'.code.toByte() && b[3] == '\n'.code.toByte()

    /** Calls [onString] for every string in the DEX string table. */
    fun scan(dex: ByteArray, onString: (String) -> Unit): Result {
        if (!isDex(dex) || dex.size < 0x70) return Result(0, "Not a valid DEX header")
        val count = s32(dex, 56)
        val idsOff = s32(dex, 60)
        if (count < 0 || count > MAX_STRINGS || idsOff < 0 || idsOff.toLong() + count.toLong() * 4 > dex.size) {
            return Result(0, "DEX string table out of bounds (possible anti-analysis tampering)")
        }
        var bad = 0
        for (i in 0 until count) {
            val off = s32(dex, idsOff + i * 4)
            if (off < 0 || off >= dex.size) { bad++; continue }
            var p = off
            // Skip uleb128 utf16 length.
            var guard = 0
            while (p < dex.size && (dex[p].toInt() and 0x80) != 0 && guard++ < 5) p++
            p++
            val start = p
            val limit = minOf(dex.size, start + MAX_STRING_BYTES)
            while (p < limit && dex[p] != 0.toByte()) p++
            if (p > start) onString(decodeMutf8(dex, start, p))
        }
        return Result(count, if (bad > 0) "$bad DEX string offsets are invalid" else null)
    }

    /**
     * Calls [onType] for every type descriptor in type_ids and [onMethodName] for every method name
     * in method_ids. These are what the code actually references, unlike plain string literals,
     * so an app that merely contains the text "sendTextMessage" (like this scanner) is not flagged.
     */
    fun scanRefs(dex: ByteArray, onType: (String) -> Unit, onMethodName: (String) -> Unit) {
        if (!isDex(dex) || dex.size < 0x70) return
        val strCount = s32(dex, 56)
        val strOff = s32(dex, 60)
        if (strCount < 0 || strCount > MAX_STRINGS || strOff < 0 || strOff.toLong() + strCount.toLong() * 4 > dex.size) return
        fun str(i: Int): String? {
            if (i < 0 || i >= strCount) return null
            val off = s32(dex, strOff + i * 4)
            if (off < 0 || off >= dex.size) return null
            var p = off
            var guard = 0
            while (p < dex.size && (dex[p].toInt() and 0x80) != 0 && guard++ < 5) p++
            p++
            val start = p
            val limit = minOf(dex.size, start + MAX_STRING_BYTES)
            while (p < limit && dex[p] != 0.toByte()) p++
            return decodeMutf8(dex, start, p)
        }
        val typeCount = s32(dex, 0x40)
        val typeOff = s32(dex, 0x44)
        if (typeCount in 0..MAX_STRINGS && typeOff >= 0 && typeOff.toLong() + typeCount.toLong() * 4 <= dex.size) {
            for (i in 0 until typeCount) str(s32(dex, typeOff + i * 4))?.let(onType)
        }
        val methodCount = s32(dex, 0x58)
        val methodOff = s32(dex, 0x5C)
        if (methodCount in 0..MAX_STRINGS && methodOff >= 0 && methodOff.toLong() + methodCount.toLong() * 8 <= dex.size) {
            val names = HashSet<Int>()
            for (i in 0 until methodCount) names.add(s32(dex, methodOff + i * 8 + 4))
            for (n in names) str(n)?.let(onMethodName)
        }
    }

    /** Lenient Modified-UTF-8 decode; invalid sequences become U+FFFD. */
    private fun decodeMutf8(b: ByteArray, start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        var i = start
        while (i < end) {
            val c = b[i].toInt() and 0xFF
            when {
                c < 0x80 -> { sb.append(c.toChar()); i++ }
                c and 0xE0 == 0xC0 && i + 1 < end -> {
                    sb.append((((c and 0x1F) shl 6) or (b[i + 1].toInt() and 0x3F)).toChar()); i += 2
                }
                c and 0xF0 == 0xE0 && i + 2 < end -> {
                    sb.append((((c and 0x0F) shl 12) or ((b[i + 1].toInt() and 0x3F) shl 6) or (b[i + 2].toInt() and 0x3F)).toChar()); i += 3
                }
                else -> { sb.append('�'); i++ }
            }
        }
        return sb.toString()
    }

    private fun s32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
