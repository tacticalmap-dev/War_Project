#!/usr/bin/env bash
# Verifies the damage tracking behind the incremental Chromium uploads (CefPaintRegions).
#
# CefPaintRegions has no dependencies, so this compiles just that file plus the checker and runs it;
# no Minecraft, no Gradle and no CEF binaries are involved. The key property it proves is coverage:
# every rectangle reported as damaged is still covered after merging and clipping, otherwise an
# incremental upload would leave stale pixels on screen.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="build/cef-regions-check"
SOURCE="src/main/java/com/flowingsun/war_project/client/cef/CefPaintRegions.java"

rm -rf "$OUT"
mkdir -p "$OUT"

javac -d "$OUT" "$SOURCE" scripts/CefPaintRegionsCheck.java
java -cp "$OUT" com.flowingsun.war_project.client.cef.CefPaintRegionsCheck
