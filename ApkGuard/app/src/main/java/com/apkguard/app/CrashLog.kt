package com.apkguard.app

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash on the phone so it can be shown on next launch.
 * Nothing is sent anywhere; the user decides whether to copy or share it.
 */
class ApkGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { CrashLog.save(this, error) }
            previous?.uncaughtException(thread, error)
        }
    }
}

object CrashLog {
    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, "last-crash.txt")

    fun save(ctx: Context, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val version = runCatching {
            @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()
        file(ctx).writeText(
            "APK Guard $version on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}\n\n" + sw.toString().take(20_000)
        )
    }

    fun pending(ctx: Context): String? = file(ctx).takeIf { it.isFile }?.readText()

    fun clear(ctx: Context) { file(ctx).delete() }

    /** Plain platform widgets only, so this screen works even if the main UI is what crashed. */
    fun show(activity: Activity, report: String, onContinue: () -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad * 3)
            setBackgroundColor(Color.BLACK)
        }
        col.addView(TextView(activity).apply {
            text = "APK Guard crashed last time"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(activity).apply {
            text = "Copy this report and paste it to the developer. It contains no personal data, only where the app failed."
            setTextColor(0xFFA3AAB4.toInt())
            setPadding(0, pad / 2, 0, pad)
        })
        fun button(label: String, click: () -> Unit) = Button(activity).apply { text = label; setOnClickListener { click() } }
        col.addView(button("Copy report") {
            activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("APK Guard crash", report))
        })
        col.addView(button("Share report") {
            runCatching {
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                }, "Share crash report"))
            }
        })
        col.addView(button("Continue") { clear(activity); onContinue() })
        col.addView(TextView(activity).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFC9CED6.toInt())
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        })
        activity.setContentView(ScrollView(activity).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }
}
