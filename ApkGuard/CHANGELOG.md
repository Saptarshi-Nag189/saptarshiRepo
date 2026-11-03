# Changelog

All notable changes to APK Guard. Versions follow [Semantic Versioning](https://semver.org).

## [1.0.0] - 2026-11-03

First public release.

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
