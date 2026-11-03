# Changelog

All notable changes to APK Guard. Versions follow [Semantic Versioning](https://semver.org).

## [1.1.0] - 2026-11-03

First published release.

### New
- Live check: lists downloaded apps that hold Accessibility, device admin, notification access,
  draw-over-apps, usage access, default SMS or unrestricted battery. Shows where each app was
  installed from, whether its icon is hidden, and what changed since the last check. Buttons open
  the right system screen to turn a power off.
- Scans the APK inside .zip, .xapk, .apks, .apkm, .rar (v4), .gz, .tar and .tar.gz files,
  including archives inside archives. Split bundles use base.apk.
- Export a report as PDF, web page, JSON or plain text. Save it with the system file picker or share it.
- History of past scans (summaries only).
- New dark interface with a bottom bar, large risk score and action tiles. Edge-to-edge layout that
  keeps buttons clear of the status bar, camera cutout and gesture bar.
- Scan an installed app's own APK from Live check.

### Fixed
- APK Guard no longer flags itself. Code rules now match functions the app actually calls, not
  text that only mentions them.
- Picking the GitHub download ZIP scanned the wrapper instead of the app inside.

### Dependencies
- Added junrar 8.1.2 for RAR files (UnRar license) and the Manrope font (SIL Open Font License 1.1).

## [1.0.0] - 2026-10-18

Initial build. Never published as a release.

### Features
- Offline APK scanner: pick a file, or use "Open with" / "Share" from a file manager or browser.
- Verdict (Dangerous / Suspicious / Low risk) with a 0-100 risk score and an explained finding list.
- Checks permissions, special services (accessibility, device admin, keyboard, notification reader, VPN)
  and dangerous combinations such as overlay + accessibility (banking trojan pattern).
- Scans DEX code for SMS sending, OTP theft, automated taps, hidden code loading, device lock/wipe,
  root commands, Telegram/Discord exfiltration, tunnelling hosts, raw IP endpoints and crypto miners.
- Finds hidden payloads (DEX/APK/ELF disguised as other files) and commercial packers.
- Detects anti-analysis tricks: fake-encrypted ZIP entries, mismatched or duplicate entries,
  malformed manifests, Janus DEX prefix.
- Signature checks: unsigned, v1-only, debug key, public AOSP test key, expired certificate, and
  a different signer than the installed app with the same package name (fake copy).
- Shows the file's SHA-256 for manual lookup.

### Security
- No INTERNET permission and no third-party dependencies.
- APK is never installed or executed; parsed as raw bytes with size and decompression limits.
- Temporary copy lives in private no-backup storage and is deleted after each scan.
