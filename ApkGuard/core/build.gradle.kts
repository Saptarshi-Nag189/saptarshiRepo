import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // RAR (v4) extraction. The only third-party library in APK Guard; see README "Dependencies".
    implementation("com.github.junrar:junrar:8.1.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Optional: point at a real APK to run the integration test.
    System.getenv("APKGUARD_SAMPLE_APK")?.let { environment("APKGUARD_SAMPLE_APK", it) }
}
