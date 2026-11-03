package com.apkguard.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builders for synthetic APK contents used in tests. */
object TestApks {

    class El(val name: String, val attrs: List<Pair<String, Any>> = emptyList(), val children: List<El> = emptyList())

    private val RES_IDS = mapOf(
        "name" to 0x01010003, "permission" to 0x01010006, "exported" to 0x01010010,
        "debuggable" to 0x0101000f, "minSdkVersion" to 0x0101020c, "targetSdkVersion" to 0x01010270,
        "allowBackup" to 0x01010280,
    )

    /** Writes Android binary XML (UTF-8 string pool, resource map, elements). */
    fun axml(root: El): ByteArray {
        val strings = ArrayList<String>()
        fun idx(s: String): Int { val i = strings.indexOf(s); if (i >= 0) return i; strings.add(s); return strings.size - 1 }
        // Attribute names with resource ids must come first so the resource map lines up.
        val attrNames = LinkedHashSet<String>()
        fun collect(e: El) { e.attrs.forEach { attrNames.add(it.first) }; e.children.forEach(::collect) }
        collect(root)
        val mapped = attrNames.filter { it in RES_IDS }
        mapped.forEach { idx(it) }
        attrNames.forEach { idx(it) }
        val body = ByteArrayOutputStream()
        fun le(n: Int, v: Int) { val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); if (n == 2) b.putShort(v.toShort()) else b.putInt(v); body.write(b.array()) }
        fun emit(e: El) {
            val nameIdx = idx(e.name)
            val attrBytes = e.attrs.map { (k, v) ->
                val b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                b.putInt(if (k == "package") -1 else idx("http://schemas.android.com/apk/res/android")); b.putInt(idx(k))
                when (v) {
                    is String -> { val si = idx(v); b.putInt(si); b.putShort(8); b.put(0); b.put(0x03); b.putInt(si) }
                    is Boolean -> { b.putInt(-1); b.putShort(8); b.put(0); b.put(0x12); b.putInt(if (v) -1 else 0) }
                    is Int -> { b.putInt(-1); b.putShort(8); b.put(0); b.put(0x10); b.putInt(v) }
                    else -> error("bad attr")
                }
                b.array()
            }
            le(2, 0x0102); le(2, 16); le(4, 16 + 20 + 20 * attrBytes.size)
            le(4, 1); le(4, -1)
            le(4, -1); le(4, nameIdx); le(2, 20); le(2, 20); le(2, attrBytes.size); le(2, 0); le(2, 0); le(2, 0)
            attrBytes.forEach { body.write(it) }
            e.children.forEach(::emit)
            le(2, 0x0103); le(2, 16); le(4, 24); le(4, 1); le(4, -1); le(4, -1); le(4, nameIdx)
        }
        // First pass registers all strings.
        emit(root); body.reset(); emit(root)

        val pool = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (s in strings) {
            offsets.add(pool.size())
            val u = s.toByteArray(Charsets.UTF_8)
            require(s.length < 0x80 && u.size < 0x80)
            pool.write(s.length); pool.write(u.size); pool.write(u); pool.write(0)
        }
        while (pool.size() % 4 != 0) pool.write(0)
        val spHeader = 28
        val spSize = spHeader + offsets.size * 4 + pool.size()
        val sp = ByteBuffer.allocate(spSize).order(ByteOrder.LITTLE_ENDIAN)
        sp.putShort(1); sp.putShort(spHeader.toShort()); sp.putInt(spSize)
        sp.putInt(strings.size); sp.putInt(0); sp.putInt(0x100); sp.putInt(spHeader + offsets.size * 4); sp.putInt(0)
        offsets.forEach { sp.putInt(it) }; sp.put(pool.toByteArray())

        val rm = ByteBuffer.allocate(8 + mapped.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        rm.putShort(0x0180); rm.putShort(8); rm.putInt(8 + mapped.size * 4); mapped.forEach { rm.putInt(RES_IDS.getValue(it)) }

        val total = 8 + spSize + rm.capacity() + body.size()
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(3); out.putShort(8); out.putInt(total)
        out.put(sp.array()); out.put(rm.array()); out.put(body.toByteArray())
        return out.array()
    }

    /**
     * Minimal DEX: header, string_ids, type_ids, method_ids and string data. Enough for the scanner.
     * [types] are type descriptors the code references; [methods] are (class descriptor, method name).
     */
    fun dex(strings: List<String>, types: List<String> = emptyList(), methods: List<Pair<String, String>> = emptyList()): ByteArray {
        val all = (strings + types + methods.flatMap { listOf(it.first, it.second) }).distinct().sorted()
        val typeList = (types + methods.map { it.first }).distinct().sorted()
        val header = 0x70
        val idsOff = header
        val typeOff = idsOff + all.size * 4
        val methodOff = typeOff + typeList.size * 4
        val dataStart = methodOff + methods.size * 8
        val data = ByteArrayOutputStream()
        val offs = ArrayList<Int>()
        for (s in all) {
            offs.add(dataStart + data.size())
            require(s.length < 128)
            data.write(s.length); data.write(s.toByteArray(Charsets.UTF_8)); data.write(0)
        }
        val b = ByteBuffer.allocate(dataStart + data.size()).order(ByteOrder.LITTLE_ENDIAN)
        b.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        b.position(32); b.putInt(b.capacity()); b.putInt(header); b.putInt(0x12345678)
        b.position(56); b.putInt(all.size); b.putInt(idsOff)
        b.position(0x40); b.putInt(typeList.size); b.putInt(typeOff)
        b.position(0x58); b.putInt(methods.size); b.putInt(methodOff)
        b.position(idsOff); offs.forEach { b.putInt(it) }
        typeList.forEach { b.putInt(all.indexOf(it)) }
        methods.forEach { (c, n) -> b.putShort(typeList.indexOf(c).toShort()); b.putShort(0); b.putInt(all.indexOf(n)) }
        b.put(data.toByteArray())
        return b.array()
    }

    fun zip(file: File, entries: List<Pair<String, ByteArray>>, stored: Set<String> = emptySet()) {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, bytes) in entries) {
                val e = ZipEntry(name)
                if (name in stored) {
                    e.method = ZipEntry.STORED; e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
        }
    }
}
