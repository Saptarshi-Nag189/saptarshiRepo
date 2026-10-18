package com.apkguard.core

import com.apkguard.core.Severity.CRITICAL
import com.apkguard.core.Severity.HIGH
import com.apkguard.core.Severity.INFO
import com.apkguard.core.Severity.LOW
import com.apkguard.core.Severity.MEDIUM
import java.io.File

/**
 * Static APK analyser. It only reads bytes: no code from the APK is ever
 * loaded, executed or installed, and no network access is used.
 */
class ApkScanner(
    private val maxManifestBytes: Int = 16 * 1024 * 1024,
    private val maxDexBytes: Int = 128 * 1024 * 1024,
    private val maxMagicProbes: Int = 20_000,
) {
    /** Thrown when the user cancels; checked between expensive steps. */
    class CancelledException : Exception("Scan cancelled")

    fun scan(file: File, progress: (String) -> Unit = {}, isCancelled: () -> Boolean = { false }): Report {
        val findings = ArrayList<Finding>()
        fun check() { if (isCancelled()) throw CancelledException() }

        progress("Reading archive structure")
        ZipArchive.open(file).use { zip ->
            check()
            checkStructure(zip, findings)
            val signing = SigningBlock.detect(zip)
            checkSigning(signing, findings)

            progress("Parsing AndroidManifest.xml")
            val manifestEntry = zip.entries.lastOrNull { it.name == "AndroidManifest.xml" }
            val manifest = manifestEntry?.let {
                runCatching { Manifest.from(BinaryXml.parse(zip.readEntry(it, maxManifestBytes))) }.getOrNull()
            }
            if (manifest == null) {
                findings += Finding(HIGH, "Structure", "Manifest missing or unreadable",
                    "Android needs AndroidManifest.xml to install. A missing or broken one means this is not a normal APK or is built to defeat scanners.")
            } else {
                checkManifest(manifest, signing, findings)
            }
            check()

            val dexEntries = zip.entries.filter { DEX_NAME.matches(it.name) }
            val seen = HashSet<String>()
            val urls = LinkedHashSet<String>()
            val interest = Rules.code.filter { !it.substring }.flatMap { it.anyOf + it.allOf }.toHashSet()
            val subTokens = Rules.code.filter { it.substring }.flatMap { it.anyOf + it.allOf }.distinct()
            dexEntries.forEachIndexed { i, e ->
                check()
                progress("Scanning code ${i + 1}/${dexEntries.size} (${e.name})")
                if (e.uncompressedSize > maxDexBytes) {
                    findings += Finding(MEDIUM, "Code", "Oversized DEX skipped", "${e.name} is ${e.uncompressedSize / 1048576} MiB, too large to scan safely.")
                    return@forEachIndexed
                }
                val bytes = runCatching { zip.readEntry(e, maxDexBytes) }.getOrElse {
                    findings += Finding(MEDIUM, "Code", "Unreadable DEX file", "${e.name}: ${it.message}")
                    return@forEachIndexed
                }
                var n = 0
                val res = DexScanner.scan(bytes) { s ->
                    if ((++n and 0x3FFF) == 0) check()
                    if (s in interest) seen.add(s)
                    if (s.length >= 5) {
                        for (t in subTokens) if (s.contains(t)) seen.add(t)
                        if (urls.size < MAX_URLS && (s.contains("http://") || s.contains("https://"))) {
                            URL_RE.findAll(s).forEach { m -> if (urls.size < MAX_URLS) urls.add(m.value) }
                        }
                    }
                }
                res.malformed?.let {
                    findings += Finding(MEDIUM, "Code", "Malformed DEX file", "${e.name}: $it")
                }
            }
            if (dexEntries.isEmpty()) {
                findings += Finding(INFO, "Code", "No DEX code found", "The app has no Java/Kotlin bytecode; logic may be native or in another runtime.")
            }
            for (r in Rules.code) {
                val hit = r.anyOf.filter { it in seen }
                if (hit.isNotEmpty() && r.allOf.all { it in seen }) {
                    findings += Finding(r.severity, "Code", r.title, r.detail, hit)
                }
            }
            checkUrls(urls, findings)

            progress("Inspecting native libraries and assets")
            val abis = checkNative(zip, findings)
            check()
            val embedded = checkEmbeddedPayloads(zip, findings, isCancelled)
            if (manifest != null) checkCombos(manifest, embedded, seen, findings)

            val info = ApkInfo(
                packageName = manifest?.packageName,
                versionName = manifest?.versionName,
                versionCode = manifest?.versionCode,
                minSdk = manifest?.minSdk,
                targetSdk = manifest?.targetSdk,
                fileSize = zip.fileLength,
                entryCount = zip.entries.size,
                dexCount = dexEntries.size,
                nativeAbis = abis,
                permissions = manifest?.permissions ?: emptyList(),
                signing = signing,
                urls = urls.toList(),
            )
            return Report(info, findings)
        }
    }

    private fun checkStructure(zip: ZipArchive, out: MutableList<Finding>) {
        val dups = zip.entries.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (dups.isNotEmpty()) {
            val critical = dups.any { it == "AndroidManifest.xml" || DEX_NAME.matches(it) }
            out += Finding(if (critical) CRITICAL else HIGH, "Structure", "Duplicate files inside the APK",
                "The same path appears more than once. Different tools then see different content, a known way to hide malware (\"Master Key\"-style).",
                dups.take(20).toList())
        }
        val traversal = zip.entries.filter { e ->
            val n = e.name
            n.startsWith("/") || n.contains("\\") || n.contains('\u0000') || n.split('/').any { it == ".." }
        }
        if (traversal.isNotEmpty()) {
            out += Finding(HIGH, "Structure", "Path traversal file names",
                "Entry names try to escape their folder (../ or absolute paths). Used to attack unzip tools.",
                traversal.take(20).map { it.name })
        }
        val enc = zip.entries.filter { it.isEncryptedFlag }
        if (enc.isNotEmpty()) {
            out += Finding(MEDIUM, "Anti-analysis", "Fake \"encrypted\" ZIP entries",
                "Entries are flagged as encrypted. Android ignores this flag but many scanners give up, so it is a classic evasion trick.",
                enc.take(20).map { it.name })
        }
        val odd = zip.entries.filter { it.method != ZipArchive.METHOD_STORED && it.method != ZipArchive.METHOD_DEFLATED }
        if (odd.isNotEmpty()) {
            out += Finding(MEDIUM, "Anti-analysis", "Unusual compression methods",
                "Entries use compression methods Android does not support, often to break analysis tools.",
                odd.take(20).map { "${it.name} (method ${it.method})" })
        }
        val bombs = zip.entries.filter { it.compressedSize > 0 && it.uncompressedSize > 50L * 1048576 && it.uncompressedSize / it.compressedSize > 100 }
        if (bombs.isNotEmpty()) {
            out += Finding(MEDIUM, "Structure", "Extreme compression ratio (zip bomb pattern)",
                "Some files expand more than 100x to over 50 MiB.", bombs.take(10).map { it.name })
        }
        val mismatched = ArrayList<String>()
        var minLocal = Long.MAX_VALUE
        for (e in zip.entries.take(maxMagicProbes)) {
            minLocal = minOf(minLocal, e.localHeaderOffset)
            val local = runCatching { zip.readLocalHeader(e) }.getOrNull()
            if (local == null) mismatched += "${e.name} (bad local header)"
            else if (local.name != e.name) mismatched += "${e.name} vs ${local.name}"
        }
        if (mismatched.isNotEmpty()) {
            out += Finding(HIGH, "Anti-analysis", "Inconsistent ZIP headers",
                "File names or headers in the ZIP index do not match the file data. Tools that read the archive differently will see different files.",
                mismatched.take(20))
        }
        if (minLocal != Long.MAX_VALUE && minLocal > 0) {
            val head = zip.readBytesAt(0, minOf(8L, minLocal).toInt())
            if (DexScanner.isDex(head + ByteArray(8))) {
                out += Finding(CRITICAL, "Anti-analysis", "DEX code hidden before the ZIP data (Janus)",
                    "The file starts with DEX bytecode glued in front of the APK. This exploits CVE-2017-13156 to run hidden code on old Android versions.")
            } else {
                out += Finding(MEDIUM, "Structure", "Extra data before the ZIP contents",
                    "$minLocal unexplained bytes precede the first file. Normal APKs start directly with a ZIP entry.")
            }
        }
    }

    private fun checkSigning(s: SigningBlock.Schemes, out: MutableList<Finding>) {
        if (!s.any) {
            out += Finding(HIGH, "Signature", "APK is not signed",
                "No signature found. Android will refuse to install it, and its origin cannot be verified.")
        } else if (s.v1 && !s.v2 && !s.v3 && !s.v31) {
            out += Finding(MEDIUM, "Signature", "Only legacy v1 (JAR) signature",
                "Old signature scheme vulnerable to tampering on Android 5-8 (Janus). Modern apps use v2/v3.")
        }
    }

    private fun checkManifest(m: Manifest, signing: SigningBlock.Schemes, out: MutableList<Finding>) {
        if (m.anomalies.isNotEmpty()) {
            out += Finding(MEDIUM, "Anti-analysis", "Malformed AndroidManifest.xml",
                "The manifest contains corrupt or unusual binary structures, often crafted to crash analysis tools.",
                m.anomalies.distinct().take(10))
        }
        if (m.packageName.isNullOrBlank()) {
            out += Finding(MEDIUM, "Manifest", "No package name", "The manifest does not declare a package name.")
        }
        for (p in m.permissions) {
            val rule = Rules.permissions[p] ?: continue
            out += Finding(rule.severity, "Permission", shortPerm(p), rule.explain, listOf(p))
        }
        for (c in m.components) {
            c.permission?.let { perm ->
                Rules.boundServices[perm]?.let { rule ->
                    out += Finding(rule.severity, "Component", rule.title ?: perm, rule.explain, listOf("${c.kind}: ${c.name}"))
                }
            }
            if (c.kind == "receiver") {
                for (a in c.actions) Rules.receiverActions[a]?.let { rule ->
                    out += Finding(rule.severity, "Component", rule.explain, "Receiver ${c.name} listens for $a.", listOf(c.name))
                }
            }
        }
        val dangerousCount = m.permissions.count { Rules.permissions[it]?.severity?.let { s -> s <= MEDIUM } == true }
        val target = m.targetSdk
        if (target != null && target < 23 && dangerousCount > 0) {
            out += Finding(MEDIUM, "Manifest", "Targets an outdated Android version (API $target)",
                "Apps targeting API < 23 get all dangerous permissions at install time without asking you. Malware does this on purpose.")
        }
        if (m.debuggable) out += Finding(MEDIUM, "Manifest", "Debuggable build",
            "This is a developer build. Store apps are never debuggable; it may be repackaged or tampered.")
        if (m.testOnly) out += Finding(MEDIUM, "Manifest", "Test-only build", "Marked testOnly; not a released app.")
        if (m.sharedUserId?.startsWith("android.uid.") == true) {
            out += Finding(CRITICAL, "Manifest", "Claims a system user ID",
                "sharedUserId=${m.sharedUserId} asks to run as a system component. A downloaded app should never do this.")
        }
        if (m.usesCleartextTraffic == true) out += Finding(LOW, "Network", "Allows unencrypted HTTP traffic",
            "usesCleartextTraffic=true lets data travel without encryption.")
        if (m.allowBackup != false) out += Finding(LOW, "Manifest", "App data can be backed up",
            "allowBackup is enabled, so its data can be extracted via ADB backup.")
        if (!m.hasLauncher) out += Finding(MEDIUM, "Manifest", "No launcher icon",
            "The app does not appear in your app drawer. Could be a plugin, or a way to hide from the user.")
        val exported = m.components.filter {
            it.isExported(m.targetSdk) && it.permission == null &&
                !(Manifest.ACTION_MAIN in it.actions && Manifest.CATEGORY_LAUNCHER in it.categories)
        }
        if (exported.isNotEmpty()) {
            val providers = exported.filter { it.kind == "provider" }
            out += Finding(if (providers.isNotEmpty()) MEDIUM else LOW, "Component", "Unprotected exported components",
                "${exported.size} component(s) can be started by any other app without a permission.",
                exported.take(20).map { "${it.kind}: ${it.name}" })
        }
        if (target != null && target >= 30 && signing.any && !signing.v2 && !signing.v3 && !signing.v31) {
            out += Finding(LOW, "Signature", "Will not install on modern Android",
                "Targets API $target but has no v2+ signature, which Android 11+ requires.")
        }
    }

    private fun checkUrls(urls: Collection<String>, out: MutableList<Finding>) {
        val rawIp = urls.filter { IP_URL_RE.containsMatchIn(it) && !it.contains("://127.") && !it.contains("://10.") && !it.contains("://192.168.") }
        if (rawIp.isNotEmpty()) {
            out += Finding(MEDIUM, "Network", "Connects to raw IP addresses",
                "Hard-coded IP addresses instead of domain names are typical for command-and-control servers.", rawIp.take(20))
        }
        val interesting = urls.filterNot { u -> BENIGN_URL_HOSTS.any { u.contains(it) } }
        if (interesting.isNotEmpty()) {
            out += Finding(INFO, "Network", "Hard-coded web addresses (${interesting.size})",
                "Endpoints found in the code. Check that they belong to the app's real publisher.", interesting.take(50))
        }
    }

    private fun checkNative(zip: ZipArchive, out: MutableList<Finding>): List<String> {
        val libs = zip.entries.filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
        val abis = libs.mapNotNull { it.name.split('/').getOrNull(1) }.distinct().sorted()
        val names = libs.map { it.name.substringAfterLast('/') }.toSet()
        val packers = names.mapNotNull { n -> Rules.packerLibs[n]?.let { "$n ($it)" } }
        if (packers.isNotEmpty()) {
            out += Finding(HIGH, "Anti-analysis", "Packed / encrypted code",
                "A commercial packer hides the real code, so this scan (and most others) cannot see what the app does. Common in malware, also used by some legit apps in China.",
                packers)
        }
        val fw = names.mapNotNull { Rules.frameworks[it] }.distinct()
        if (fw.isNotEmpty()) {
            out += Finding(INFO, "Code", "Built with ${fw.joinToString()}",
                "Most logic lives outside DEX bytecode, so code checks here have limited visibility.")
        }
        return abis
    }

    /** Returns true if an embedded executable payload (DEX/APK/ELF) was found. */
    private fun checkEmbeddedPayloads(zip: ZipArchive, out: MutableList<Finding>, isCancelled: () -> Boolean): Boolean {
        val disguised = ArrayList<String>()
        val hiddenDex = ArrayList<String>()
        val archives = ArrayList<String>()
        val strayElf = ArrayList<String>()
        var probes = 0
        for (e in zip.entries) {
            if (e.isDirectory || e.uncompressedSize < 8) continue
            val n = e.name
            if (DEX_NAME.matches(n) || (n.startsWith("lib/") && n.endsWith(".so"))) continue
            if (n.startsWith("META-INF/") || n == "AndroidManifest.xml" || n == "resources.arsc") continue
            if (++probes > maxMagicProbes) break
            if ((probes and 0xFF) == 0 && isCancelled()) throw CancelledException()
            val head = runCatching { zip.readEntry(e, 8) }.getOrNull() ?: continue
            if (head.size < 4) continue
            val ext = n.substringAfterLast('.', "").lowercase()
            val kind = when {
                DexScanner.isDex(head) -> "DEX"
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte() -> "ZIP"
                head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() -> "ELF"
                else -> null
            } ?: continue
            val expected = when (kind) {
                "DEX" -> ext in setOf("dex", "odex", "vdex")
                "ZIP" -> ext in setOf("zip", "jar", "apk", "aar", "apks", "xapk", "obb", "ttf", "otf", "kotlin_builtins")
                else -> ext == "so"
            }
            when {
                !expected -> disguised += "$n looks like $kind"
                kind == "DEX" -> hiddenDex += n
                kind == "ZIP" -> archives += n
                else -> strayElf += n
            }
        }
        if (disguised.isNotEmpty()) out += Finding(HIGH, "Payload", "Disguised executable files",
            "Files pretend to be something else (e.g. images) but contain code or archives. A strong sign of a hidden second stage.", disguised.take(20))
        if (hiddenDex.isNotEmpty()) out += Finding(HIGH, "Payload", "Extra DEX code outside the normal location",
            "Bytecode stored in assets is usually loaded secretly at runtime.", hiddenDex.take(20))
        val apks = archives.filter { it.endsWith(".apk", true) || it.endsWith(".jar", true) }
        if (apks.isNotEmpty()) out += Finding(MEDIUM, "Payload", "Embedded APK/JAR files",
            "Contains other apps or code libraries inside. Droppers use this to install a second app.", apks.take(20))
        if (strayElf.isNotEmpty()) out += Finding(MEDIUM, "Payload", "Native binaries outside lib/",
            "Executables stored in assets are often extracted and run directly.", strayElf.take(20))
        return disguised.isNotEmpty() || hiddenDex.isNotEmpty() || apks.isNotEmpty() || strayElf.isNotEmpty()
    }

    private fun checkCombos(m: Manifest, embeddedPayload: Boolean, code: Set<String>, out: MutableList<Finding>) {
        val perms = m.permissions.toSet()
        val a11y = m.components.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" }
        val admin = m.components.any { it.permission == "android.permission.BIND_DEVICE_ADMIN" }
        val notif = m.components.any { it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" }
        val smsRead = perms.any { it.endsWith(".RECEIVE_SMS") || it.endsWith(".READ_SMS") } ||
            m.components.any { "android.provider.Telephony.SMS_RECEIVED" in it.actions }
        val overlay = "android.permission.SYSTEM_ALERT_WINDOW" in perms
        val internet = "android.permission.INTERNET" in perms
        val boot = m.components.any { "android.intent.action.BOOT_COMPLETED" in it.actions }

        if (a11y && overlay) out += Finding(CRITICAL, "Combination", "Banking-trojan pattern: overlay + accessibility",
            "Can show fake login screens over your apps and control the phone. This exact combo is used by Anatsa, Cerberus, Hydra and others.")
        if (a11y && smsRead) out += Finding(CRITICAL, "Combination", "Accessibility control plus SMS access",
            "Can read your OTP codes and approve actions on screen by itself.")
        if (a11y && admin) out += Finding(CRITICAL, "Combination", "Accessibility plus device admin",
            "Can control the phone and resist removal.")
        if ((smsRead || notif) && internet) out += Finding(HIGH, "Combination", "Can steal OTP codes",
            "Reads SMS or notifications and has internet access to send them out.")
        if (!m.hasLauncher && (boot || a11y || admin)) out += Finding(HIGH, "Combination", "Hidden app with background persistence",
            "No app icon, yet it auto-starts or gains special powers. Typical of spyware.")
        if ("android.permission.REQUEST_INSTALL_PACKAGES" in perms && (embeddedPayload || "Ldalvik/system/DexClassLoader;" in code)) {
            out += Finding(HIGH, "Combination", "Dropper pattern",
                "Can install apps and carries or loads extra code. Typical of malware droppers.")
        }
        if ("sendTextMessage" in code && "android.permission.SEND_SMS" in perms && !m.hasLauncher) {
            out += Finding(CRITICAL, "Combination", "Hidden SMS sender", "Sends SMS with no visible app icon. Classic premium-SMS fraud.")
        }
    }

    private fun shortPerm(p: String) = p.substringAfterLast('.').replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        private val DEX_NAME = Regex("classes\\d*\\.dex")
        private const val MAX_URLS = 500
        private val URL_RE = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{3,200}")
        private val IP_URL_RE = Regex("https?://\\d{1,3}(\\.\\d{1,3}){3}")
        private val BENIGN_URL_HOSTS = listOf(
            "schemas.android.com", "www.w3.org", "www.apache.org", "xmlpull.org", "ns.adobe.com",
            "purl.org", "xml.org", "json-schema.org", "www.example.com",
        )
    }
}
