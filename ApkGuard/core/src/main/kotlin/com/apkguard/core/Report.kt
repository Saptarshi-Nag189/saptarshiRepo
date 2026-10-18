package com.apkguard.core

enum class Severity(val weight: Int, val label: String) {
    CRITICAL(40, "Critical"),
    HIGH(18, "High"),
    MEDIUM(7, "Medium"),
    LOW(2, "Low"),
    INFO(0, "Info"),
}

enum class Verdict(val label: String, val advice: String) {
    DANGEROUS("Dangerous", "Strong signs of malicious capability. Do not install."),
    SUSPICIOUS("Suspicious", "Risky capabilities found. Install only if you fully trust the source."),
    LOW_RISK("Low risk", "No major red flags found. Static scans cannot prove an app is safe."),
}

data class Finding(
    val severity: Severity,
    val category: String,
    val title: String,
    val detail: String,
    val evidence: List<String> = emptyList(),
)

data class ApkInfo(
    val packageName: String?,
    val versionName: String?,
    val versionCode: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val fileSize: Long,
    val entryCount: Int,
    val dexCount: Int,
    val nativeAbis: List<String>,
    val permissions: List<String>,
    val signing: SigningBlock.Schemes?,
    val urls: List<String>,
)

data class Report(val info: ApkInfo, val findings: List<Finding>) {

    val score: Int
        get() {
            // Count repeated findings of the same title once so large apps aren't over-penalised.
            // Permissions alone are weak evidence (many legit tools request lots), so cap their share.
            val unique = findings.distinctBy { it.title }
            val total = unique.groupBy { it.category }.entries.sumOf { (cat, list) ->
                val sum = list.sumOf { it.severity.weight }
                CATEGORY_CAPS[cat]?.let { minOf(it, sum) } ?: sum
            }
            return minOf(100, total)
        }

    val verdict: Verdict
        get() = when {
            findings.any { it.severity == Severity.CRITICAL } || score >= 70 -> Verdict.DANGEROUS
            score >= 30 -> Verdict.SUSPICIOUS
            else -> Verdict.LOW_RISK
        }

    val sortedFindings: List<Finding>
        get() = findings.sortedBy { it.severity.ordinal }

    companion object {
        private val CATEGORY_CAPS = mapOf("Permission" to 30, "Manifest" to 15, "Network" to 20)
    }

    fun plus(extra: List<Finding>): Report = copy(findings = findings + extra)

    fun toText(sha256: String?): String = buildString {
        appendLine("APK Guard report")
        appendLine("Verdict: ${verdict.label} (risk score $score/100)")
        appendLine(verdict.advice)
        info.packageName?.let { appendLine("Package: $it") }
        info.versionName?.let { appendLine("Version: $it (${info.versionCode})") }
        sha256?.let { appendLine("SHA-256: $it") }
        appendLine()
        for (f in sortedFindings) {
            appendLine("[${f.severity.label}] ${f.title}")
            appendLine("  ${f.detail}")
            f.evidence.take(10).forEach { appendLine("  - $it") }
        }
    }
}
