#!/usr/bin/env bash
# 在 JVM 上跑 CalcEngine 的单元测试（不需要 Android 设备）
set -euo pipefail
export LANG=C.UTF-8
: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk-arm64}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="${TMPDIR:-/tmp}/calc-engine-test"
rm -rf "$OUT"
mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$OUT" \
  "$ROOT/app/src/main/java/com/mapeople/calculator/CalcEngine.java" \
  "$ROOT/app/src/test/java/com/mapeople/calculator/EngineTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" com.mapeople.calculator.EngineTest
