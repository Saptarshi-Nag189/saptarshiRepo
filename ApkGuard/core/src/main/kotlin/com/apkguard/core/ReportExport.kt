package com.apkguard.core

/** Turns a [Report] into shareable files. Pure string building; no I/O. */
object ReportExport {

    class Meta(val fileName: String?, val sha256: String?, val scannedAt: String, val appVersion: String)

    fun text(r: Report, m: Meta, evidence: Boolean): String = buildString {
        appendLine("APK Guard report")
        appendLine("Verdict: ${r.verdict.label}, risk score ${r.score}/100")
        appendLine(r.verdict.advice)
        appendLine()
        m.fileName?.let { appendLine("File: $it") }
        appendLine("Package: ${r.info.packageName ?: "unknown"}")
        r.info.versionName?.let { appendLine("Version: $it (${r.info.versionCode})") }
        m.sha256?.let { appendLine("SHA-256: $it") }
        appendLine("Scanned: ${m.scannedAt} with APK Guard ${m.appVersion}")
        appendLine()
        for (f in r.sortedFindings) {
            appendLine("[${f.severity.label}] ${f.title}")
            appendLine("  ${f.detail}")
            if (evidence) f.evidence.forEach { appendLine("  - $it") }
        }
        appendLine()
        appendLine("Static scan only. Encrypted or downloaded code can stay hidden.")
    }

    fun json(r: Report, m: Meta, evidence: Boolean): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"tool\": ").append(q("APK Guard ${m.appVersion}")).append(",\n")
        sb.append("  \"scannedAt\": ").append(q(m.scannedAt)).append(",\n")
        sb.append("  \"file\": ").append(q(m.fileName)).append(",\n")
        sb.append("  \"sha256\": ").append(q(m.sha256)).append(",\n")
        sb.append("  \"verdict\": ").append(q(r.verdict.name)).append(",\n")
        sb.append("  \"score\": ").append(r.score).append(",\n")
        val i = r.info
        sb.append("  \"app\": {")
        sb.append("\"package\": ").append(q(i.packageName)).append(", ")
        sb.append("\"versionName\": ").append(q(i.versionName)).append(", ")
        sb.append("\"versionCode\": ").append(q(i.versionCode)).append(", ")
        sb.append("\"minSdk\": ").append(i.minSdk ?: "null").append(", ")
        sb.append("\"targetSdk\": ").append(i.targetSdk ?: "null").append(", ")
        sb.append("\"permissions\": [").append(i.permissions.joinToString(", ") { q(it) }).append("]")
        sb.append("},\n")
        sb.append("  \"findings\": [\n")
        sb.append(r.sortedFindings.joinToString(",\n") { f ->
            buildString {
                append("    {\"severity\": ").append(q(f.severity.name))
                append(", \"category\": ").append(q(f.category))
                append(", \"title\": ").append(q(f.title))
                append(", \"detail\": ").append(q(f.detail))
                if (evidence) append(", \"evidence\": [").append(f.evidence.joinToString(", ") { q(it) }).append("]")
                append("}")
            }
        })
        sb.append("\n  ]\n}\n")
        return sb.toString()
    }

    fun html(r: Report, m: Meta, evidence: Boolean): String {
        val sev = mapOf(
            Severity.CRITICAL to "#c62828", Severity.HIGH to "#d84315", Severity.MEDIUM to "#a26b00",
            Severity.LOW to "#1f5fa8", Severity.INFO to "#5a606b",
        )
        val verdictColor = when (r.verdict) {
            Verdict.DANGEROUS -> "#c62828"; Verdict.SUSPICIOUS -> "#a26b00"; Verdict.LOW_RISK -> "#1e7b3a"
        }
        return buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            // No scripts, no remote resources: the report is safe to open anywhere.
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">")
            append("<title>APK Guard report: ").append(h(r.info.packageName ?: "unknown")).append("</title>")
            append("<style>body{font:16px/1.5 system-ui,sans-serif;margin:0;padding:24px;max-width:760px;margin:auto;color:#1a1c20;background:#fff}")
            append("h1{font-size:28px;margin:0 0 4px}.v{font-size:56px;font-weight:800;margin:0}.m{color:#5a606b}")
            append(".f{border:1px solid #e3e6ea;border-radius:16px;padding:14px 16px;margin:10px 0}.s{font-weight:700;font-size:13px}")
            append("code,pre{font:13px/1.5 ui-monospace,monospace;white-space:pre-wrap;overflow-wrap:anywhere}table{border-collapse:collapse}td{padding:4px 12px 4px 0;vertical-align:top}</style>")
            append("</head><body>")
            append("<h1>APK Guard report</h1>")
            append("<p class=\"v\" style=\"color:").append(verdictColor).append("\">").append(h(r.verdict.label)).append("</p>")
            append("<p><strong>Risk score ").append(r.score).append("/100.</strong> ").append(h(r.verdict.advice)).append("</p>")
            append("<table>")
            row("File", m.fileName); row("Package", r.info.packageName); row("Version", r.info.versionName?.let { "$it (${r.info.versionCode})" })
            row("SHA-256", m.sha256, code = true); row("Scanned", "${m.scannedAt} with APK Guard ${m.appVersion}")
            append("</table><h2>Findings (").append(r.findings.size).append(")</h2>")
            for (f in r.sortedFindings) {
                append("<div class=\"f\"><div class=\"s\" style=\"color:").append(sev[f.severity]).append("\">")
                append(h(f.severity.label)).append(" · ").append(h(f.category)).append("</div>")
                append("<strong>").append(h(f.title)).append("</strong><div>").append(h(f.detail)).append("</div>")
                if (evidence && f.evidence.isNotEmpty()) append("<pre>").append(h(f.evidence.joinToString("\n"))).append("</pre>")
                append("</div>")
            }
            append("<p class=\"m\">Static scan only. Encrypted or downloaded code can stay hidden.</p></body></html>")
        }
    }

    private fun StringBuilder.row(k: String, v: String?, code: Boolean = false) {
        if (v == null) return
        append("<tr><td class=\"m\">").append(h(k)).append("</td><td>")
        if (code) append("<code>").append(h(v)).append("</code>") else append(h(v))
        append("</td></tr>")
    }

    /** HTML-escapes untrusted text from the scanned APK. */
    fun h(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;")
            '"' -> append("&quot;"); '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** JSON string literal, or null. */
    fun q(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
