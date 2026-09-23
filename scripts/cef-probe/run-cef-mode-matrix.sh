#!/usr/bin/env bash
# Measures what the Chromium surface costs in each rendering mode and which backend it actually got.
#
# It does NOT start Minecraft: it compiles the org.cef bindings that ship with this mod, runs a
# head-less off screen browser against the same jcef binaries the game uses, and prints, per mode, the
# WebGL renderer string Chromium was given (the hard evidence for "GPU or SwiftShader"), the frame
# rate, the bitmap traffic it handed back and its CPU time.
#
#   group A  the way CefBootstrap used to start Chromium (switches only to startup(), so they are
#            dropped): this is the "what was really happening" baseline
#   group B  switches passed to CefApp.getInstance() plus --disable-gpu*  -> SwiftShader
#   group C  switches passed to CefApp.getInstance() without --disable-gpu -> hardware GPU
#
# Close the Minecraft client first: the cleanup step kills CEF helper processes that belong to the
# caches created below (not the game's own), but a running game would still distort the CPU numbers.
#
# Usage: bash scripts/cef-probe/run-cef-mode-matrix.sh [seconds] [deviceScale]
set -euo pipefail

cd "$(dirname "$0")/../.."
SECONDS_PER_GROUP="${1:-8}"
SCALE="${2:-4}"
NATIVES_REL="${CEF_NATIVES:-run/war_project-cef/windows_amd64}"
OUT="build/cef-probe"

if [ ! -d "$NATIVES_REL" ]; then
  echo "CEF binaries not found in $NATIVES_REL (set CEF_NATIVES to the windows_amd64 directory)" >&2
  exit 1
fi
NATIVES_WIN="$(cd "$NATIVES_REL" && pwd -W 2>/dev/null || pwd)"
ROOT_WIN="$(pwd -W 2>/dev/null || pwd)"

mkdir -p "$OUT/classes"
find src/cefApi/java -name '*.java' | grep -v '/mac/' > "$OUT/sources.txt"
javac -nowarn -d "$OUT/classes" @"$OUT/sources.txt"
javac -nowarn -cp "$OUT/classes" -d "$OUT/classes" scripts/cef-probe/CefGpuProbe.java

kill_group_helpers() {
  local label="$1"
  powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -like '*cache-$label*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
}

run_group() {
  local label="$1" pass="$2" profile="$3"
  rm -rf "$OUT/cache-$label"
  echo "=== $label  (argsToGetInstance=$pass  switches=$profile  scale=$SCALE) ==="
  java -cp "$OUT/classes" CefGpuProbe "$label" "$NATIVES_WIN" "$ROOT_WIN/$OUT/cache-$label" "$pass" "$profile" "$SECONDS_PER_GROUP" "$SCALE" 2>&1 \
    | grep -v 'policy manager' | grep -v 'library path' || true
  kill_group_helpers "$label"
  sleep 2
}

run_group A_switchesDropped_current false current
run_group B_software_passed        true  current
run_group C_hardware_passed        true  gpu
echo "=== matrix done ==="
