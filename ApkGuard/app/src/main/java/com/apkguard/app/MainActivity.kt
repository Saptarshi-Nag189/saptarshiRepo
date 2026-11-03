package com.apkguard.app

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.apkguard.core.ApkScanner
import com.apkguard.core.Containers
import com.apkguard.core.Finding
import com.apkguard.core.Report
import com.apkguard.core.ReportExport
import com.apkguard.core.Severity
import com.apkguard.core.Verdict
import com.apkguard.core.ZipFormatException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * APK Guard's single activity. Tabs: Scan, Live check, History, About.
 * A scanned file is copied into app-private storage, read as plain bytes and deleted.
 * It is never installed or executed.
 */
class MainActivity : Activity() {

    private enum class Tab { SCAN, LIVE, HISTORY, ABOUT }

    /** Where the scanned bytes come from: a picked/shared document, or an installed app's APK. */
    private sealed class Source(val label: String) {
        class Doc(val uri: Uri, label: String) : Source(label)
        class Installed(val file: File, label: String) : Source(label)
    }

    private class Scanned(val report: Report, val source: Source, val sha: String, val at: Long)

    private lateinit var ui: Ui
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private lateinit var scrim: View
    private var insetTop = 0
    private var insetBottom = 0
    private var navVisible = true

    private var tab = Tab.SCAN
    private var onBack: (() -> Unit)? = null
    private var scanning = false
    private var cancel = AtomicBoolean(false)
    private var last: Scanned? = null
    private var live: LiveCheck.Result? = null

    // Result screen state
    private var filter: Severity? = null
    private val expanded = HashSet<String>()

    // Export waiting for the system "save as" picker
    private var pendingExport: Pair<ReportFiles.Format, Boolean>? = null

    private val appVersion: String by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        goEdgeToEdge()
        buildShell()
        cleanupStaleFiles()
        showHome()
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        cancel.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Still delivered when predictive back is not opted in")
    override fun onBackPressed() {
        val b = onBack
        if (b != null) b() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- window and shell

    /** Android 15 forces edge-to-edge; do the same on older versions and pad with insets. */
    private fun goEdgeToEdge() {
        val w = window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        @Suppress("DEPRECATION") run {
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // Our own bottom bar provides contrast, so keep the system bar fully transparent.
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
        w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
    }

    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(Colors.BG) }
        content = ui.column(12)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Translucent protection behind the status bar so scrolled text stays readable.
        scrim = View(this).apply { setBackgroundColor(0xD1000000.toInt()) }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, 0, Gravity.TOP))
        nav = buildNav()
        root.addView(nav, FrameLayout.LayoutParams(MATCH_PARENT, ui.dp(68), Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetTop = i.top
                insetBottom = i.bottom
            } else {
                @Suppress("DEPRECATION") run {
                    insetTop = maxOf(insets.systemWindowInsetTop, insets.displayCutout?.safeInsetTop ?: 0)
                    insetBottom = insets.systemWindowInsetBottom
                }
            }
            applyInsets()
            insets
        }
        setContentView(root)
    }

    private fun applyInsets() {
        scrim.layoutParams = (scrim.layoutParams as FrameLayout.LayoutParams).apply { height = insetTop }
        nav.layoutParams = (nav.layoutParams as FrameLayout.LayoutParams).apply {
            setMargins(ui.dp(12), 0, ui.dp(12), insetBottom + ui.dp(8))
        }
        val bottom = if (navVisible) insetBottom + ui.dp(68 + 24) else insetBottom + ui.dp(24)
        content.setPadding(ui.dp(16), insetTop + ui.dp(8), ui.dp(16), bottom)
        scrim.requestLayout()
        nav.requestLayout()
    }

    private val navItems = ArrayList<Pair<Tab, LinearLayout>>()

    private fun buildNav(): LinearLayout {
        val bar = ui.row().apply {
            background = ui.rounded(Colors.NAV, 999, 0xFF3D3D3D.toInt())
            setPadding(ui.dp(6), 0, ui.dp(6), 0)
            elevation = ui.dpf(6)
        }
        for ((t, label, icon) in listOf(
            Triple(Tab.SCAN, "Scan", R.drawable.ic_file),
            Triple(Tab.LIVE, "Live", R.drawable.ic_pulse),
            Triple(Tab.HISTORY, "History", R.drawable.ic_history),
            Triple(Tab.ABOUT, "About", R.drawable.ic_info),
        )) {
            val item = ui.column().apply {
                gravity = Gravity.CENTER
                minimumHeight = ui.dp(56)
                isClickable = true
                isFocusable = true
                contentDescription = label
                background = ui.ripple(GradientDrawable(), 999)
                setOnClickListener { selectTab(t) }
                val bubble = FrameLayout(this@MainActivity).apply {
                    tag = "bubble"
                    addView(ui.icon(icon, Colors.TEXT2).apply {
                        tag = "icon"
                        layoutParams = FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER)
                    })
                }
                addView(bubble, LinearLayout.LayoutParams(ui.dp(44), ui.dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(ui.text(label, 11f, 700, Colors.TEXT2).apply { tag = "label"; gravity = Gravity.CENTER })
            }
            navItems += t to item
            bar.addView(item, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        return bar
    }

    private fun refreshNav() {
        for ((t, item) in navItems) {
            val on = t == tab
            val bubble = item.findViewWithTag<FrameLayout>("bubble")
            bubble.background = if (on) ui.rounded(Colors.ACCENT, 999) else null
            bubble.elevation = if (on) ui.dpf(4) else 0f
            item.findViewWithTag<ImageView>("icon").imageTintList = ColorStateList.valueOf(if (on) Colors.ON_ACCENT else Colors.TEXT2)
            item.findViewWithTag<TextView>("label").setTextColor(if (on) Colors.TEXT else Colors.TEXT2)
            item.isSelected = on
        }
    }

    private fun selectTab(t: Tab) {
        if (scanning && t != Tab.SCAN) { toast("Finish or cancel the scan first."); return }
        tab = t
        when (t) {
            Tab.SCAN -> showHome()
            Tab.LIVE -> showLive(refresh = live == null)
            Tab.HISTORY -> showHistory()
            Tab.ABOUT -> showAbout()
        }
    }

    /** Replaces the screen. [back] handles the system back gesture; null exits the app. */
    private fun screen(showNav: Boolean, back: (() -> Unit)?, build: LinearLayout.() -> Unit) {
        navVisible = showNav
        nav.visibility = if (showNav) View.VISIBLE else View.GONE
        onBack = back
        content.removeAllViews()
        content.build()
        applyInsets()
        refreshNav()
        scroll.scrollTo(0, 0)
    }

    private fun header(title: String?, back: (() -> Unit)?, trailing: View? = null) = ui.row(8).apply {
        back?.let { addView(ui.roundIcon(R.drawable.ic_back, "Back") { it() }) }
        addView(ui.text(title ?: "", 17f, 700).apply { setPadding(ui.dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        trailing?.let { addView(it) }
    }

    private fun circleIcon(res: Int, bg: Int, tint: Int, sizeDp: Int, iconDp: Int = 20) = FrameLayout(this).apply {
        background = ui.rounded(bg, 999)
        addView(ui.icon(res, tint, iconDp).apply { layoutParams = FrameLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp), Gravity.CENTER) })
        layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
    }

    // ---------------------------------------------------------------- Scan tab

    private fun showHome() {
        tab = Tab.SCAN
        screen(showNav = true, back = null) {
            addView(ui.row(10).apply {
                setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(4))
                addView(circleIcon(R.drawable.ic_shield, Colors.ACCENT, Colors.ON_ACCENT, 40, 22))
                addView(ui.column().apply {
                    addView(ui.text("APK Guard", 18f, 800))
                    addView(ui.text("v$appVersion · offline", 12f, 500, Colors.TEXT2, mono = true))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(ui.roundIcon(R.drawable.ic_info, "About APK Guard") { selectTab(Tab.ABOUT) })
            })

            addView(ui.card(Colors.ACCENT, 32, 22, 24, gap = 14).apply {
                addView(ui.text("Check an app before you install it", 32f, 800, Colors.ON_ACCENT).apply {
                    letterSpacing = -0.03f
                    setLineSpacing(0f, 1.0f)
                })
                addView(ui.text("Pick the file you downloaded. APK Guard reads it here on your phone and tells you what the app can do.",
                    15f, 500, 0xFF2E3410.toInt()))
                addView(ui.row().apply {
                    background = ui.ripple(ui.rounded(Colors.BG, 999), 999)
                    minimumHeight = ui.dp(56)
                    setPadding(ui.dp(24), ui.dp(7), ui.dp(7), ui.dp(7))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Choose a file"
                    setOnClickListener { pickFile() }
                    addView(ui.text("Choose a file", 17f, 700, 0xFFF3F5EA.toInt()), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(circleIcon(R.drawable.ic_arrow_up_right, Colors.ACCENT, Colors.ON_ACCENT, 42))
                })
            })

            val needs = live?.needsLook?.size
            val lastScan = History.load(this@MainActivity).firstOrNull()
            addView(ui.row(12).apply {
                addView(ui.card(radiusDp = 28, padH = 18, padV = 18).apply {
                    isClickable = true
                    isFocusable = true
                    background = ui.ripple(ui.rounded(Colors.SURFACE, 28), 28)
                    contentDescription = "Live check"
                    setOnClickListener { selectTab(Tab.LIVE) }
                    addView(ui.row().apply {
                        addView(circleIcon(R.drawable.ic_pulse, Colors.RAISED, Colors.ACCENT, 40))
                        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                        if (needs != null && needs > 0) addView(ui.text("$needs alert${if (needs == 1) "" else "s"}", 12f, 700, Colors.ON_ACCENT).apply {
                            background = ui.rounded(Colors.HIGH, 999)
                            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
                        })
                    })
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
                    addView(ui.text("Live check", 17f, 700))
                    addView(ui.text(if (needs == null) "Tap to check your apps" else "Apps with risky powers on", 13f, 500, Colors.TEXT2))
                }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(ui.card(radiusDp = 28, padH = 18, padV = 18).apply {
                    addView(ui.text("Last scan", 13f, 500, Colors.TEXT2))
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
                    if (lastScan == null) {
                        addView(ui.text("None yet", 20f, 800, Colors.TEXT3))
                    } else {
                        addView(ui.text(lastScan.score.toString(), 44f, 800, verdictColor(lastScan.verdict)).apply { letterSpacing = -0.04f })
                        addView(ui.ellipsize(ui.text("${lastScan.name} · ${verdictLabel(lastScan.verdict).lowercase()}", 13f, 500, Colors.TEXT2)))
                    }
                }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(150)))

            addView(ui.card(radiusDp = 28, padH = 18, padV = 16, gap = 10).apply {
                addView(ui.text("Opens these files", 13f, 500, Colors.TEXT2))
                addView(ui.flow().apply {
                    listOf(".apk", ".zip", ".xapk", ".apks", ".apkm", ".rar", ".tar.gz").forEach { addView(ui.chip(it)) }
                })
            })
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_PICK)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            // Only content:// from a picker or a sharing app; never file:// paths.
            if (uri.scheme != "content") showError("Can't open this file", "Pick it with the file picker instead.")
            else startScan(Source.Doc(uri, queryName(uri) ?: "file"))
        }
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PICK -> startScan(Source.Doc(uri, queryName(uri) ?: "file"))
            REQ_SAVE -> pendingExport?.let { (f, ev) -> saveExport(uri, f, ev) }
        }
    }

    private enum class Step(val label: String) {
        OPEN("Opening the file"), MANIFEST("Reading the app's manifest"), CODE("Checking the code"),
        FILES("Looking for hidden files"), SIGNATURE("Checking the signature"),
    }

    private fun startScan(src: Source) {
        if (scanning) return
        scanning = true
        tab = Tab.SCAN
        cancel = AtomicBoolean(false)
        val token = cancel
        val stepViews = HashMap<Step, Pair<LinearLayout, TextView>>()
        lateinit var detail: TextView

        screen(showNav = false, back = { token.set(true) }) {
            addView(ui.column(4).apply {
                setPadding(ui.dp(4), ui.dp(16), 0, 0)
                addView(ui.text("Scanning", 13f, 500, Colors.TEXT2))
                addView(ui.ellipsize(ui.text(src.label, 22f, 800)))
                detail = ui.text("", 13f, 500, Colors.TEXT2, mono = true)
                addView(detail)
            })
            addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Colors.ACCENT)
                contentDescription = "Scan in progress"
            }, ui.lp(top = 8))
            addView(ui.column(4).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                for (s in Step.entries) {
                    val label = ui.text(s.label, 15f, 500, Colors.TEXT3)
                    val r = ui.row(12).apply {
                        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                        addView(ui.icon(R.drawable.ic_info, 0xFF5C636D.toInt()))
                        addView(label)
                    }
                    stepViews[s] = r to label
                    addView(r)
                }
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(ui.outlinePill("Cancel", heightDp = 52) { token.set(true) }, ui.lp(top = 24))
        }

        fun mark(active: Step) {
            for (s in Step.entries) {
                val (r, label) = stepViews[s] ?: continue
                val done = s.ordinal < active.ordinal
                val now = s == active
                (r.getChildAt(0) as ImageView).apply {
                    setImageResource(if (done) R.drawable.ic_check else if (now) R.drawable.ic_rescan else R.drawable.ic_info)
                    imageTintList = ColorStateList.valueOf(if (done) Colors.OK else if (now) Colors.ACCENT else 0xFF5C636D.toInt())
                }
                label.setTextColor(if (now) Colors.TEXT else if (done) Colors.TEXT2 else Colors.TEXT3)
                label.typeface = ui.font(if (now) 700 else 500)
                r.background = if (now) ui.rounded(Colors.SURFACE, 16) else null
            }
        }
        mark(Step.OPEN)

        executor.execute {
            val work = File(noBackupFilesDir, WORK_DIR).apply { mkdirs() }
            val tmp = File(work, UUID.randomUUID().toString() + ".bin")
            try {
                val sha = when (src) {
                    is Source.Doc -> copyAndHash(contentResolver.openInputStream(src.uri) ?: throw IOException("Cannot open"), tmp, token)
                    is Source.Installed -> copyAndHash(src.file.inputStream(), tmp, token)
                }
                post { detail.text = "%.1f MB".format(tmp.length() / 1048576.0) }
                val report = ApkScanner().scan(tmp, progress = { msg ->
                    val step = when {
                        msg.startsWith("Opening") || msg.startsWith("Found") || msg.startsWith("Reading archive") -> Step.OPEN
                        msg.startsWith("Parsing") -> Step.MANIFEST
                        msg.startsWith("Scanning code") -> Step.CODE
                        else -> Step.FILES
                    }
                    post { mark(step); if (msg.startsWith("Found")) detail.text = msg.lowercase() }
                }, isCancelled = { token.get() })
                post { mark(Step.SIGNATURE) }
                // Android's verifier needs the APK itself; for an archive wrapper the core's findings stand alone.
                val wrapped = report.findings.any { it.title == "Scanned the APK inside this archive" }
                val full = if (wrapped) report else report.plus(SignatureCheck.run(packageManager, tmp.absolutePath, report.info.packageName).findings)
                val scanned = Scanned(full, src, sha, System.currentTimeMillis())
                History.add(this, History.Entry(src.label, full.info.packageName, full.score, full.verdict.name, scanned.at))
                post {
                    last = scanned
                    filter = null
                    expanded.clear()
                    showResult()
                }
            } catch (e: ApkScanner.CancelledException) {
                post { showHome(); toast("Scan cancelled.") }
            } catch (e: Containers.UnsupportedArchiveException) {
                post { showError("Can't open this archive", e.message ?: "Unsupported archive.", src.label) }
            } catch (e: ZipFormatException) {
                post { showError("This isn't a valid app file", "${e.message}. A file that claims to be an APK but isn't one is suspicious in itself.", src.label) }
            } catch (e: TooLargeException) {
                post { showError("File too large", "Files over ${MAX_BYTES / (1024 * 1024)} MB are not scanned.", src.label) }
            } catch (e: IOException) {
                post { showError("Couldn't read the file", e.message ?: "Read error.", src.label) }
            } catch (e: OutOfMemoryError) {
                post { showError("Too big to scan here", "The file is too complex to scan on this phone.", src.label) }
            } catch (e: Exception) {
                post { showError("Scan failed", e.javaClass.simpleName, src.label) }
            } finally {
                tmp.delete()
                post { scanning = false }
            }
        }
    }

    private class TooLargeException : IOException()

    private fun copyAndHash(input: InputStream, dest: File, cancel: AtomicBoolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancel.get()) throw ApkScanner.CancelledException()
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw TooLargeException()
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
        executor.execute {
            File(noBackupFilesDir, WORK_DIR).listFiles()?.forEach { it.delete() }
            ReportFiles.shareDir(this).listFiles()?.forEach { it.delete() }
        }
    }

    private fun showError(title: String, message: String, fileName: String? = null) {
        screen(showNav = false, back = { showHome() }) {
            addView(header(null, { showHome() }))
            addView(ui.column(16).apply {
                setPadding(ui.dp(8), ui.dp(24), ui.dp(8), 0)
                addView(FrameLayout(this@MainActivity).apply {
                    background = ui.rounded(Colors.SURFACE, 18)
                    addView(ui.icon(R.drawable.ic_box, Colors.MEDIUM, 30).apply {
                        layoutParams = FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER)
                    })
                }, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)))
                addView(ui.text(title, 24f, 800))
                fileName?.let { addView(ui.text(it, 13f, 500, Colors.TEXT2, mono = true)) }
                addView(ui.text(message, 15f, 500, Colors.TEXT2))
                addView(ui.card(gap = 10).apply {
                    addView(ui.text("Works today", 14f, 700))
                    addView(ui.text(".apk .zip .xapk .apks .apkm .rar (v4) .gz .tar .tar.gz", 13f, 500, 0xFFC9CED6.toInt(), mono = true))
                })
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(ui.pill("Choose another file") { pickFile() }, ui.lp(top = 24))
        }
    }

    // ---------------------------------------------------------------- Result

    private fun verdictColor(v: String) = when (v) {
        Verdict.DANGEROUS.name -> Colors.CRITICAL
        Verdict.SUSPICIOUS.name -> Colors.MEDIUM
        else -> Colors.OK
    }

    private fun verdictLabel(v: String) = runCatching { Verdict.valueOf(v).label }.getOrDefault(v)

    private fun plainSummary(r: Report): String {
        val titles = r.findings.map { it.title }
        if (r.verdict == Verdict.DANGEROUS && titles.any { it.contains("SMS") } && titles.any { it.contains("ccessibility") }) {
            return "This app can read your SMS codes and control your screen. Don't install it."
        }
        return r.verdict.advice
    }

    private fun showResult() {
        val s = last ?: return showHome()
        val r = s.report
        val (bg, fg, sub) = when (r.verdict) {
            Verdict.DANGEROUS -> Triple(Colors.DANGER_BG, Colors.CRITICAL, 0xFFF3C2BE.toInt())
            Verdict.SUSPICIOUS -> Triple(Colors.WARN_BG, Colors.MEDIUM, 0xFFF6E1C6.toInt())
            Verdict.LOW_RISK -> Triple(Colors.OK_BG, Colors.OK, 0xFFBFE6CF.toInt())
        }
        screen(showNav = false, back = { showHome() }) {
            addView(header("Scan result", { showHome() }))

            addView(ui.card(bg, 32, 22, 22, gap = 6).apply {
                addView(ui.row().apply {
                    addView(ui.text(r.verdict.label, 13f, 700, Colors.ON_ACCENT).apply {
                        background = ui.rounded(fg, 999)
                        setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5))
                    })
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(ui.text("risk score", 13f, 500, sub))
                })
                addView(ui.row(6).apply {
                    gravity = Gravity.BOTTOM
                    addView(ui.text(r.score.toString(), 88f, 800, fg).apply { letterSpacing = -0.06f })
                    addView(ui.text("/100", 20f, 700, sub).apply { setPadding(0, 0, 0, ui.dp(14)) })
                })
                addView(LinearLayout(this@MainActivity).apply {
                    background = ui.rounded(0x14FFFFFF, 999)
                    weightSum = 100f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    addView(View(this@MainActivity).apply { background = ui.rounded(fg, 999) },
                        LinearLayout.LayoutParams(0, MATCH_PARENT, r.score.coerceIn(1, 100).toFloat()))
                }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(8)))
                addView(ui.text(plainSummary(r), 15f, 500, Colors.TEXT).apply { setPadding(0, ui.dp(8), 0, 0) })
                contentDescription = "${r.verdict.label}, risk score ${r.score} of 100. ${plainSummary(r)}"
            })

            addView(ui.row(12).apply {
                fun tile(label: String, icon: Int, onClick: () -> Unit) = ui.column(6).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    isFocusable = true
                    contentDescription = label
                    setOnClickListener { onClick() }
                    addView(FrameLayout(this@MainActivity).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Colors.TILE_TOP, Colors.TILE_BOTTOM)).apply {
                            cornerRadius = ui.dpf(20)
                        }
                        foreground = ui.ripple(GradientDrawable(), 20)
                        addView(ui.icon(icon, Colors.ON_ACCENT, 22).apply {
                            layoutParams = FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER)
                        })
                    }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(68)))
                    addView(ui.text(label, 12f, 600, 0xFFC9CED6.toInt()).apply { gravity = Gravity.CENTER })
                }
                addView(tile("Export", R.drawable.ic_export) { showExportSheet() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(tile("Share", R.drawable.ic_share) { shareExport(ReportFiles.Format.TEXT, false) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(tile("Copy hash", R.drawable.ic_copy) { copyHash(s.sha) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(tile("Rescan", R.drawable.ic_rescan) { startScan(s.source) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })

            val i = r.info
            addView(ui.card().apply {
                addView(ui.row(14).apply {
                    addView(FrameLayout(this@MainActivity).apply {
                        background = ui.rounded(Colors.RAISED, 12)
                        addView(ui.icon(R.drawable.ic_phone, Colors.TEXT2, 24).apply {
                            layoutParams = FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER)
                        })
                    }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
                    addView(ui.column(2).apply {
                        addView(ui.ellipsize(ui.text(s.source.label, 16f, 700)))
                        addView(ui.ellipsize(ui.text(i.packageName ?: "unknown package", 13f, 500, Colors.TEXT2, mono = true)))
                        val parts = listOfNotNull(i.versionName?.let { "Version $it" }, i.minSdk?.let { "Android API $it+" },
                            "%.1f MB".format(i.fileSize / 1048576.0))
                        addView(ui.text(parts.joinToString(" · "), 13f, 500, Colors.TEXT2))
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                })
            })

            val counts = Severity.entries.associateWith { sv -> r.findings.count { it.severity == sv } }
            addView(ui.flow(8).apply {
                fun chip(label: String, value: Severity?) = ui.text(label, 13f, 600, if (filter == value) Colors.BG else 0xFFC9CED6.toInt()).apply {
                    gravity = Gravity.CENTER
                    minHeight = ui.dp(36)
                    setPadding(ui.dp(14), 0, ui.dp(14), 0)
                    background = ui.ripple(if (filter == value) ui.rounded(Colors.TEXT, 999) else ui.rounded(0, 999, 0xFF38393E.toInt()), 999)
                    isClickable = true
                    isSelected = filter == value
                    contentDescription = "Show $label"
                    setOnClickListener { filter = value; showResult() }
                }
                addView(chip("All ${r.findings.size}", null))
                for (sv in Severity.entries) if ((counts[sv] ?: 0) > 0) addView(chip("${sv.label} ${counts[sv]}", sv))
            })

            for (f in r.sortedFindings.filter { filter == null || it.severity == filter }) addView(findingCard(f))

            addView(ui.card(Colors.BG, gap = 8).apply {
                background = ui.rounded(Colors.BG, 24, Colors.LINE)
                addView(ui.text("File fingerprint (SHA-256)", 13f, 500, Colors.TEXT2))
                addView(ui.text(s.sha, 12f, 500, 0xFFC9CED6.toInt(), mono = true).apply { setTextIsSelectable(true) })
                addView(ui.outlinePill("Copy fingerprint", heightDp = 44) { copyHash(s.sha) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            })
            addView(ui.text("This scan reads the file without running it. Code that is encrypted or downloaded later can stay hidden.",
                12f, 500, Colors.TEXT3).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), 0) })
        }
    }

    private fun findingCard(f: Finding): View {
        val key = f.severity.name + f.title
        val open = key in expanded
        return ui.card(padH = 16, padV = 14).apply {
            isClickable = true
            isFocusable = true
            background = ui.ripple(ui.rounded(Colors.SURFACE, 24), 24)
            contentDescription = "${f.severity.label}: ${f.title}. ${if (open) "Collapse" else "Expand"}"
            setOnClickListener { if (!expanded.add(key)) expanded.remove(key); showResultKeepScroll() }
            addView(ui.row(12).apply {
                gravity = Gravity.TOP
                addView(View(this@MainActivity).apply { background = ui.rounded(Colors.severity(f.severity), 999) },
                    LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { topMargin = ui.dp(5) })
                addView(ui.column(3).apply {
                    addView(ui.text(f.severity.label, 12f, 700, Colors.severity(f.severity)))
                    addView(ui.text(f.title, 15f, 700))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(ui.icon(R.drawable.ic_chevron_down, Colors.TEXT2, 18).apply { rotation = if (open) 180f else 0f })
            })
            if (open) addView(ui.column(10).apply {
                setPadding(ui.dp(22), ui.dp(10), 0, 0)
                addView(ui.text(f.detail, 14f, 500, 0xFFC9CED6.toInt()))
                if (f.evidence.isNotEmpty()) addView(ui.text(f.evidence.take(40).joinToString("\n"), 12f, 500, Colors.TEXT2, mono = true).apply {
                    background = ui.rounded(0xFF101112.toInt(), 12)
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                    setTextIsSelectable(true)
                })
            })
        }
    }

    private fun showResultKeepScroll() {
        val y = scroll.scrollY
        showResult()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun copyHash(sha: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("SHA-256", sha).apply {
            if (Build.VERSION.SDK_INT >= 33) description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
        })
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < 33) toast("Fingerprint copied")
    }

    // ---------------------------------------------------------------- Export

    private fun meta(s: Scanned) = ReportExport.Meta(
        fileName = s.source.label,
        sha256 = s.sha,
        scannedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(s.at)),
        appVersion = appVersion,
    )

    private fun exportName(s: Scanned, f: ReportFiles.Format) =
        ReportFiles.fileName(s.report, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(s.at)), f)

    private fun showExportSheet() {
        val s = last ?: return
        var format = ReportFiles.Format.PDF
        var evidence = true
        val dialog = Dialog(this, R.style.SheetDialog)
        lateinit var nameView: TextView
        val rows = ui.column(8)

        fun renderRows() {
            rows.removeAllViews()
            for (f in ReportFiles.Format.entries) {
                val on = f == format
                rows.addView(ui.row(12).apply {
                    minimumHeight = ui.dp(56)
                    setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                    background = ui.ripple(ui.rounded(if (on) 0xFF2A2C12.toInt() else 0xFF141517.toInt(), 18,
                        if (on) Colors.ACCENT else 0xFF2A2B30.toInt()), 18)
                    isClickable = true
                    isSelected = on
                    contentDescription = "${f.label}, ${f.note}${if (on) ", selected" else ""}"
                    setOnClickListener { format = f; renderRows(); nameView.text = exportName(s, format) }
                    addView(View(this@MainActivity).apply {
                        background = if (on) ui.rounded(Colors.ACCENT, 999) else ui.rounded(0, 999, Colors.TEXT2)
                    }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
                    addView(ui.column(2).apply {
                        addView(ui.text(f.label, 15f, 700))
                        addView(ui.text(f.note, 13f, 500, Colors.TEXT2))
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(ui.text(".${f.ext}", 12f, 500, Colors.TEXT3, mono = true))
                })
            }
        }

        val sheet = ui.column(16).apply {
            background = GradientDrawable().apply {
                setColor(Colors.SURFACE)
                cornerRadii = floatArrayOf(ui.dpf(28), ui.dpf(28), ui.dpf(28), ui.dpf(28), 0f, 0f, 0f, 0f)
            }
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), insetBottom + ui.dp(20))
            addView(View(this@MainActivity).apply { background = ui.rounded(0xFF38393E.toInt(), 999) },
                LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(ui.column(4).apply {
                addView(ui.text("Export report", 20f, 800))
                addView(ui.text("Made on this phone. Nothing is sent anywhere unless you share it.", 14f, 500, Colors.TEXT2))
            })
            addView(rows)
            addView(ui.row(12).apply {
                minimumHeight = ui.dp(48)
                addView(ui.column(2).apply {
                    addView(ui.text("Include technical evidence", 15f, 600))
                    addView(ui.text("Package names, API calls, URLs", 13f, 500, Colors.TEXT2))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(Switch(this@MainActivity).apply {
                    isChecked = true
                    contentDescription = "Include technical evidence"
                    thumbTintList = ColorStateList.valueOf(Colors.ACCENT)
                    setOnCheckedChangeListener { _, c -> evidence = c }
                })
            })
            nameView = ui.text(exportName(s, format), 12f, 500, Colors.TEXT3, mono = true)
            addView(nameView)
            addView(ui.row(8).apply {
                addView(ui.outlinePill("Share…", heightDp = 52) { dialog.dismiss(); shareExport(format, evidence) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(ui.pill("Save to phone") {
                    dialog.dismiss()
                    pendingExport = format to evidence
                    @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = format.mime
                        putExtra(Intent.EXTRA_TITLE, exportName(s, format))
                    }, REQ_SAVE)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
        }
        renderRows()
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
        }
        dialog.show()
    }

    private fun saveExport(uri: Uri, f: ReportFiles.Format, evidence: Boolean) {
        val s = last ?: return
        executor.execute {
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { ReportFiles.write(it, s.report, meta(s), f, evidence) } != null
            }.getOrDefault(false)
            post { toast(if (ok) "Report saved" else "Couldn't save the report") }
        }
    }

    private fun shareExport(f: ReportFiles.Format, evidence: Boolean) {
        val s = last ?: return
        executor.execute {
            val dir = ReportFiles.shareDir(this)
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, exportName(s, f))
            val ok = runCatching { file.outputStream().use { ReportFiles.write(it, s.report, meta(s), f, evidence) } }.isSuccess
            post {
                if (!ok) { toast("Couldn't create the report"); return@post }
                val uri = Uri.Builder().scheme("content").authority(ReportProvider.AUTHORITY).appendPath(file.name).build()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = f.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "APK Guard report: ${s.report.info.packageName ?: s.source.label}")
                    if (f == ReportFiles.Format.TEXT) putExtra(Intent.EXTRA_TEXT, ReportExport.text(s.report, meta(s), evidence))
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(Intent.createChooser(send, "Share report")) }.onFailure { toast("No app can receive this file") }
            }
        }
    }

    // ---------------------------------------------------------------- Live check

    private fun showLive(refresh: Boolean) {
        tab = Tab.LIVE
        val r = live
        if (refresh || r == null) {
            screen(showNav = true, back = { showHome() }) {
                addView(header("Live check", null))
                addView(ui.text("Checking your apps…", 15f, 500, Colors.TEXT2).apply { setPadding(ui.dp(4), ui.dp(24), 0, 0) })
                addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Colors.ACCENT)
                })
            }
            executor.execute {
                val res = runCatching { LiveCheck.run(this) }.getOrNull()
                post {
                    live = res
                    if (tab == Tab.LIVE) {
                        if (res == null) showError("Live check failed", "Android didn't let APK Guard read the app list.") else showLive(false)
                    }
                }
            }
            return
        }
        screen(showNav = true, back = { showHome() }) {
            addView(header("Live check", null, ui.roundIcon(R.drawable.ic_rescan, "Check again") { showLive(true) }))
            val n = r.needsLook.size
            addView(ui.text(if (n == 0) "Nothing needs a look" else "$n app${if (n == 1) "" else "s"} need${if (n == 1) "s" else ""} a look",
                30f, 800).apply { letterSpacing = -0.03f; setPadding(ui.dp(4), ui.dp(8), 0, 0) })
            addView(ui.text("Powers Android only gives when you switch them on. Checked at ${DateFormat.getTimeFormat(this@MainActivity).format(Date(r.checkedAt))}.",
                14f, 500, Colors.TEXT2).apply { setPadding(ui.dp(4), 0, 0, 0) })

            fun tile(count: Int, label: String, on: Boolean) = ui.card(radiusDp = 24, padH = 14, padV = 14, gap = 14).apply {
                background = ui.rounded(Colors.SURFACE, 24, if (on) Colors.ACCENT else 0xFF232427.toInt())
                contentDescription = "$label: $count"
                addView(ui.row().apply {
                    addView(ui.text(count.toString(), 28f, 800).apply { letterSpacing = -0.03f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(FrameLayout(this@MainActivity).apply {
                        background = ui.rounded(if (on) Colors.ACCENT else 0xFF2A2B2F.toInt(), 999)
                        setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        addView(View(this@MainActivity).apply { background = ui.rounded(if (on) Colors.BG else 0xFF6B7078.toInt(), 999) },
                            FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), if (on) Gravity.END else Gravity.START))
                    }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(24)))
                })
                addView(ui.text(label, 13f, 500, 0xFFC9CED6.toInt()))
            }
            addView(ui.row(10).apply {
                addView(tile(r.accessibility, "Apps with Accessibility on", r.accessibility > 0), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(tile(r.admins, "Device admins", r.admins > 0), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            addView(ui.row(10).apply {
                addView(tile(r.notificationReaders, "Read your notifications", r.notificationReaders > 0), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(tile(if (r.defaultSmsChanged) 1 else 0, "Default SMS app changed", r.defaultSmsChanged), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })

            if (r.needsLook.isNotEmpty()) {
                addView(ui.sectionLabel("Needs a look"))
                r.needsLook.forEach { addView(appRow(it, true)) }
            }
            if (r.changes.isNotEmpty()) {
                addView(ui.sectionLabel("Changes since last check"))
                r.changes.take(20).forEach { c ->
                    addView(ui.card(radiusDp = 20, padH = 14, padV = 12).apply {
                        addView(ui.row(12).apply {
                            addView(circleIcon(if (c.title.startsWith("New")) R.drawable.ic_download else R.drawable.ic_plus,
                                if (c.bad) Colors.WARN_BG else Colors.RAISED, if (c.bad) Colors.HIGH else 0xFFC9CED6.toInt(), 36, 18))
                            addView(ui.column(2).apply {
                                addView(ui.text(c.title, 14f, 700))
                                addView(ui.ellipsize(ui.text(c.app, 12f, 500, Colors.TEXT2)))
                            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                            addView(ui.text(DateFormat.getTimeFormat(this@MainActivity).format(Date(c.time)), 12f, 500, Colors.TEXT3))
                        })
                    })
                }
            }
            if (r.fromStore.isNotEmpty()) {
                addView(ui.sectionLabel("From an app store"))
                r.fromStore.forEach { addView(appRow(it, false)) }
            }
            if (r.apps.isEmpty()) addView(ui.card().apply {
                addView(ui.text("No downloaded app holds a risky power right now.", 15f, 500))
            })
            addView(ui.text("Live check reads Android's own settings. It can't see what an app does inside, only which powers it holds and where it came from. Apps that came with the phone are not listed.",
                12f, 500, Colors.TEXT3).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0) })
        }
    }

    private fun appRow(a: LiveCheck.App, flagged: Boolean) = ui.card(radiusDp = 24, padH = 16, padV = 14).apply {
        isClickable = true
        isFocusable = true
        background = ui.ripple(ui.rounded(Colors.SURFACE, 24), 24)
        contentDescription = "${a.label}. ${a.powers.joinToString { it.title }}. Installed by ${a.installerLabel}"
        setOnClickListener { showAppDetail(a) }
        addView(ui.row(12).apply {
            addView(appIcon(a.pkg, 40))
            addView(ui.column(2).apply {
                addView(ui.ellipsize(ui.text(a.label, 15f, 700)))
                addView(ui.text(a.powers.filter { it != LiveCheck.Power.BATTERY }.joinToString(", ") { it.title },
                    13f, 600, if (flagged) Colors.HIGH else Colors.TEXT2))
                addView(ui.text("Installed by ${a.installerLabel}${if (a.hiddenIcon) " · icon hidden" else ""}", 12f, 500, Colors.TEXT2))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ui.icon(R.drawable.ic_chevron_right, Colors.TEXT2, 18))
        })
    }

    private fun appIcon(pkg: String, sizeDp: Int) = ImageView(this).apply {
        val d = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
        if (d != null) setImageDrawable(d) else background = ui.rounded(Colors.RAISED, 10)
        layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun showAppDetail(a: LiveCheck.App) {
        screen(showNav = false, back = { showLive(false) }) {
            addView(header("App details", { showLive(false) }))
            addView(ui.row(14).apply {
                setPadding(ui.dp(4), ui.dp(8), 0, 0)
                addView(appIcon(a.pkg, 56))
                addView(ui.column(2).apply {
                    addView(ui.text(a.label, 20f, 800))
                    addView(ui.ellipsize(ui.text(a.pkg, 13f, 500, Colors.TEXT2, mono = true)))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            if (a.needsLook) addView(ui.card(Colors.WARN_BG).apply {
                addView(ui.row(12).apply {
                    gravity = Gravity.TOP
                    addView(ui.icon(R.drawable.ic_warning, Colors.HIGH, 22))
                    val why = if (!a.fromStore) "This app came from outside an app store" else "This app hides its icon"
                    val held = a.powers.filter { it != LiveCheck.Power.BATTERY }.joinToString(" and ") { it.title.lowercase() }
                    addView(ui.text("$why and holds $held. If you don't remember turning these on, switch them off.", 14f, 500, 0xFFF6E1C6.toInt()),
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                })
            })
            addView(ui.sectionLabel("Powers switched on"))
            addView(ui.card(padV = 4).apply {
                a.powers.forEachIndexed { idx, p ->
                    addView(ui.row(12).apply {
                        setPadding(0, ui.dp(10), 0, ui.dp(10))
                        addView(ui.column(2).apply {
                            addView(ui.text(p.title, 15f, 700))
                            addView(ui.text(p.desc, 13f, 500, Colors.TEXT2))
                        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        addView(ui.outlinePill(if (p == LiveCheck.Power.BATTERY) "Settings" else "Turn off", heightDp = 44) {
                            openSettings(LiveCheck.settingsIntent(p, a.pkg))
                        })
                    })
                    if (idx < a.powers.size - 1) addView(View(this@MainActivity).apply { setBackgroundColor(0xFF232427.toInt()) },
                        LinearLayout.LayoutParams(MATCH_PARENT, 1))
                }
            })
            addView(ui.card(padV = 4).apply {
                fun kv(k: String, v: String, color: Int = Colors.TEXT) = ui.row().apply {
                    minimumHeight = ui.dp(44)
                    addView(ui.text(k, 14f, 500, Colors.TEXT2), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(ui.text(v, 14f, 600, color))
                }
                addView(kv("Installed by", a.installerLabel, if (a.fromStore) Colors.TEXT else Colors.HIGH))
                addView(kv("Installed", DateFormat.getDateFormat(this@MainActivity).format(Date(a.firstInstall))))
                addView(kv("Icon in app drawer", if (a.hiddenIcon) "Hidden" else "Shown", if (a.hiddenIcon) Colors.HIGH else Colors.TEXT))
            })
            addView(ui.pill("Scan this app's file") {
                val f = File(a.sourceDir)
                if (f.canRead()) startScan(Source.Installed(f, a.label)) else toast("Android doesn't allow reading this app's file")
            }, ui.lp(top = 8))
            addView(ui.outlinePill("App settings and uninstall…", fg = Colors.CRITICAL, stroke = Colors.CRITICAL, heightDp = 52) {
                openSettings(LiveCheck.appSettingsIntent(a.pkg))
            })
        }
    }

    private fun openSettings(i: Intent) {
        runCatching { startActivity(i) }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }

    // ---------------------------------------------------------------- History and About

    private fun showHistory() {
        tab = Tab.HISTORY
        val items = History.load(this)
        screen(showNav = true, back = { showHome() }) {
            addView(header("History", null))
            if (items.isEmpty()) {
                addView(ui.card().apply { addView(ui.text("No scans yet. Files you scan show up here.", 15f, 500, Colors.TEXT2)) })
                return@screen
            }
            items.forEach { e ->
                addView(ui.card(radiusDp = 24, padH = 16, padV = 14).apply {
                    contentDescription = "${e.name}, ${verdictLabel(e.verdict)}, score ${e.score}"
                    addView(ui.row(14).apply {
                        addView(ui.text(e.score.toString(), 26f, 800, verdictColor(e.verdict)).apply {
                            gravity = Gravity.CENTER
                            letterSpacing = -0.03f
                        }, LinearLayout.LayoutParams(ui.dp(52), WRAP_CONTENT))
                        addView(ui.column(2).apply {
                            addView(ui.ellipsize(ui.text(e.name, 15f, 700)))
                            addView(ui.ellipsize(ui.text(e.pkg ?: "unknown package", 12f, 500, Colors.TEXT2, mono = true)))
                            addView(ui.text("${verdictLabel(e.verdict)} · ${DateFormat.getDateFormat(this@MainActivity).format(Date(e.time))} " +
                                DateFormat.getTimeFormat(this@MainActivity).format(Date(e.time)), 12f, 500, Colors.TEXT2))
                        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    })
                })
            }
            addView(ui.text("Only summaries are kept. Export a report to save the details.", 12f, 500, Colors.TEXT3).apply { setPadding(ui.dp(4), ui.dp(4), 0, 0) })
            addView(ui.outlinePill("Clear history", heightDp = 48) { History.clear(this@MainActivity); showHistory() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun showAbout() {
        tab = Tab.ABOUT
        screen(showNav = true, back = { showHome() }) {
            addView(header("About", null))
            addView(ui.card(Colors.ACCENT, 32, 22, 22, gap = 6).apply {
                addView(ui.text("APK Guard", 28f, 800, Colors.ON_ACCENT))
                addView(ui.text("Version $appVersion", 14f, 600, 0xFF2E3410.toInt()))
            })
            fun point(title: String, body: String) = ui.card(gap = 4).apply {
                addView(ui.text(title, 15f, 700))
                addView(ui.text(body, 14f, 500, Colors.TEXT2))
            }
            addView(point("No internet permission", "APK Guard can't send anything anywhere. Reports leave the phone only when you share them."))
            addView(point("Never runs the app", "Files are read as bytes in private storage, then deleted. Nothing is installed."))
            addView(point("Static checks have limits", "Code that is packed, encrypted or downloaded later can stay hidden. A low score is not a guarantee."))
            addView(point("Third-party code", "junrar 8.1.2 for RAR files (UnRar license). Manrope font (SIL Open Font License 1.1). Everything else is Android and Kotlin."))
        }
    }

    // ---------------------------------------------------------------- utilities

    private fun post(block: () -> Unit) = main.post { if (!isDestroyed) block() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_SAVE = 2
        private const val WORK_DIR = "scan"
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    }
}
