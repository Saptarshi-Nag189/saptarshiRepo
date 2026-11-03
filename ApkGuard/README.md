# APK Guard

Offline Android app that scans a downloaded APK and flags dangerous behaviour
before you install it. Everything runs on the phone. Nothing is uploaded.

## What it checks

| Area | Examples |
|---|---|
| Permissions | SMS read/send, call log, overlay, install packages, background location, all-files access |
| Special components | Accessibility services, device admin, custom keyboards, notification readers, VPN |
| Dangerous combos | Overlay + accessibility (banking trojan), accessibility + SMS, hidden icon + boot persistence, dropper pattern |
| Code (DEX strings) | SMS sending, OTP parsing, automated taps, in-memory code loading, lock/wipe device, root shell, `pm install` |
| Exfiltration | Telegram bot API, Discord webhooks, ngrok/duckdns hosts, paste sites, raw IP URLs, crypto miners |
| Hidden payloads | DEX/APK/ELF disguised as images, extra DEX in assets, native binaries outside `lib/` |
| Anti-analysis | Packers (Jiagu, Bangcle, Legu...), fake-encrypted ZIP entries, mismatched ZIP headers, duplicate entries, malformed manifest, Janus DEX prefix |
| Signature | Unsigned, v1-only, debug key, public AOSP test key, expired cert, **different signer than the installed app with the same package (fake copy)** |
| Manifest flags | Debuggable, testOnly, system `sharedUserId`, cleartext traffic, old targetSdk, exported components |

It also shows the SHA-256 of the file so you can look it up yourself.

**Archives:** if you pick a .zip, .xapk, .apks, .apkm, .rar (v4), .gz, .tar or .tar.gz, APK Guard
finds the APK inside and scans that. RAR5 files are detected and you're asked to extract first.

**Live check:** lists downloaded apps that currently hold Accessibility, device admin, notification
access, draw-over-apps, usage access, default SMS or unrestricted battery, where each came from,
and what changed since the last check. It reads Android's settings; it can't see inside an app.

**Export:** save or share a report as PDF, HTML, JSON or plain text. HTML reports carry a strict
Content-Security-Policy and escape everything taken from the scanned APK.

## Security design

- **No INTERNET permission.** Stripped in the manifest; CI fails the build if it ever appears.
- **One third-party library.** junrar 8.1.2 reads RAR files; everything else is Kotlin and the
  Android framework. junrar never gets a file path from the archive: extracted data goes to a
  random temp name with a size cap. Note junrar's UnRar license restricts using its code to build
  a RAR compressor.
- **APK is never installed, loaded or executed.** It is parsed as raw bytes by a custom,
  bounds-checked ZIP / binary-XML / DEX reader. Android's `PackageManager` is used only to verify
  the signature.
- **Hostile-input hardening.** Size caps on every read, capped decompression (zip bombs),
  entry-count limits, fuzz tests for malformed manifests and archives.
- **Private, temporary storage.** The file is copied to app-private `noBackupFilesDir`,
  scanned, then deleted. Leftovers are wiped on next launch.
- **No backups.** `allowBackup=false` plus data-extraction rules excluding everything.
- **Only `content://` input.** `file://` URIs are rejected.
- Release builds are minified with R8 and strip logging.

`QUERY_ALL_PACKAGES` is used to compare a scanned APK's signer with an installed app of the same
name (spots fake copies) and to list apps for Live check. With no network access, that list cannot
leave the phone.

## Use

1. Install APK Guard.
2. Tap **Choose APK**, or in your file manager / browser downloads use **Open with** or **Share** -> APK Guard.
3. Read the verdict: **Dangerous**, **Suspicious** or **Low risk**, with each finding explained.

Static analysis has limits. Packed, encrypted or downloaded-later code can hide behaviour.
A "Low risk" result is not a guarantee.

## Build

Requires JDK 17 and the Android SDK (API 35).

```sh
cd ApkGuard
./gradlew :core:test            # scanner tests (no Android SDK needed)
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/
```

Optional real-APK test: `APKGUARD_SAMPLE_APK=/path/app.apk ./gradlew :core:test`.

### Releases

Download the latest signed APK from the repo's **Releases** page. Each release lists the
APK's SHA-256 and the signing certificate fingerprint so you can verify it.

To publish a new release:

1. Bump `ApkGuard/VERSION` (e.g. `1.2.0`) and add a `## [1.2.0]` section to `CHANGELOG.md`.
2. Commit and push. The **APK Guard Release** workflow sees the new version, runs the tests,
   builds and verifies the APK, creates the `v1.2.0` tag and publishes the release.
   A version that is already released is skipped. Pushing a `vX.Y.Z` tag by hand also works.

`versionCode` is derived as `MAJOR*10000 + MINOR*100 + PATCH`, so it always increases.

### Get the APK from GitHub Actions

Every push builds the app. Open **Actions -> APK Guard -> latest run -> Artifacts -> apkguard-release**.

Without signing secrets the release APK is signed with a throwaway debug key, so each new
build must be uninstalled before installing. For stable updates, create a key once:

```sh
keytool -genkeypair -v -keystore release.jks -alias apkguard -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks
```

and add repo secrets `APKGUARD_KEYSTORE_BASE64`, `APKGUARD_KEYSTORE_PASSWORD`,
`APKGUARD_KEY_ALIAS`, `APKGUARD_KEY_PASSWORD`. Never commit the keystore.

## Layout

- `core/` pure Kotlin scanner (ZIP, binary XML, DEX, signing block, rules, scoring). JVM-testable.
- `app/` Android UI, Live check, report export and the PackageManager signature check.
