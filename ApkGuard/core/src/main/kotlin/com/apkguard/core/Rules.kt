package com.apkguard.core

import com.apkguard.core.Severity.CRITICAL
import com.apkguard.core.Severity.HIGH
import com.apkguard.core.Severity.LOW
import com.apkguard.core.Severity.MEDIUM

/** Static knowledge base. Everything is local; nothing is downloaded. */
object Rules {

    class PermRule(val severity: Severity, val explain: String, val title: String? = null)

    private const val P = "android.permission."

    val permissions: Map<String, PermRule> = mapOf(
        "${P}SEND_SMS" to PermRule(HIGH, "Can send SMS, including to premium-rate numbers, without you seeing it."),
        "${P}RECEIVE_SMS" to PermRule(HIGH, "Can read incoming SMS, including bank OTP codes."),
        "${P}READ_SMS" to PermRule(HIGH, "Can read all your stored SMS messages."),
        "${P}RECEIVE_MMS" to PermRule(MEDIUM, "Can read incoming MMS messages."),
        "${P}RECEIVE_WAP_PUSH" to PermRule(MEDIUM, "Can intercept WAP push messages."),
        "${P}READ_CALL_LOG" to PermRule(HIGH, "Can read your call history."),
        "${P}WRITE_CALL_LOG" to PermRule(HIGH, "Can edit or erase your call history."),
        "${P}PROCESS_OUTGOING_CALLS" to PermRule(HIGH, "Can see and redirect outgoing calls."),
        "${P}CALL_PHONE" to PermRule(MEDIUM, "Can place phone calls without your confirmation."),
        "${P}ANSWER_PHONE_CALLS" to PermRule(MEDIUM, "Can answer or end phone calls."),
        "${P}SYSTEM_ALERT_WINDOW" to PermRule(HIGH, "Can draw over other apps. Used by trojans to show fake login screens."),
        "${P}REQUEST_INSTALL_PACKAGES" to PermRule(HIGH, "Can ask to install other apps. Typical of droppers."),
        "${P}INSTALL_PACKAGES" to PermRule(MEDIUM, "Requests silent install rights (system-only). Suspicious in a downloaded app."),
        "${P}DELETE_PACKAGES" to PermRule(MEDIUM, "Requests silent uninstall rights (system-only)."),
        "${P}MANAGE_EXTERNAL_STORAGE" to PermRule(HIGH, "Full access to all files on shared storage."),
        "${P}WRITE_SECURE_SETTINGS" to PermRule(MEDIUM, "Requests to change secure system settings (system-only)."),
        "${P}READ_LOGS" to PermRule(MEDIUM, "Requests to read system logs of other apps (system-only)."),
        "${P}MODIFY_PHONE_STATE" to PermRule(MEDIUM, "Requests control over telephony (system-only)."),
        "${P}CAPTURE_AUDIO_OUTPUT" to PermRule(MEDIUM, "Requests recording of device audio output (system-only)."),
        "${P}REBOOT" to PermRule(MEDIUM, "Requests ability to reboot the device (system-only)."),
        "${P}ACCESS_BACKGROUND_LOCATION" to PermRule(HIGH, "Can track your location while the app is closed."),
        "${P}ACCESS_FINE_LOCATION" to PermRule(MEDIUM, "Can read your precise GPS location."),
        "${P}RECORD_AUDIO" to PermRule(MEDIUM, "Can record audio from the microphone."),
        "${P}CAMERA" to PermRule(MEDIUM, "Can take pictures and video."),
        "${P}READ_CONTACTS" to PermRule(MEDIUM, "Can read your contacts."),
        "${P}WRITE_CONTACTS" to PermRule(MEDIUM, "Can modify your contacts."),
        "${P}READ_PHONE_NUMBERS" to PermRule(MEDIUM, "Can read your phone number."),
        "${P}PACKAGE_USAGE_STATS" to PermRule(MEDIUM, "Can see which apps you use and when."),
        "${P}QUERY_ALL_PACKAGES" to PermRule(MEDIUM, "Can list every app installed. Banking trojans use this to pick targets."),
        "${P}WRITE_SETTINGS" to PermRule(MEDIUM, "Can modify system settings."),
        "${P}DISABLE_KEYGUARD" to PermRule(MEDIUM, "Can disable the lock screen."),
        "${P}GET_ACCOUNTS" to PermRule(LOW, "Can list accounts on the device."),
        "${P}READ_PHONE_STATE" to PermRule(LOW, "Can read phone status and identifiers."),
        "${P}RECEIVE_BOOT_COMPLETED" to PermRule(LOW, "Starts automatically when the phone boots."),
        "${P}REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to PermRule(LOW, "Asks to keep running in the background without limits."),
        "${P}USE_FULL_SCREEN_INTENT" to PermRule(LOW, "Can show full-screen pop-ups."),
        "${P}KILL_BACKGROUND_PROCESSES" to PermRule(LOW, "Can kill other apps' background processes."),
        "${P}READ_EXTERNAL_STORAGE" to PermRule(LOW, "Can read shared files and photos."),
        "${P}BODY_SENSORS" to PermRule(LOW, "Can read health sensor data."),
    )

    /** Services that the system binds to with special powers, keyed by their protecting permission. */
    val boundServices: Map<String, PermRule> = mapOf(
        "${P}BIND_ACCESSIBILITY_SERVICE" to PermRule(HIGH, "Accessibility service: can read everything on screen and tap/type for you. The #1 feature abused by Android banking trojans.", "Accessibility service"),
        "${P}BIND_DEVICE_ADMIN" to PermRule(HIGH, "Device administrator: can lock or wipe the phone and resists uninstall.", "Device administrator"),
        "${P}BIND_INPUT_METHOD" to PermRule(HIGH, "Custom keyboard: sees everything you type, including passwords.", "Custom keyboard"),
        "${P}BIND_NOTIFICATION_LISTENER_SERVICE" to PermRule(MEDIUM, "Reads all notifications, including OTP codes and private messages.", "Notification reader"),
        "${P}BIND_VPN_SERVICE" to PermRule(MEDIUM, "VPN service: can see and redirect your network traffic.", "VPN service"),
    )

    val receiverActions: Map<String, PermRule> = mapOf(
        "android.provider.Telephony.SMS_RECEIVED" to PermRule(HIGH, "Intercepts incoming SMS as they arrive."),
        "android.provider.Telephony.SMS_DELIVER" to PermRule(MEDIUM, "Registers as a default SMS app handler."),
        "android.intent.action.NEW_OUTGOING_CALL" to PermRule(MEDIUM, "Watches outgoing calls."),
        "android.intent.action.PHONE_STATE" to PermRule(LOW, "Watches incoming and outgoing call state."),
        "android.app.action.DEVICE_ADMIN_ENABLED" to PermRule(HIGH, "Acts as a device administrator."),
        "android.intent.action.BOOT_COMPLETED" to PermRule(LOW, "Runs automatically after boot."),
        "android.intent.action.PACKAGE_ADDED" to PermRule(LOW, "Watches when other apps are installed."),
    )

    /**
     * DEX rules. Fires when any [anyOf] token is present and all [allOf] tokens are present.
     * Token kinds: "Lpkg/Type;" = a referenced type, "#text" = an exact string literal,
     * anything else = a referenced method name. With [substring], tokens are matched inside
     * string literals instead (URLs, shell commands).
     */
    class CodeRule(
        val id: String,
        val severity: Severity,
        val title: String,
        val detail: String,
        val anyOf: List<String>,
        val allOf: List<String> = emptyList(),
        val substring: Boolean = false,
    )

    /**
     * Substring tokens are stored reversed so this app's own DEX never contains them verbatim;
     * otherwise APK Guard would flag itself when scanning its own APK.
     */
    private fun hidden(vararg reversed: String): List<String> = reversed.map { it.reversed() }

    val code: List<CodeRule> = listOf(
        CodeRule("sms_send", HIGH, "Code sends SMS messages", "Uses SmsManager send APIs. Common in premium-SMS fraud and OTP theft.",
            listOf("sendTextMessage", "sendMultipartTextMessage", "sendDataMessage"), listOf("Landroid/telephony/SmsManager;")),
        CodeRule("sms_read_incoming", HIGH, "Code parses incoming SMS", "Decodes raw SMS PDUs, typically to steal OTP codes.",
            listOf("createFromPdu"), listOf("Landroid/telephony/SmsMessage;")),
        CodeRule("sms_db", HIGH, "Code reads the SMS database", "Reads the phone's SMS store directly.", hidden("sms//:tnetnoc"), substring = true),
        CodeRule("call_log_db", MEDIUM, "Code reads the call log", "Reads the phone's call history store directly.", hidden("gol_llac//:tnetnoc"), substring = true),
        CodeRule("abort_broadcast", MEDIUM, "Code can suppress broadcasts", "abortBroadcast can hide incoming SMS from the user on older Android versions.",
            listOf("abortBroadcast")),
        CodeRule("a11y_gesture", HIGH, "Code performs automated taps via Accessibility", "dispatchGesture lets an app click buttons on your behalf (e.g. approve transfers).",
            listOf("dispatchGesture")),
        CodeRule("a11y_global", MEDIUM, "Code triggers global system actions", "performGlobalAction can press Back/Home/open notifications to block you from removing it.",
            listOf("performGlobalAction")),
        CodeRule("a11y_read", MEDIUM, "Code reads other apps' screen content", "getRootInActiveWindow reads the UI tree of whatever app is open.",
            listOf("getRootInActiveWindow")),
        CodeRule("dex_inmemory", HIGH, "Loads hidden code from memory", "InMemoryDexClassLoader runs code that is not in the APK, a common way to hide a malicious payload.",
            listOf("Ldalvik/system/InMemoryDexClassLoader;")),
        CodeRule("dex_dynamic", MEDIUM, "Loads code at runtime", "DexClassLoader can run code downloaded after install, bypassing review.",
            listOf("Ldalvik/system/DexClassLoader;")),
        CodeRule("device_ids", MEDIUM, "Reads hardware/SIM identifiers", "Collects IMEI, IMSI, SIM serial or phone number.",
            listOf("getDeviceId", "getImei", "getSubscriberId", "getSimSerialNumber", "getLine1Number"), listOf("Landroid/telephony/TelephonyManager;")),
        CodeRule("admin_lock", HIGH, "Can lock the device remotely", "DevicePolicyManager.lockNow is a common ransomware behaviour.",
            listOf("lockNow"), listOf("Landroid/app/admin/DevicePolicyManager;")),
        CodeRule("admin_wipe", HIGH, "Can factory-reset the device", "DevicePolicyManager.wipeData erases the phone.",
            listOf("wipeData"), listOf("Landroid/app/admin/DevicePolicyManager;")),
        CodeRule("admin_password", HIGH, "Can change the lock-screen password", "resetPassword is used by ransomware to lock you out.",
            listOf("resetPassword", "resetPasswordWithToken"), listOf("Landroid/app/admin/DevicePolicyManager;")),
        CodeRule("screen_capture", MEDIUM, "Can record the screen", "Uses MediaProjection to capture what is shown on screen.",
            listOf("Landroid/media/projection/MediaProjectionManager;")),
        CodeRule("hide_icon", LOW, "Can hide or disable its own components", "setComponentEnabledSetting is often used to hide the launcher icon after install.",
            listOf("setComponentEnabledSetting")),
        CodeRule("installer", MEDIUM, "Code installs other packages", "Uses PackageInstaller sessions to install apps.",
            listOf("Landroid/content/pm/PackageInstaller\$Session;")),
        CodeRule("app_list", LOW, "Enumerates installed apps", "Lists installed apps, used to find banking targets or for fingerprinting.",
            listOf("getInstalledPackages", "getInstalledApplications")),
        CodeRule("root_exec", HIGH, "Runs commands as root", "Contains shell commands that run through the su binary.",
            hidden("c- us", "c- us/nibx/metsys/", "c- us/nib/metsys/"), substring = true),
        CodeRule("root_su_literal", MEDIUM, "May execute the 'su' binary", "Has a bare \"su\" string together with Runtime.exec. Root apps and some root checks do this.",
            listOf("#su"), listOf("Ljava/lang/Runtime;", "exec")),
        CodeRule("root_paths", LOW, "References root binaries", "Mentions su paths. Often root detection, sometimes root exploitation.",
            hidden("us/nibx/metsys/", "us/nib/metsys/", "us/nibs/"), substring = true),
        CodeRule("shell_pm", HIGH, "Runs package-manager shell commands", "Contains package-manager shell commands that install, remove or hide apps without asking.",
            hidden("llatsni mp", "llatsninu mp", "elbasid mp", "edih mp"), substring = true),
        CodeRule("shell_chmod", MEDIUM, "Changes file permissions via shell", "Makes files executable from the shell, which is how dropped payloads get started.",
            hidden("777 domhc", "atad/ 557 domhc"), substring = true),
        CodeRule("c2_telegram", HIGH, "Sends data to a Telegram bot", "Telegram bot API is a popular exfiltration channel for stealers.",
            hidden("tob/gro.margelet.ipa"), substring = true),
        CodeRule("c2_discord", HIGH, "Sends data to a Discord webhook", "Discord webhooks are a popular exfiltration channel for stealers.",
            hidden("skoohbew/ipa/moc.drocsid", "skoohbew/ipa/moc.ppadrocsid"), substring = true),
        CodeRule("c2_tunnel", MEDIUM, "Uses tunnelling or dynamic-DNS hosts", "ngrok/duckdns/no-ip style hosts are common for throwaway command servers.",
            hidden("oi.korgn", "ppa.eerf-korgn", "gro.sndkcud", ".pi-on.", "moc.eralfduolcyrt", "ten.oevres"), substring = true),
        CodeRule("c2_paste", MEDIUM, "Fetches content from paste sites", "Raw paste URLs are used to hide command-server addresses.",
            hidden("war/moc.nibetsap", "/r/ee.etsap", "/oc.yrtner"), substring = true),
        CodeRule("miner", HIGH, "Contains crypto-mining indicators", "Mining pool protocols or known miner names were found.",
            hidden("//:pct+mutarts", "//:lss+mutarts", "girmx", "evihnioc"), substring = true),
    )

    /** Native libraries shipped by commercial packers that encrypt the real code. */
    val packerLibs: Map<String, String> = mapOf(
        "libjiagu.so" to "Qihoo 360 Jiagu", "libjiagu_a64.so" to "Qihoo 360 Jiagu", "libjiagu_x86.so" to "Qihoo 360 Jiagu",
        "libsecexe.so" to "Bangcle", "libsecmain.so" to "Bangcle", "libDexHelper.so" to "SecNeo/Bangcle",
        "libexec.so" to "Ijiami", "libexecmain.so" to "Ijiami", "libshella.so" to "Tencent Legu",
        "libshellx.so" to "Tencent Legu", "libtup.so" to "Tencent Legu", "libbaiduprotect.so" to "Baidu",
        "libmobisec.so" to "Alibaba", "libnesec.so" to "NetEase", "libprotectClass.so" to "Unknown packer",
        "libapkprotect.so" to "APKProtect", "libdexprotector.so" to "DexProtector",
    )

    val frameworks: Map<String, String> = mapOf(
        "libflutter.so" to "Flutter", "libreactnativejni.so" to "React Native", "libhermes.so" to "React Native",
        "libmonodroid.so" to "Xamarin/.NET", "libunity.so" to "Unity", "libil2cpp.so" to "Unity IL2CPP",
        "libpython3.so" to "Python", "libgojni.so" to "Go mobile",
    )
}
