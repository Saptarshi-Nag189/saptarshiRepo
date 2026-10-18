package com.apkguard.core

/** The parts of AndroidManifest.xml that matter for risk assessment. */
class Manifest(
    val packageName: String?,
    val versionName: String?,
    val versionCode: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val declaredPermissions: List<String>,
    val debuggable: Boolean,
    val allowBackup: Boolean?,
    val usesCleartextTraffic: Boolean?,
    val testOnly: Boolean,
    val sharedUserId: String?,
    val components: List<Component>,
    val anomalies: List<String>,
) {
    class Component(
        val kind: String,
        val name: String,
        val exportedAttr: Boolean?,
        val permission: String?,
        val actions: List<String>,
        val categories: List<String>,
    ) {
        fun isExported(targetSdk: Int?): Boolean =
            exportedAttr ?: (actions.isNotEmpty() && (targetSdk ?: 1) < 31)
    }

    val hasLauncher: Boolean
        get() = components.any { it.kind in ACTIVITY_KINDS && ACTION_MAIN in it.actions && CATEGORY_LAUNCHER in it.categories }

    companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        val ACTIVITY_KINDS = setOf("activity", "activity-alias")
        private val COMPONENT_KINDS = setOf("activity", "activity-alias", "service", "receiver", "provider")

        private const val R_NAME = 0x01010003
        private const val R_PERMISSION = 0x01010006
        private const val R_DEBUGGABLE = 0x0101000f
        private const val R_EXPORTED = 0x01010010
        private const val R_SHARED_USER_ID = 0x0101000b
        private const val R_MIN_SDK = 0x0101020c
        private const val R_VERSION_CODE = 0x0101021b
        private const val R_VERSION_NAME = 0x0101021c
        private const val R_TARGET_SDK = 0x01010270
        private const val R_TEST_ONLY = 0x01010272
        private const val R_ALLOW_BACKUP = 0x01010280
        private const val R_CLEARTEXT = 0x010104ec

        fun from(xml: BinaryXml): Manifest {
            var pkg: String? = null
            var vName: String? = null
            var vCode: String? = null
            var minSdk: Int? = null
            var targetSdk: Int? = null
            var sharedUid: String? = null
            var debuggable = false
            var allowBackup: Boolean? = null
            var cleartext: Boolean? = null
            var testOnly = false
            val perms = LinkedHashSet<String>()
            val declared = LinkedHashSet<String>()
            val components = ArrayList<Component>()

            var current: BinaryXml.Element? = null
            var actions = ArrayList<String>()
            var categories = ArrayList<String>()

            for (ev in xml.events) {
                when (ev) {
                    is BinaryXml.Event.Start -> {
                        val e = ev.element
                        when (e.name) {
                            "manifest" -> {
                                pkg = e.attr("package")?.asString()
                                vName = e.attr("versionName", R_VERSION_NAME)?.asString()
                                vCode = e.attr("versionCode", R_VERSION_CODE)?.asString()
                                sharedUid = e.attr("sharedUserId", R_SHARED_USER_ID)?.asString()
                            }
                            "uses-sdk" -> {
                                minSdk = e.attr("minSdkVersion", R_MIN_SDK)?.asInt()
                                targetSdk = e.attr("targetSdkVersion", R_TARGET_SDK)?.asInt()
                            }
                            "uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m" ->
                                e.attr("name", R_NAME)?.asString()?.let { perms.add(it) }
                            "permission" -> e.attr("name", R_NAME)?.asString()?.let { declared.add(it) }
                            "application" -> {
                                debuggable = e.attr("debuggable", R_DEBUGGABLE)?.asBoolean() == true
                                allowBackup = e.attr("allowBackup", R_ALLOW_BACKUP)?.asBoolean()
                                cleartext = e.attr("usesCleartextTraffic", R_CLEARTEXT)?.asBoolean()
                                testOnly = e.attr("testOnly", R_TEST_ONLY)?.asBoolean() == true
                            }
                            "action" -> if (current != null) e.attr("name", R_NAME)?.asString()?.let { actions.add(it) }
                            "category" -> if (current != null) e.attr("name", R_NAME)?.asString()?.let { categories.add(it) }
                            in COMPONENT_KINDS -> {
                                current = e
                                actions = ArrayList()
                                categories = ArrayList()
                            }
                        }
                    }
                    is BinaryXml.Event.End -> {
                        val c = current
                        if (c != null && ev.depth == c.depth) {
                            components.add(
                                Component(
                                    kind = c.name,
                                    name = c.attr("name", R_NAME)?.asString() ?: "(unnamed)",
                                    exportedAttr = c.attr("exported", R_EXPORTED)?.asBoolean(),
                                    permission = c.attr("permission", R_PERMISSION)?.asString(),
                                    actions = actions,
                                    categories = categories,
                                )
                            )
                            current = null
                        }
                    }
                }
            }
            return Manifest(
                pkg, vName, vCode, minSdk, targetSdk, perms.toList(), declared.toList(),
                debuggable, allowBackup, cleartext, testOnly, sharedUid, components, xml.anomalies,
            )
        }
    }
}
