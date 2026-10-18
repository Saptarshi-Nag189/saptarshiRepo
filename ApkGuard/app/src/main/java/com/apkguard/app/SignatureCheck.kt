package com.apkguard.app

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.apkguard.core.Finding
import com.apkguard.core.Severity
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Uses Android's own APK verifier (PackageManager) to check the signature
 * and compare it against any installed app with the same package name.
 * PackageManager only parses the file; it never installs or runs it.
 */
object SignatureCheck {

    class Result(val findings: List<Finding>, val certSha256: String?, val signer: String?)

    fun run(pm: PackageManager, apkPath: String, parsedPackage: String?): Result {
        val out = ArrayList<Finding>()
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info: PackageInfo? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apkPath, flags)
            }
        } catch (t: Throwable) {
            null
        }
        if (info == null) {
            out += Finding(Severity.HIGH, "Signature", "Android rejected this APK",
                "Android's own package parser could not read it. It will not install, or it was crafted to confuse tools.")
            return Result(out, null, null)
        }
        if (parsedPackage != null && info.packageName != parsedPackage) {
            out += Finding(Severity.HIGH, "Anti-analysis", "Package name mismatch",
                "Android sees '${info.packageName}' but the raw manifest says '$parsedPackage'. The manifest was crafted to mislead scanners.")
        }
        val signing = info.signingInfo
        val signers = when {
            signing == null -> emptyArray()
            signing.hasMultipleSigners() -> signing.apkContentsSigners
            else -> signing.signingCertificateHistory ?: emptyArray()
        }
        if (signers.isEmpty()) {
            out += Finding(Severity.HIGH, "Signature", "Signature is invalid or missing",
                "Android could not verify a signature. The file is damaged or has been modified after signing.")
            return Result(out, null, null)
        }
        // For rotated keys the last entry in the history is the current signer.
        val current = signers.last().toByteArray()
        val sha = sha256(current)
        val cert = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(current)) as X509Certificate
        }.getOrNull()
        val subject = cert?.subjectX500Principal?.name

        if (subject != null) {
            val s = subject.lowercase()
            when {
                "cn=android debug" in s -> out += Finding(Severity.MEDIUM, "Signature", "Signed with a debug key",
                    "Built on a developer machine and never signed for release. Real store apps are not signed like this.")
                "android@android.com" in s || (s.contains("cn=android") && s.contains("o=android") && s.contains("ou=android")) ->
                    out += Finding(Severity.HIGH, "Signature", "Signed with a public AOSP test key",
                        "The private key is published online, so anyone can sign anything with it. Often used by malware posing as system apps.")
            }
        }
        cert?.let {
            if (it.notAfter.before(Date())) out += Finding(Severity.LOW, "Signature", "Signing certificate expired",
                "Expired on ${it.notAfter}. Not fatal for install, but unusual for a maintained app.")
        }
        if (signing?.hasMultipleSigners() == true) {
            out += Finding(Severity.INFO, "Signature", "Multiple signers", "This APK is signed by more than one key.")
        }

        val pkg = info.packageName
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(pkg, flags)
            }
        }.getOrNull()
        if (installed != null) {
            val installedCerts = installed.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
            if (sha in installedCerts) {
                out += Finding(Severity.INFO, "Signature", "Same developer as the installed app",
                    "You already have $pkg installed and this APK is signed with the same key.")
            } else {
                out += Finding(Severity.CRITICAL, "Signature", "Fake copy of an installed app",
                    "You have $pkg installed, but this APK is signed by a DIFFERENT key. It is a repackaged or counterfeit version.")
            }
        }

        out += Finding(Severity.INFO, "Signature", "Signer certificate",
            "Compare this fingerprint with the one the official developer publishes.",
            listOfNotNull(subject, "SHA-256: $sha"))
        return Result(out, sha, subject)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
}
