package com.apkguard.core

/**
 * Parser for Android's compiled binary XML (AndroidManifest.xml inside an APK).
 * Malware often ships deliberately malformed manifests that crash analysis
 * tools but still install. This parser never throws on bad chunks; it skips
 * them and records an anomaly instead.
 */
class BinaryXml private constructor(private val data: ByteArray) {

    class Attr(val namespace: String?, val name: String, val resId: Int, val type: Int, val data: Int, val stringValue: String?) {
        fun asString(): String? = stringValue ?: when (type) {
            TYPE_INT_DEC -> data.toString()
            TYPE_INT_HEX -> "0x" + Integer.toHexString(data)
            TYPE_BOOLEAN -> (data != 0).toString()
            TYPE_REFERENCE -> "@0x" + Integer.toHexString(data)
            else -> null
        }
        fun asBoolean(): Boolean? = when (type) {
            TYPE_BOOLEAN, TYPE_INT_DEC, TYPE_INT_HEX -> data != 0
            TYPE_STRING -> stringValue?.equals("true", ignoreCase = true)
            else -> null
        }
        fun asInt(): Int? = when (type) {
            TYPE_INT_DEC, TYPE_INT_HEX -> data
            TYPE_STRING -> stringValue?.trim()?.toIntOrNull()
            else -> null
        }
    }

    class Element(val name: String, val attrs: List<Attr>, val depth: Int) {
        /** Look up by Android resource id first (survives name obfuscation), then by name. */
        fun attr(name: String, resId: Int = 0): Attr? =
            (if (resId != 0) attrs.firstOrNull { it.resId == resId } else null)
                ?: attrs.firstOrNull { it.name == name }
    }

    sealed class Event {
        class Start(val element: Element) : Event()
        class End(val name: String, val depth: Int) : Event()
    }

    val events = ArrayList<Event>()
    val anomalies = ArrayList<String>()
    private var strings: Array<String?> = emptyArray()
    private var resIds: IntArray = IntArray(0)

    private fun u16(o: Int): Int = (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8)
    private fun s32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)
    private fun inBounds(o: Int, len: Int) = o >= 0 && len >= 0 && o.toLong() + len <= data.size

    private fun str(i: Int): String? = if (i >= 0 && i < strings.size) strings[i] else null

    private fun parse() {
        if (data.size < 8) { anomalies.add("Manifest too small"); return }
        val type = u16(0)
        if (type != RES_XML_TYPE) anomalies.add("Manifest has unexpected root chunk type 0x${Integer.toHexString(type)}")
        val headerSize = u16(2).let { if (it < 8 || it > data.size) 8 else it }
        var p = headerSize
        var depth = 0
        var chunks = 0
        while (p + 8 <= data.size) {
            if (++chunks > MAX_CHUNKS) { anomalies.add("Manifest has an excessive number of chunks"); break }
            val cType = u16(p)
            val cHeader = u16(p + 2)
            val cSize = s32(p + 4)
            if (cSize < 8 || !inBounds(p, cSize)) {
                anomalies.add("Manifest chunk at offset $p has invalid size $cSize")
                break
            }
            try {
                when (cType) {
                    RES_STRING_POOL_TYPE -> parseStringPool(p, cHeader, cSize)
                    RES_XML_RESOURCE_MAP_TYPE -> {
                        val n = (cSize - cHeader) / 4
                        resIds = IntArray(maxOf(n, 0)) { s32(p + cHeader + it * 4) }
                    }
                    RES_XML_START_ELEMENT_TYPE -> {
                        depth++
                        parseStart(p, cHeader, depth)?.let { events.add(Event.Start(it)) }
                    }
                    RES_XML_END_ELEMENT_TYPE -> {
                        val name = if (inBounds(p + 16, 8)) str(s32(p + 20)) ?: "" else ""
                        events.add(Event.End(name, depth))
                        depth--
                    }
                    RES_XML_START_NAMESPACE_TYPE, RES_XML_END_NAMESPACE_TYPE, RES_XML_CDATA_TYPE -> Unit
                    else -> anomalies.add("Manifest contains unknown chunk type 0x${Integer.toHexString(cType)}")
                }
            } catch (e: IndexOutOfBoundsException) {
                anomalies.add("Manifest chunk at offset $p is truncated")
            }
            p += cSize
        }
    }

    private fun parseStringPool(p: Int, header: Int, size: Int) {
        val count = s32(p + 8)
        val flags = s32(p + 16)
        val stringsStart = s32(p + 20)
        if (count < 0 || count > MAX_STRINGS || !inBounds(p + header, count * 4)) {
            anomalies.add("Manifest string pool has invalid count $count")
            return
        }
        val utf8 = (flags and 0x100) != 0
        val base = p + stringsStart
        val end = p + size
        strings = Array(count) { i ->
            val off = base + s32(p + header + i * 4)
            if (off < base || off >= end) null else try {
                if (utf8) readUtf8(off, end) else readUtf16(off, end)
            } catch (e: IndexOutOfBoundsException) { null }
        }
    }

    private fun readUtf8(o: Int, end: Int): String? {
        var p = o
        // UTF-16 length (skip), then UTF-8 byte length; each 1 or 2 bytes.
        p += if ((data[p].toInt() and 0x80) != 0) 2 else 1
        var len = data[p].toInt() and 0xFF
        if ((len and 0x80) != 0) { len = ((len and 0x7F) shl 8) or (data[p + 1].toInt() and 0xFF); p += 2 } else p += 1
        if (p + len > end) return null
        return String(data, p, len, Charsets.UTF_8)
    }

    private fun readUtf16(o: Int, end: Int): String? {
        var p = o
        var len = u16(p)
        if ((len and 0x8000) != 0) { len = ((len and 0x7FFF) shl 16) or u16(p + 2); p += 4 } else p += 2
        if (p + len * 2 > end) return null
        val chars = CharArray(len) { (u16(p + it * 2)).toChar() }
        return String(chars)
    }

    private fun parseStart(p: Int, header: Int, depth: Int): Element? {
        val ext = p + header
        if (!inBounds(ext, 20)) return null
        val name = str(s32(ext + 4)) ?: run { anomalies.add("Manifest element with unresolvable name"); "" }
        val attrStart = u16(ext + 8)
        val attrSize = u16(ext + 10).let { if (it < 20) 20 else it }
        val attrCount = u16(ext + 12)
        val attrs = ArrayList<Attr>(attrCount)
        for (i in 0 until attrCount) {
            val a = ext + attrStart + i * attrSize
            if (!inBounds(a, 20)) { anomalies.add("Manifest attribute list is truncated"); break }
            val nsIdx = s32(a)
            val nameIdx = s32(a + 4)
            val rawIdx = s32(a + 8)
            val dType = data[a + 15].toInt() and 0xFF
            val dVal = s32(a + 16)
            val resId = if (nameIdx >= 0 && nameIdx < resIds.size) resIds[nameIdx] else 0
            val sv = if (dType == TYPE_STRING) str(dVal) else str(rawIdx)
            attrs.add(Attr(str(nsIdx), str(nameIdx) ?: "", resId, dType, dVal, sv))
        }
        return Element(name, attrs, depth)
    }

    companion object {
        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val TYPE_BOOLEAN = 0x12

        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_TYPE = 0x0003
        private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
        private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val RES_XML_END_ELEMENT_TYPE = 0x0103
        private const val RES_XML_CDATA_TYPE = 0x0104
        private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        private const val MAX_CHUNKS = 500_000
        private const val MAX_STRINGS = 1_000_000

        fun parse(bytes: ByteArray): BinaryXml = BinaryXml(bytes).also { it.parse() }
    }
}
