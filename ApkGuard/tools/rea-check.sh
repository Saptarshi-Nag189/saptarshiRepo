#!/usr/bin/env bash
# Desktop second opinion for an APK using REA (https://github.com/morluto/rea).
# Runs only REA's static readers; the APK is never installed or executed.
#
# Needs: Node.js 22.23.4+ / 24.22.1+ and npm.
# Optional deep analysis (decompiled classes, manifest, references):
#   a full JDK on PATH and REA_JADX_MCP_JAR=/abs/path/jadx-headless-mcp-0.7.1-all.jar
#   (download: https://github.com/1013503897/jadx-headless-mcp/releases/tag/v0.7.1).
#
# Usage: tools/rea-check.sh /path/to/app.apk [output-dir]
set -euo pipefail

apk="${1:?usage: rea-check.sh /path/to/app.apk [output-dir]}"
out="${2:-rea-report}"
[ -f "$apk" ] || { echo "No such file: $apk" >&2; exit 1; }
apk="$(cd "$(dirname "$apk")" && pwd)/$(basename "$apk")"
mkdir -p "$out"

# Pinned version; npx runs it from npm's cache without a global install.
REA=(npx --yes rea-agents@6.8.0)

run() {
  local name="$1"; shift
  echo "== $name"
  if "${REA[@]}" "$@" --format json > "$out/$name.json" 2> "$out/$name.err"; then
    echo "   saved $out/$name.json"
  else
    echo "   failed, see $out/$name.err"
  fi
}

run signature inspect-apk-signature "$apk"
run flutter identify-flutter-build "$apk"

if [ -n "${REA_JADX_MCP_JAR:-}" ]; then
  run package inspect-android-package "$apk"
  # Classes that often matter in malware: SMS, accessibility, device admin, dynamic loading.
  for q in SmsManager AccessibilityService DeviceAdminReceiver DexClassLoader; do
    run "classes-$q" search-android-classes "$apk" "$q"
  done
else
  echo "== deep analysis skipped (set REA_JADX_MCP_JAR to enable)"
fi

echo "Done. Results in $out/"
