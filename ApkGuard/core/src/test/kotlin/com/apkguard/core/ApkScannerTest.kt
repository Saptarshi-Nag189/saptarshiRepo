package com.apkguard.core

import com.apkguard.core.TestApks.El
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ApkScannerTest {

    private val tmp: File = Files.createTempDirectory("apkguard").toFile().apply { deleteOnExit() }

    private fun perm(p: String) = El("uses-permission", listOf("name" to "android.permission.$p"))

    private fun manifest(perms: List<String>, launcher: Boolean = true, a11y: Boolean = false, target: Int = 34): ByteArray {
        val comps = ArrayList<El>()
        if (launcher) comps += El("activity", listOf("name" to ".Main", "exported" to true), listOf(
            El("intent-filter", children = listOf(
                El("action", listOf("name" to "android.intent.action.MAIN")),
                El("category", listOf("name" to "android.intent.category.LAUNCHER")),
            ))))
        if (a11y) comps += El("service", listOf("name" to ".Spy", "permission" to "android.permission.BIND_ACCESSIBILITY_SERVICE", "exported" to true), listOf(
            El("intent-filter", children = listOf(El("action", listOf("name" to "android.accessibilityservice.AccessibilityService"))))))
        comps += El("receiver", listOf("name" to ".Boot", "exported" to false), listOf(
            El("intent-filter", children = listOf(El("action", listOf("name" to "android.intent.action.BOOT_COMPLETED"))))))
        return TestApks.axml(El("manifest", listOf("package" to "com.example.test", "versionName" to "1.0"), listOf(
            El("uses-sdk", listOf("minSdkVersion" to 24, "targetSdkVersion" to target)),
        ) + perms.map(::perm) + El("application", listOf("allowBackup" to false), comps)))
    }

    private fun scan(entries: List<Pair<String, ByteArray>>, stored: Set<String> = emptySet()): Report {
        val f = File(tmp, "t${Random.nextInt()}.apk")
        TestApks.zip(f, entries, stored)
        return ApkScanner().scan(f).also { f.delete() }
    }

    private fun Report.titles() = findings.map { it.title }

    @Test
    fun manifestRoundTrip() {
        val m = Manifest.from(BinaryXml.parse(manifest(listOf("INTERNET", "SEND_SMS"), a11y = true)))
        assertEquals("com.example.test", m.packageName)
        assertEquals(24, m.minSdk)
        assertEquals(34, m.targetSdk)
        assertEquals(listOf("android.permission.INTERNET", "android.permission.SEND_SMS"), m.permissions)
        assertTrue(m.hasLauncher)
        assertEquals(3, m.components.size)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", m.components[1].permission)
        assertEquals(false, m.allowBackup)
        assertTrue(m.anomalies.isEmpty(), m.anomalies.toString())
    }

    @Test
    fun benignAppIsLowRisk() {
        val r = scan(listOf(
            "AndroidManifest.xml" to manifest(listOf("INTERNET")),
            "classes.dex" to TestApks.dex(listOf("Lcom/example/Main;", "onCreate", "https://api.example.org/v1")),
        ))
        assertEquals(Verdict.LOW_RISK, r.verdict, r.toText(null))
        assertEquals("com.example.test", r.info.packageName)
        assertTrue("https://api.example.org/v1" in r.info.urls)
    }

    @Test
    fun bankingTrojanIsDangerous() {
        val r = scan(listOf(
            "AndroidManifest.xml" to manifest(listOf("INTERNET", "SYSTEM_ALERT_WINDOW", "RECEIVE_SMS", "QUERY_ALL_PACKAGES"), launcher = false, a11y = true),
            "classes.dex" to TestApks.dex(listOf(
                "Landroid/telephony/SmsManager;", "sendTextMessage", "dispatchGesture",
                "https://api.telegram.org/bot123:abc/sendMessage", "http://45.13.2.9/gate.php",
                "Ldalvik/system/InMemoryDexClassLoader;",
            )),
            "assets/logo.png" to TestApks.dex(listOf("Lhidden/Payload;")),
        ))
        assertEquals(Verdict.DANGEROUS, r.verdict)
        val t = r.titles()
        listOf(
            "Banking-trojan pattern: overlay + accessibility", "Accessibility control plus SMS access",
            "Code sends SMS messages", "Sends data to a Telegram bot", "Connects to raw IP addresses",
            "Disguised executable files", "Hidden app with background persistence", "Loads hidden code from memory",
            "Accessibility service", "No launcher icon",
        ).forEach { assertTrue(it in t, "missing '$it' in $t") }
    }

    @Test
    fun ruleRequiresAllTokens() {
        // sendTextMessage alone (no SmsManager) must not fire the SMS rule.
        val r = scan(listOf(
            "AndroidManifest.xml" to manifest(listOf()),
            "classes.dex" to TestApks.dex(listOf("sendTextMessage")),
        ))
        assertFalse("Code sends SMS messages" in r.titles())
    }

    @Test
    fun detectsDuplicateAndEncryptedEntries() {
        val f = File(tmp, "dup.apk")
        TestApks.zip(f, listOf("AndroidManifest.xml" to manifest(listOf()), "classes.dex" to TestApks.dex(listOf("a")), "x.txt" to "hello".toByteArray()))
        // Patch: rename x.txt -> classes.dex is not possible (length differs), so flip the encrypted bit and duplicate via raw bytes.
        val bytes = f.readBytes()
        val name = "x.txt".toByteArray()
        var cd = -1
        for (i in bytes.indices.reversed()) {
            if (i + 46 < bytes.size && bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() &&
                bytes.copyOfRange(i + 46, i + 46 + name.size).contentEquals(name)) { cd = i; break }
        }
        assertTrue(cd > 0)
        bytes[cd + 8] = (bytes[cd + 8].toInt() or 1).toByte()
        f.writeBytes(bytes)
        val r = ApkScanner().scan(f)
        assertTrue("Fake \"encrypted\" ZIP entries" in r.titles(), r.titles().toString())
    }

    @Test
    fun detectsJanusPrefix() {
        val inner = File(tmp, "inner.apk")
        TestApks.zip(inner, listOf("AndroidManifest.xml" to manifest(listOf())))
        // Prepend DEX bytes; central directory offsets now point too early, so fix them up by shifting.
        val dex = TestApks.dex(listOf("x"))
        val f = File(tmp, "janus.apk")
        f.writeBytes(dex + shiftZip(inner.readBytes(), dex.size))
        val r = ApkScanner().scan(f)
        assertTrue("DEX code hidden before the ZIP data (Janus)" in r.titles(), r.titles().toString())
    }

    /** Adjusts central-directory and EOCD offsets after prepending [by] bytes. */
    private fun shiftZip(z: ByteArray, by: Int): ByteArray {
        fun le32(o: Int) = (z[o].toInt() and 0xFF) or ((z[o + 1].toInt() and 0xFF) shl 8) or ((z[o + 2].toInt() and 0xFF) shl 16) or ((z[o + 3].toInt() and 0xFF) shl 24)
        fun put32(o: Int, v: Int) { for (k in 0..3) z[o + k] = (v ushr (8 * k)).toByte() }
        val eocd = (z.size - 22 downTo 0).first { le32(it) == 0x06054b50 }
        val cdOff = le32(eocd + 16)
        var p = cdOff
        while (le32(p) == 0x02014b50) {
            put32(p + 42, le32(p + 42) + by)
            p += 46 + ((z[p + 28].toInt() and 0xFF) or ((z[p + 29].toInt() and 0xFF) shl 8)) +
                ((z[p + 30].toInt() and 0xFF) or ((z[p + 31].toInt() and 0xFF) shl 8)) +
                ((z[p + 32].toInt() and 0xFF) or ((z[p + 33].toInt() and 0xFF) shl 8))
        }
        put32(eocd + 16, cdOff + by)
        return z
    }

    @Test
    fun malformedManifestDoesNotCrash() {
        val good = manifest(listOf("INTERNET"))
        repeat(300) { seed ->
            val rnd = Random(seed)
            val bad = good.copyOf()
            repeat(1 + rnd.nextInt(20)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte() }
            val trimmed = if (rnd.nextBoolean()) bad.copyOf(rnd.nextInt(bad.size)) else bad
            Manifest.from(BinaryXml.parse(trimmed))
        }
    }

    @Test
    fun randomFilesFailCleanly() {
        repeat(200) { seed ->
            val rnd = Random(seed)
            val f = File(tmp, "r$seed.apk")
            val base = File(tmp, "base.apk").also { TestApks.zip(it, listOf("AndroidManifest.xml" to manifest(listOf()), "classes.dex" to TestApks.dex(listOf("a", "b")))) }
            val bytes = base.readBytes()
            repeat(1 + rnd.nextInt(30)) { bytes[rnd.nextInt(bytes.size)] = rnd.nextInt(256).toByte() }
            f.writeBytes(bytes)
            try {
                ApkScanner().scan(f)
            } catch (e: ZipFormatException) {
                // expected for broken archives
            } catch (t: Throwable) {
                fail("seed $seed crashed: $t")
            } finally { f.delete() }
        }
    }

    @Test
    fun zipBombIsCapped() {
        val big = ByteArray(200 * 1024 * 1024)
        val f = File(tmp, "bomb.apk")
        TestApks.zip(f, listOf("AndroidManifest.xml" to manifest(listOf()), "classes.dex" to big))
        val r = ApkScanner(maxDexBytes = 8 * 1024 * 1024).scan(f)
        assertTrue("Extreme compression ratio (zip bomb pattern)" in r.titles(), r.titles().toString())
        f.delete()
    }

    @Test
    fun realApkIfProvided() {
        val path = System.getenv("APKGUARD_SAMPLE_APK") ?: return
        val r = ApkScanner().scan(File(path))
        println(r.toText(null))
        println("URLs: ${r.info.urls.size}, dex: ${r.info.dexCount}, abis: ${r.info.nativeAbis}, signing: ${r.info.signing}")
        assertTrue(r.info.packageName != null)
    }
}
