package com.apkguard.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Finds the APK inside whatever the user picked: a plain APK, or an APK wrapped in
 * ZIP / XAPK / APKS / APKM, RAR (v4), GZIP, TAR or TAR.GZ, nested up to [MAX_DEPTH] levels.
 * Extracted files get random names in [workDir]; entry names are never used as paths.
 */
object Containers {
    const val MAX_DEPTH = 4

    class Unwrapped(val apk: File, val chain: List<String>, val otherApks: List<String>)

    class UnsupportedArchiveException(val format: String) :
        Exception(if (format == "RAR5") "This RAR uses the newer RAR5 format, which APK Guard can't open yet. Extract the APK with a file manager, then scan it."
            else "$format archives are not supported yet. Extract the APK with a file manager, then scan it.")

    enum class Kind { ZIP, GZIP, TAR, RAR, SEVEN_Z, UNKNOWN }

    fun sniff(file: File): Kind {
        val head = ByteArray(264)
        val n = RandomAccessFile(file, "r").use { raf -> raf.read(head).coerceAtLeast(0) }
        fun at(o: Int, vararg b: Int) = n >= o + b.size && b.indices.all { head[o + it] == b[it].toByte() }
        return when {
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> Kind.ZIP
            at(0, 0x1F, 0x8B) -> Kind.GZIP
            at(257, 'u'.code, 's'.code, 't'.code, 'a'.code, 'r'.code) -> Kind.TAR
            at(0, 'R'.code, 'a'.code, 'r'.code, '!'.code, 0x1A, 0x07) -> Kind.RAR
            at(0, '7'.code, 'z'.code, 0xBC, 0xAF, 0x27, 0x1C) -> Kind.SEVEN_Z
            // Janus-style files start with DEX but still end in a ZIP directory; let the APK parser decide.
            else -> Kind.UNKNOWN
        }
    }

    /**
     * Returns the APK to scan. Every extracted temp file is added to [temps] so the caller can
     * delete them even if scanning fails.
     */
    fun unwrap(file: File, workDir: File, maxBytes: Long, temps: MutableList<File>, isCancelled: () -> Boolean): Unwrapped {
        var current = file
        val chain = ArrayList<String>()
        var others: List<String> = emptyList()
        repeat(MAX_DEPTH + 1) {
            if (isCancelled()) throw ApkScanner.CancelledException()
            when (sniff(current)) {
                Kind.RAR -> {
                    val (name, out, rest) = extractFromRar(current, workDir, maxBytes, temps)
                        ?: return Unwrapped(current, chain, others)
                    chain += name
                    others = rest
                    current = out
                }
                Kind.SEVEN_Z -> throw UnsupportedArchiveException("7z")
                Kind.GZIP -> {
                    val out = newTemp(workDir, temps)
                    current.inputStream().buffered().use { GZIPInputStream(it).use { gz -> out.outputStream().use { o -> copyCapped(gz, o, maxBytes) } } }
                    chain += "gzip"
                    current = out
                }
                Kind.TAR -> {
                    val (name, out, rest) = extractFromTar(current, workDir, maxBytes, temps)
                        ?: return Unwrapped(current, chain, others) // no APK inside; let the APK parser report it
                    chain += name
                    others = rest
                    current = out
                }
                Kind.ZIP, Kind.UNKNOWN -> {
                    val picked = runCatching { ZipArchive.open(current) }.getOrNull()?.use { zip ->
                        if (zip.entries.any { it.name == "AndroidManifest.xml" }) return Unwrapped(current, chain, others)
                        val candidates = zip.entries.filter { !it.isDirectory && it.uncompressedSize > 0 && isCandidate(it.name) }
                        val best = pick(candidates.map { it.name to it.uncompressedSize }) ?: return Unwrapped(current, chain, others)
                        val entry = candidates.first { it.name == best }
                        val out = newTemp(workDir, temps)
                        out.outputStream().buffered().use { zip.copyEntry(entry, it, maxBytes) }
                        others = candidates.map { it.name }.filter { it != best && it.endsWith(".apk", true) }
                        best to out
                    } ?: return Unwrapped(current, chain, others)
                    chain += picked.first
                    current = picked.second
                }
            }
        }
        return Unwrapped(current, chain, others)
    }

    private fun isCandidate(name: String): Boolean {
        val n = name.lowercase()
        return listOf(".apk", ".zip", ".xapk", ".apks", ".apkm", ".gz", ".tgz", ".tar", ".rar").any { n.endsWith(it) }
    }

    /** Prefer the base APK of a split bundle, then any APK, then nested archives; largest first. */
    private fun pick(items: List<Pair<String, Long>>): String? {
        val apks = items.filter { it.first.endsWith(".apk", true) }
        apks.firstOrNull { it.first.substringAfterLast('/').equals("base.apk", true) }?.let { return it.first }
        return (apks.ifEmpty { items }).maxByOrNull { it.second }?.first
    }

    private fun extractFromRar(file: File, workDir: File, maxBytes: Long, temps: MutableList<File>): Triple<String, File, List<String>>? {
        // RAR5 signature is "Rar!\u001A\u0007\u0001\u0000"; junrar only reads RAR 1.5 to 4.x.
        val sig = ByteArray(8)
        RandomAccessFile(file, "r").use { it.read(sig) }
        if (sig[6] == 1.toByte()) throw UnsupportedArchiveException("RAR5")
        val archive = try {
            com.github.junrar.Archive(file)
        } catch (e: com.github.junrar.exception.UnsupportedRarVersionException) {
            throw UnsupportedArchiveException("RAR5")
        } catch (e: com.github.junrar.exception.UnsupportedRarEncryptedException) {
            throw ZipFormatException("This RAR archive is password protected")
        } catch (e: Exception) {
            throw ZipFormatException("Damaged or encrypted RAR archive")
        }
        archive.use { rar ->
            if (rar.isEncrypted) throw ZipFormatException("This RAR archive is password protected")
            val headers = rar.fileHeaders.filter { !it.isDirectory && isCandidate(it.fileName.replace('\\', '/')) }
            val best = pick(headers.map { it.fileName.replace('\\', '/') to it.fullUnpackSize }) ?: return null
            val header = headers.first { it.fileName.replace('\\', '/') == best }
            val out = newTemp(workDir, temps)
            try {
                out.outputStream().buffered().use { rar.extractFile(header, CappedOutput(it, maxBytes)) }
            } catch (e: ZipFormatException) {
                throw e
            } catch (e: Exception) {
                throw ZipFormatException("Could not extract $best from the RAR archive")
            }
            return Triple(best, out, headers.map { it.fileName.replace('\\', '/') }.filter { it != best && it.endsWith(".apk", true) })
        }
    }

    /** Throws once more than [max] bytes are written, so a RAR bomb cannot fill the disk. */
    private class CappedOutput(private val out: OutputStream, private val max: Long) : OutputStream() {
        private var n = 0L
        override fun write(b: Int) { bump(1); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { bump(len.toLong()); out.write(b, off, len) }
        private fun bump(k: Long) {
            n += k
            if (n > max) throw ZipFormatException("Archive expands past ${max / 1048576} MiB; refusing (zip bomb protection)")
        }
        override fun flush() = out.flush()
    }

    private fun extractFromTar(file: File, workDir: File, maxBytes: Long, temps: MutableList<File>): Triple<String, File, List<String>>? {
        // First pass: list entries. Second pass: extract the chosen one.
        val list = ArrayList<Pair<String, Long>>()
        readTar(file) { name, size, _ -> if (isCandidate(name)) list += name to size; false }
        val best = pick(list) ?: return null
        val out = newTemp(workDir, temps)
        readTar(file) { name, _, input ->
            if (name == best) { out.outputStream().use { copyCapped(input, it, maxBytes) }; true } else false
        }
        return Triple(best, out, list.map { it.first }.filter { it != best && it.endsWith(".apk", true) })
    }

    /** Minimal ustar reader. [onEntry] returns true to stop. */
    private fun readTar(file: File, onEntry: (String, Long, InputStream) -> Boolean) {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(512)
            var entries = 0
            while (entries++ < 100_000) {
                if (!readFully(input, header)) return
                if (header.all { it == 0.toByte() }) return
                val name = cString(header, 0, 100)
                val prefix = cString(header, 345, 155)
                val size = cString(header, 124, 12).trim().toLongOrNull(8) ?: return
                if (size < 0) return
                val type = header[156].toInt().toChar()
                val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
                val padded = (size + 511) / 512 * 512
                val body = BoundedInput(input, size)
                if ((type == '0' || type == '\u0000') && onEntry(full, size, body)) return
                body.skipRest()
                skipFully(input, padded - size)
            }
        }
    }

    private class BoundedInput(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else src.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        fun skipRest() = skipFully(src, left).also { left = 0 }
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) return
            left -= r
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun copyCapped(input: InputStream, out: OutputStream, maxBytes: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            total += n
            if (total > maxBytes) throw ZipFormatException("Archive expands past ${maxBytes / 1048576} MiB; refusing (zip bomb protection)")
            out.write(buf, 0, n)
        }
    }

    private fun newTemp(dir: File, temps: MutableList<File>): File =
        File(dir, "inner-" + UUID.randomUUID() + ".bin").also { temps += it }
}
