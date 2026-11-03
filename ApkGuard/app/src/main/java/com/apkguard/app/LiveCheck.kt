package com.apkguard.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.view.accessibility.AccessibilityManager

/**
 * Reads, from Android's own settings, which installed apps currently hold powers that the
 * user has to switch on by hand. It sees which powers an app holds and where it came from,
 * not what the app does with them. Nothing is changed; buttons only open system settings.
 */
object LiveCheck {

    enum class Power(val title: String, val desc: String, val high: Boolean) {
        ACCESSIBILITY("Accessibility", "Reads the screen and taps for you", true),
        DEVICE_ADMIN("Device admin", "Can lock or wipe the phone, hard to remove", true),
        NOTIFICATIONS("Reads notifications", "Sees every notification, including codes", false),
        OVERLAY("Draws over other apps", "Can cover other apps' screens", false),
        DEFAULT_SMS("Default SMS app", "Receives and can hide your texts", true),
        USAGE("Usage access", "Sees which apps you use and when", false),
        BATTERY("Runs in background", "Battery limits removed", false),
    }

    class App(
        val pkg: String,
        val label: String,
        val installer: String?,
        val installerLabel: String,
        val fromStore: Boolean,
        val powers: List<Power>,
        val hiddenIcon: Boolean,
        val firstInstall: Long,
        val sourceDir: String,
    ) {
        /** Powers that matter enough to list the app. Battery and usage alone are common and mild. */
        val risky: Boolean get() = powers.any { it != Power.BATTERY && it != Power.USAGE }
        val needsLook: Boolean get() = risky && (!fromStore || hiddenIcon)
    }

    class Change(val title: String, val app: String, val time: Long, val bad: Boolean)

    class Result(
        val apps: List<App>,
        val accessibility: Int,
        val admins: Int,
        val notificationReaders: Int,
        val defaultSmsChanged: Boolean,
        val changes: List<Change>,
        val checkedAt: Long,
    ) {
        val needsLook: List<App> get() = apps.filter { it.needsLook }
        val fromStore: List<App> get() = apps.filter { it.risky && !it.needsLook }
    }

    private val STORES = setOf(
        "com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.amazon.venezia",
        "com.xiaomi.market", "com.xiaomi.mipicks", "com.heytap.market", "com.oppo.market", "com.vivo.appstore",
        "com.oneplus.appstore", "org.fdroid.fdroid", "com.aurora.store", "com.google.android.packageinstaller.ps",
    )

    fun run(ctx: Context): Result {
        val pm = ctx.packageManager
        val prefs = ctx.getSharedPreferences("live", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("at", 0L)
        val before = prefs.getStringSet("powers", emptySet()) ?: emptySet()
        val beforeSms = prefs.getString("sms", null)

        val a11y = runCatching {
            ctx.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
        val admins = runCatching {
            ctx.getSystemService(DevicePolicyManager::class.java).activeAdmins?.map { it.packageName }?.toSet()
        }.getOrNull() ?: emptySet()
        // Not a public constant, but readable by apps; falls back to empty if a vendor blocks it.
        val listeners = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.mapNotNull { it.substringBefore('/').takeIf { p -> p.isNotBlank() } }?.toSet()
        }.getOrNull() ?: emptySet()
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
        val power = ctx.getSystemService(PowerManager::class.java)
        val ops = ctx.getSystemService(AppOpsManager::class.java)

        val installed: List<PackageInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }
        }.getOrDefault(emptyList())

        val apps = ArrayList<App>()
        for (p in installed) {
            val ai = p.applicationInfo ?: continue
            if (p.packageName == ctx.packageName) continue
            // Preinstalled apps are trusted by the phone maker; updated system apps too.
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val requested = p.requestedPermissions?.toSet() ?: emptySet()
            val powers = ArrayList<Power>()
            if (p.packageName in a11y) powers += Power.ACCESSIBILITY
            if (p.packageName in admins) powers += Power.DEVICE_ADMIN
            if (p.packageName in listeners) powers += Power.NOTIFICATIONS
            if (p.packageName == sms) powers += Power.DEFAULT_SMS
            if ("android.permission.SYSTEM_ALERT_WINDOW" in requested &&
                opAllowed(ops, pm, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai, "android.permission.SYSTEM_ALERT_WINDOW")) powers += Power.OVERLAY
            if ("android.permission.PACKAGE_USAGE_STATS" in requested &&
                opAllowed(ops, pm, AppOpsManager.OPSTR_GET_USAGE_STATS, ai, null)) powers += Power.USAGE
            if (runCatching { power.isIgnoringBatteryOptimizations(p.packageName) }.getOrDefault(false)) powers += Power.BATTERY
            if (powers.isEmpty() && p.firstInstallTime <= lastCheck) continue

            val installer = installerOf(pm, p.packageName)
            apps += App(
                pkg = p.packageName,
                label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(p.packageName).take(60),
                installer = installer,
                installerLabel = installer?.let { i -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(i, 0)).toString() }.getOrNull() ?: i }
                    ?: "Unknown source",
                fromStore = installer in STORES,
                powers = powers,
                hiddenIcon = pm.getLaunchIntentForPackage(p.packageName) == null,
                firstInstall = p.firstInstallTime,
                sourceDir = ai.sourceDir ?: "",
            )
        }

        val now = System.currentTimeMillis()
        val current = apps.flatMap { a -> a.powers.filter { it != Power.BATTERY }.map { "${a.pkg}|${it.name}" } }.toSet()
        val changes = ArrayList<Change>()
        if (lastCheck > 0) {
            for (key in current - before) {
                val (pkg, pw) = key.split('|', limit = 2)
                val app = apps.firstOrNull { it.pkg == pkg } ?: continue
                val p = runCatching { Power.valueOf(pw) }.getOrNull() ?: continue
                changes += Change("${p.title} turned on", app.label, now, p.high)
            }
            for (a in apps) if (a.firstInstall > lastCheck) {
                changes += Change("New app installed", "${a.label} · from ${a.installerLabel}", a.firstInstall, !a.fromStore)
            }
        }
        prefs.edit().putLong("at", now).putStringSet("powers", current).putString("sms", sms).apply()

        val listed = apps.filter { it.risky }
        return Result(
            apps = listed.sortedWith(compareByDescending<App> { it.needsLook }.thenByDescending { it.powers.count { p -> p.high } }),
            accessibility = apps.count { Power.ACCESSIBILITY in it.powers },
            admins = apps.count { Power.DEVICE_ADMIN in it.powers },
            notificationReaders = apps.count { Power.NOTIFICATIONS in it.powers },
            defaultSmsChanged = beforeSms != null && sms != beforeSms,
            changes = changes.sortedByDescending { it.time },
            checkedAt = now,
        )
    }

    private fun opAllowed(ops: AppOpsManager, pm: PackageManager, op: String, ai: ApplicationInfo, perm: String?): Boolean = runCatching {
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(op, ai.uid, ai.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(op, ai.uid, ai.packageName)
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> perm != null && pm.checkPermission(perm, ai.packageName) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }.getOrDefault(false)

    private fun installerOf(pm: PackageManager, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    }.getOrNull()

    /** The system screen where the user can turn a power off. */
    fun settingsIntent(p: Power, pkg: String): Intent = when (p) {
        Power.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Power.DEVICE_ADMIN -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        Power.NOTIFICATIONS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Power.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
        Power.DEFAULT_SMS -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        Power.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        Power.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun appSettingsIntent(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
}
