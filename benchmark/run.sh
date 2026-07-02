#!/bin/bash
set -euo pipefail

BENCH_DIR="/tmp/java-fastpath/benchmark"
BUILD_DIR="$BENCH_DIR/build"
PATCH_DIR="$BUILD_DIR/patches"
RESULTS_DIR="$BENCH_DIR/results"
FAT_JAR="$BUILD_DIR/benchmark.jar"
JMH_OPTS="-wi 1 -i 2 -f 1 -rf json -bm avgt -tu ns"

# JMH JSON results
BASELINE_JSON="$RESULTS_DIR/baseline-results.json"
FASTPATH_JSON="$RESULTS_DIR/fastpath-results.json"
MERGED_JSON="$RESULTS_DIR/benchmark-results.json"

mkdir -p "$RESULTS_DIR"

# ========== Check build ==========
if [ ! -f "$FAT_JAR" ]; then
    echo "Building benchmark first..."
    bash "$BENCH_DIR/build.sh"
fi

if [ ! -d "$PATCH_DIR/java/math" ] || [ ! -f "$PATCH_DIR/java/math/BigDecimal.class" ]; then
    echo "ERROR: Patch not built. Run build.sh first."
    exit 1
fi

PATCH_PATH="$PATCH_DIR"
echo "Patch path: $PATCH_PATH"
echo "Patch contents:"
ls -la "$PATCH_PATH/java/math/"

# ========== Run 1: Baseline (standard JDK 25 BigDecimal) ==========
echo ""
echo "========================================================"
echo "  RUN 1: BASELINE (Standard JDK 25 BigDecimal)"
echo "========================================================"
java -cp "$FAT_JAR" org.openjdk.jmh.Main \
    $JMH_OPTS \
    -rff "$BASELINE_JSON" \
    2>&1 | tee "$RESULTS_DIR/baseline-console.log"

echo ""
echo "✓ Baseline run complete: $BASELINE_JSON"

# ========== Run 2: Fastpath (patched BigDecimal) ==========
echo ""
echo "========================================================"
echo "  RUN 2: FASTPATH (Patched BigDecimal)"
echo "========================================================"
java -cp "$FAT_JAR" org.openjdk.jmh.Main \
    $JMH_OPTS \
    -jvmArgsAppend "--patch-module java.base=$PATCH_PATH" \
    -rff "$FASTPATH_JSON" \
    2>&1 | tee "$RESULTS_DIR/fastpath-console.log"

echo ""
echo "✓ Fastpath run complete: $FASTPATH_JSON"

# ========== Merge results ==========
echo ""
echo "========================================================"
echo "  Merging results..."
echo "========================================================"

python3 "$BENCH_DIR/generate_report.py" \
    "$BASELINE_JSON" \
    "$FASTPATH_JSON" \
    "$MERGED_JSON" \
    "$RESULTS_DIR/BENCHMARK_REPORT.md"

echo ""
echo "=== Results ==="
echo "  Baseline JSON: $BASELINE_JSON"
echo "  Fastpath JSON: $FASTPATH_JSON"
echo "  Merged JSON:   $MERGED_JSON"
echo "  Report:        $RESULTS_DIR/BENCHMARK_REPORT.md"
echo ""
echo "=== Done ==="
