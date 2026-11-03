package com.apkguard.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.apkguard.core.ApkScanner
import com.apkguard.core.Finding
import com.apkguard.core.Report
import com.apkguard.core.Severity
import com.apkguard.core.Verdict
import com.apkguard.core.ZipFormatException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single screen: pick an APK, scan it offline, show the report.
 * The APK is copied into app-private storage, analysed as plain bytes,
 * and deleted immediately afterwards. It is never installed or executed.
 */
class MainActivity : Activity() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cancelFlag = AtomicBoolean(false)
    private var scanning = false

    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickButton: Button
    private lateinit var cancelButton: Button

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        cleanupStaleFiles()
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        cancelFlag.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) startScan(uri)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) data?.data?.let { startScan(it) }
    }

    private fun pickApk() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Many file managers label APKs as octet-stream, so allow both.
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip"))
        }
        startActivityForResult(i, REQ_PICK)
    }

    private fun startScan(uri: Uri) {
        if (scanning) return
        // Only accept content:// URIs from the system picker or a sharing app; never file:// paths.
        if (uri.scheme != "content") {
            toast("Unsupported source. Please pick the file with the file picker.")
            return
        }
        scanning = true
        cancelFlag = AtomicBoolean(false)
        val cancel = cancelFlag
        val displayName = queryName(uri)
        setBusy(true, "Copying ${displayName ?: "file"} into private storage")
        content.removeAllViews()

        executor.execute {
            val work = File(noBackupFilesDir, WORK_DIR).apply { mkdirs() }
            val tmp = File(work, UUID.randomUUID().toString() + ".apk")
            try {
                val sha = copyAndHash(uri, tmp, cancel)
                val report = ApkScanner().scan(tmp, progress = { msg -> post { status.text = msg } }, isCancelled = { cancel.get() })
                post { status.text = "Verifying signature with Android" }
                val sig = SignatureCheck.run(packageManager, tmp.absolutePath, report.info.packageName)
                val full = report.plus(sig.findings)
                post { showReport(full, displayName, sha) }
            } catch (e: ApkScanner.CancelledException) {
                post { showMessage("Scan cancelled.") }
            } catch (e: ZipFormatException) {
                post { showMessage("Not a valid APK: ${e.message}\n\nA file that claims to be an APK but isn't a valid archive is itself suspicious.") }
            } catch (e: TooLargeException) {
                post { showMessage("File is larger than ${MAX_APK_BYTES / (1024 * 1024)} MiB and was not scanned.") }
            } catch (e: IOException) {
                post { showMessage("Could not read the file: ${e.message}") }
            } catch (e: OutOfMemoryError) {
                post { showMessage("The file is too complex to scan on this device.") }
            } catch (e: Exception) {
                post { showMessage("Scan failed: ${e.javaClass.simpleName}") }
            } finally {
                tmp.delete()
                post { scanning = false; setBusy(false, null) }
            }
        }
    }

    private class TooLargeException : IOException()

    private fun copyAndHash(uri: Uri, dest: File, cancel: AtomicBoolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open")
        input.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancel.get()) throw ApkScanner.CancelledException()
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_APK_BYTES) throw TooLargeException()
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.take(120)

    private fun cleanupStaleFiles() {
        executor.execute { File(noBackupFilesDir, WORK_DIR).listFiles()?.forEach { it.delete() } }
    }

    private fun post(block: () -> Unit) = main.post { if (!isDestroyed) block() }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(light: Long, darkC: Long) = (if (dark) darkC else light).toInt()
    private val textPrimary get() = color(0xFF1A1C20, 0xFFE6E8EC)
    private val textSecondary get() = color(0xFF5A606B, 0xFFA5ABB6)
    private val cardBg get() = color(0xFFFFFFFF, 0xFF1E2127)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        root.addView(TextView(this).apply {
            text = "v${appVersion()}"
            textSize = 12f
            setTextColor(textSecondary)
        })
        root.addView(TextView(this).apply {
            text = "Offline APK risk scanner. Files never leave your phone and are never installed."
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(12))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickButton = Button(this).apply {
            text = "Choose APK"
            setOnClickListener { pickApk() }
        }
        cancelButton = Button(this).apply {
            text = "Cancel"
            visibility = View.GONE
            setOnClickListener { cancelFlag.set(true) }
        }
        row.addView(pickButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(cancelButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(row)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = TextView(this).apply { setTextColor(textSecondary); setPadding(0, dp(4), 0, dp(4)) }
        root.addView(status)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        showMessage("Pick a downloaded APK, or use \"Open with\" / \"Share\" from your file manager or browser.")
    }

    private fun setBusy(busy: Boolean, msg: String?) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        pickButton.isEnabled = !busy
        status.text = msg ?: ""
    }

    private fun showMessage(msg: String) {
        content.removeAllViews()
        content.addView(card(cardBg).apply {
            addView(TextView(this@MainActivity).apply { text = msg; setTextColor(textPrimary) })
        })
    }

    private fun card(bg: Int, stroke: Int? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(bg)
            stroke?.let { setStroke(dp(1), it) }
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun sevColor(s: Severity) = when (s) {
        Severity.CRITICAL -> color(0xFFB3261E, 0xFFFF8A80)
        Severity.HIGH -> color(0xFFD9480F, 0xFFFFAB70)
        Severity.MEDIUM -> color(0xFFB7791F, 0xFFF2C94C)
        Severity.LOW -> color(0xFF3B6EA8, 0xFF8AB4F8)
        Severity.INFO -> color(0xFF5A606B, 0xFFA5ABB6)
    }

    private fun showReport(r: Report, name: String?, sha: String) {
        content.removeAllViews()
        val v = r.verdict
        val (bg, fg) = when (v) {
            Verdict.DANGEROUS -> color(0xFFFDE7E7, 0xFF3A1A1A) to color(0xFFB3261E, 0xFFFF8A80)
            Verdict.SUSPICIOUS -> color(0xFFFFF4E0, 0xFF3A2E14) to color(0xFFB45309, 0xFFF2C94C)
            Verdict.LOW_RISK -> color(0xFFE6F4EA, 0xFF16301F) to color(0xFF1E7B3A, 0xFF81C995)
        }
        content.addView(card(bg).apply {
            addView(TextView(this@MainActivity).apply {
                text = v.label
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(fg)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Risk score ${r.score}/100\n${v.advice}"
                setTextColor(textPrimary)
            })
        })

        val i = r.info
        content.addView(card(cardBg).apply {
            val lines = buildList {
                name?.let { add("File: $it") }
                add("Package: ${i.packageName ?: "unknown"}")
                i.versionName?.let { add("Version: $it (${i.versionCode})") }
                add("SDK: min ${i.minSdk ?: "?"}, target ${i.targetSdk ?: "?"}")
                add("Size: ${"%.1f".format(i.fileSize / 1048576.0)} MiB, ${i.entryCount} files, ${i.dexCount} DEX")
                if (i.nativeAbis.isNotEmpty()) add("Native code: ${i.nativeAbis.joinToString()}")
                i.signing?.let { s ->
                    add("Signature schemes: " + listOfNotNull("v1".takeIf { s.v1 }, "v2".takeIf { s.v2 }, "v3".takeIf { s.v3 }, "v3.1".takeIf { s.v31 })
                        .ifEmpty { listOf("none") }.joinToString())
                }
                add("Permissions requested: ${i.permissions.size}")
            }
            addView(TextView(this@MainActivity).apply {
                text = lines.joinToString("\n")
                setTextColor(textPrimary)
                setTextIsSelectable(true)
            })
            addView(TextView(this@MainActivity).apply {
                text = "SHA-256\n$sha"
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(textSecondary)
                setPadding(0, dp(8), 0, 0)
                setTextIsSelectable(true)
            })
            addView(Button(this@MainActivity).apply {
                text = "Copy SHA-256"
                setOnClickListener {
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("SHA-256", sha))
                    toast("Copied. You can look it up on VirusTotal from a browser.")
                }
            })
        })

        val sorted = r.sortedFindings
        if (sorted.isEmpty()) content.addView(card(cardBg).apply {
            addView(TextView(this@MainActivity).apply { text = "No findings."; setTextColor(textPrimary) })
        })
        for (f in sorted) content.addView(findingCard(f))

        content.addView(TextView(this).apply {
            text = "Static analysis only. A clean result is not a guarantee: code can be hidden, encrypted or downloaded later. Prefer official stores."
            setTextColor(textSecondary)
            textSize = 12f
            setPadding(dp(4), dp(16), dp(4), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    private fun findingCard(f: Finding) = card(cardBg).apply {
        val sc = sevColor(f.severity)
        addView(TextView(this@MainActivity).apply {
            text = "${f.severity.label.uppercase()}  ·  ${f.category}"
            setTextColor(sc)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = f.title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
            setPadding(0, dp(2), 0, dp(2))
        })
        addView(TextView(this@MainActivity).apply { text = f.detail; setTextColor(textSecondary) })
        if (f.evidence.isNotEmpty()) {
            val shown = f.evidence.take(EVIDENCE_PREVIEW)
            val ev = TextView(this@MainActivity).apply {
                text = shown.joinToString("\n") { "• $it" }
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(textSecondary)
                setPadding(0, dp(6), 0, 0)
                setTextIsSelectable(true)
            }
            addView(ev)
            if (f.evidence.size > EVIDENCE_PREVIEW) {
                addView(Button(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Show all ${f.evidence.size}"
                    setOnClickListener {
                        ev.text = f.evidence.joinToString("\n") { "• $it" }
                        visibility = View.GONE
                    }
                })
            }
        }
    }

    private fun appVersion(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName
        }
    }.getOrNull() ?: "?"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PICK = 1
        private const val WORK_DIR = "scan"
        private const val EVIDENCE_PREVIEW = 5
        private const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024
    }
}
