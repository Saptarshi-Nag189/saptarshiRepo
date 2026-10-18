package com.apkguard.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Minimal, defensive ZIP reader. All input is treated as hostile:
 * every offset and length is bounds-checked and decompression is capped.
 * We read the central directory (what Android's installer trusts) and
 * cross-check it against local headers to catch evasion tricks.
 */
class ZipArchive private constructor(
    private val raf: RandomAccessFile,
    val fileLength: Long,
    val entries: List<ZipEntryInfo>,
    val centralDirOffset: Long,
    val commentLength: Int,
) : Closeable {

    class ZipEntryInfo(
        val name: String,
        val rawName: ByteArray,
        val flags: Int,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    ) {
        val isEncryptedFlag: Boolean get() = (flags and 0x1) != 0
        val isDirectory: Boolean get() = name.endsWith("/")
    }

    class LocalHeader(val name: String, val method: Int, val dataOffset: Long)

    fun readBytesAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > fileLength) {
            throw ZipFormatException("Read out of bounds at $offset+$length")
        }
        val buf = ByteArray(length)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    fun readLocalHeader(entry: ZipEntryInfo): LocalHeader {
        val fixed = readBytesAt(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (le32(fixed, 0) != LOCAL_SIG) throw ZipFormatException("Bad local header for ${entry.name}")
        val method = le16(fixed, 8)
        val nameLen = le16(fixed, 26)
        val extraLen = le16(fixed, 28)
        val nameBytes = readBytesAt(entry.localHeaderOffset + LOCAL_HEADER_SIZE, nameLen)
        val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_SIZE + nameLen + extraLen
        return LocalHeader(String(nameBytes, Charsets.UTF_8), method, dataOffset)
    }

    /**
     * Returns up to [maxBytes] of the entry's uncompressed data.
     * Never inflates past [maxBytes], so zip bombs cannot exhaust memory.
     */
    fun readEntry(entry: ZipEntryInfo, maxBytes: Int): ByteArray {
        val local = readLocalHeader(entry)
        val dataOffset = local.dataOffset
        if (dataOffset < 0 || dataOffset > fileLength) throw ZipFormatException("Bad data offset for ${entry.name}")
        // Android only honours STORED/DEFLATED from the central directory.
        return when (entry.method) {
            METHOD_STORED -> {
                val len = minOf(maxOf(entry.uncompressedSize, entry.compressedSize), maxBytes.toLong(), fileLength - dataOffset).toInt()
                readBytesAt(dataOffset, len)
            }
            METHOD_DEFLATED -> inflate(dataOffset, entry.compressedSize, maxBytes)
            else -> throw ZipFormatException("Unsupported compression method ${entry.method} for ${entry.name}")
        }
    }

    private fun inflate(offset: Long, compressedSize: Long, maxBytes: Int): ByteArray {
        val available = minOf(compressedSize, fileLength - offset)
        if (available < 0) throw ZipFormatException("Bad compressed size")
        val inflater = Inflater(true)
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val inBuf = ByteArray(64 * 1024)
        val outBuf = ByteArray(64 * 1024)
        try {
            var pos = offset
            val end = offset + available
            raf.seek(pos)
            while (out.size() < maxBytes && !inflater.finished()) {
                if (inflater.needsInput()) {
                    if (pos >= end) break
                    val n = minOf(inBuf.size.toLong(), end - pos).toInt()
                    raf.readFully(inBuf, 0, n)
                    pos += n
                    inflater.setInput(inBuf, 0, n)
                }
                val produced = try {
                    inflater.inflate(outBuf, 0, minOf(outBuf.size, maxBytes - out.size()))
                } catch (e: DataFormatException) {
                    throw ZipFormatException("Corrupt deflate stream")
                }
                if (produced == 0 && inflater.needsDictionary()) throw ZipFormatException("Deflate dictionary not supported")
                if (produced == 0 && inflater.needsInput() && pos >= end) break
                out.write(outBuf, 0, produced)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    override fun close() = raf.close()

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        private const val EOCD_SIG = 0x06054b50L
        private const val CD_SIG = 0x02014b50L
        private const val LOCAL_SIG = 0x04034b50L
        private const val EOCD_SIZE = 22
        private const val CD_HEADER_SIZE = 46
        private const val LOCAL_HEADER_SIZE = 30
        private const val MAX_COMMENT = 0xFFFF
        const val MAX_ENTRIES = 200_000

        fun open(file: File): ZipArchive {
            val raf = RandomAccessFile(file, "r")
            try {
                return parse(raf)
            } catch (t: Throwable) {
                raf.close()
                throw t
            }
        }

        private fun parse(raf: RandomAccessFile): ZipArchive {
            val len = raf.length()
            if (len < EOCD_SIZE) throw ZipFormatException("File too small to be an APK")
            val tailLen = minOf(len, (EOCD_SIZE + MAX_COMMENT).toLong()).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen)
            raf.readFully(tail)
            var eocd = -1
            for (i in tailLen - EOCD_SIZE downTo 0) {
                if (le32(tail, i) == EOCD_SIG) {
                    val commentLen = le16(tail, i + 20)
                    if (i + EOCD_SIZE + commentLen == tailLen) { eocd = i; break }
                }
            }
            if (eocd < 0) throw ZipFormatException("No ZIP end-of-central-directory record; not an APK")
            val totalEntries = le16(tail, eocd + 10)
            val cdSize = le32(tail, eocd + 12)
            val cdOffset = le32(tail, eocd + 16)
            val commentLen = le16(tail, eocd + 20)
            if (totalEntries == 0xFFFF || cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
                throw ZipFormatException("ZIP64 archives are not valid APKs")
            }
            val eocdAbs = len - tailLen + eocd
            if (cdOffset + cdSize > eocdAbs) throw ZipFormatException("Central directory out of bounds")
            if (cdSize > 64L * 1024 * 1024) throw ZipFormatException("Central directory too large")

            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cd)
            val entries = ArrayList<ZipEntryInfo>(minOf(totalEntries, 4096))
            var p = 0
            while (p + CD_HEADER_SIZE <= cd.size) {
                if (le32(cd, p) != CD_SIG) break
                if (entries.size >= MAX_ENTRIES) throw ZipFormatException("Too many ZIP entries")
                val flags = le16(cd, p + 8)
                val method = le16(cd, p + 10)
                val cSize = le32(cd, p + 20)
                val uSize = le32(cd, p + 24)
                val nameLen = le16(cd, p + 28)
                val extraLen = le16(cd, p + 30)
                val commentL = le16(cd, p + 32)
                val localOff = le32(cd, p + 42)
                val nameStart = p + CD_HEADER_SIZE
                if (nameStart + nameLen > cd.size) throw ZipFormatException("Truncated central directory")
                val raw = cd.copyOfRange(nameStart, nameStart + nameLen)
                entries.add(ZipEntryInfo(String(raw, Charsets.UTF_8), raw, flags, method, cSize, uSize, localOff))
                p = nameStart + nameLen + extraLen + commentL
            }
            return ZipArchive(raf, len, entries, cdOffset, commentLen)
        }

        internal fun le16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        internal fun le32(b: ByteArray, o: Int): Long =
            (le16(b, o).toLong()) or (le16(b, o + 2).toLong() shl 16)

        internal fun le64(b: ByteArray, o: Int): Long =
            le32(b, o) or (le32(b, o + 4) shl 32)
    }
}

class ZipFormatException(message: String) : Exception(message)
