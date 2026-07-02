#!/bin/bash
set -euo pipefail

BENCH_DIR="/tmp/java-fastpath/benchmark"
SRC_DIR="$BENCH_DIR/src"
LIB_DIR="$BENCH_DIR/lib"
BUILD_DIR="$BENCH_DIR/build"
PATCH_DIR="$BUILD_DIR/patches"
RESULTS_DIR="$BENCH_DIR/results"
JMH_VERSION="1.37"
JMH_JAR="$LIB_DIR/jmh-core-${JMH_VERSION}.jar"
JMH_GEN_JAR="$LIB_DIR/jmh-generator-annprocess-${JMH_VERSION}.jar"
JOPTS_JAR="$LIB_DIR/jopt-simple-5.0.4.jar"
COMMONS_MATH_JAR="$LIB_DIR/commons-math3-3.6.1.jar"

BIGDECIMAL_SRC="/tmp/java-fastpath/src/java.base/share/classes/java/math/BigDecimal.java"

# ========== Step 0: Check JDK 25 ==========
echo "=== Checking JDK 25 ==="
JAVA_VERSION=$(java -version 2>&1 | head -1 | awk -F[\".] '{print $2}')
if [ "$JAVA_VERSION" != "25" ]; then
    echo "ERROR: JDK 25 required, found JDK $JAVA_VERSION"
    exit 1
fi
echo "✓ JDK 25 confirmed: $(java -version 2>&1 | head -1)"

# ========== Step 1: Download JMH dependencies ==========
echo ""
echo "=== Downloading JMH $JMH_VERSION dependencies ==="
mkdir -p "$LIB_DIR"

download_jar() {
    local url="$1"
    local dest="$2"
    local name="$3"
    if [ -f "$dest" ]; then
        echo "  ✓ $name (cached)"
    else
        echo "  ↓ Downloading $name..."
        if ! curl -sL -o "$dest" "$url"; then
            echo "  ERROR: Failed to download $name"
            exit 1
        fi
        echo "  ✓ $name downloaded"
    fi
}

Maven_Base="https://repo1.maven.org/maven2"
download_jar "$Maven_Base/org/openjdk/jmh/jmh-core/${JMH_VERSION}/jmh-core-${JMH_VERSION}.jar" "$JMH_JAR" "jmh-core"
download_jar "$Maven_Base/org/openjdk/jmh/jmh-generator-annprocess/${JMH_VERSION}/jmh-generator-annprocess-${JMH_VERSION}.jar" "$JMH_GEN_JAR" "jmh-generator-annprocess"
download_jar "$Maven_Base/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar" "$JOPTS_JAR" "jopt-simple"
download_jar "$Maven_Base/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar" "$COMMONS_MATH_JAR" "commons-math3"

# ========== Step 2: Compile modified BigDecimal.java (for patch) ==========
echo ""
echo "=== Compiling modified BigDecimal.java (patch) ==="
rm -rf "$PATCH_DIR"
mkdir -p "$PATCH_DIR"

# Compile using --patch-module so javac treats it as part of java.base
cd /tmp/java-fastpath/src/java.base/share/classes
if javac \
    --patch-module java.base=. \
    --add-exports java.base/jdk.internal.access=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.math=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.util=ALL-UNNAMED \
    --add-exports java.base/sun.nio.cs=ALL-UNNAMED \
    -d "$PATCH_DIR" \
    java/math/BigDecimal.java 2>&1; then
    echo "✓ Modified BigDecimal compiled to $PATCH_DIR"
    # Verify the class file exists
    if [ -f "$PATCH_DIR/java/math/BigDecimal.class" ]; then
        echo "✓ BigDecimal.class generated ($(du -h "$PATCH_DIR/java/math/BigDecimal.class" | cut -f1))"
    else
        echo "ERROR: BigDecimal.class not found after compilation"
        exit 1
    fi
else
    echo "ERROR: Failed to compile modified BigDecimal.java"
    echo "Attempting fallback: simple javac compilation..."
    if javac \
        --add-exports java.base/jdk.internal.access=ALL-UNNAMED \
        --add-exports java.base/jdk.internal.math=ALL-UNNAMED \
        --add-exports java.base/jdk.internal.util=ALL-UNNAMED \
        -d "$PATCH_DIR" \
        "$BIGDECIMAL_SRC" 2>&1; then
        echo "✓ Fallback compilation succeeded"
    else
        echo "ERROR: Both compilation attempts failed"
        exit 1
    fi
fi

# ========== Step 3: Compile benchmark ==========
echo ""
echo "=== Compiling JMH benchmark ==="
mkdir -p "$BUILD_DIR/classes"

CLASSPATH="$JMH_JAR:$JOPTS_JAR:$COMMONS_MATH_JAR"

# Compile benchmark classes
javac -cp "$CLASSPATH" \
      -d "$BUILD_DIR/classes" \
      $SRC_DIR/com/fastpath/bench/*.java 2>&1

echo "✓ Benchmark classes compiled"

# ========== Step 4: Process JMH annotations ==========
echo ""
echo "=== Processing JMH annotations ==="
# JMH annotation processor generates the benchmark runner
javac -cp "$CLASSPATH:$BUILD_DIR/classes:$JMH_GEN_JAR" \
      -processor org.openjdk.jmh.generators.BenchmarkProcessor \
      -d "$BUILD_DIR/classes" \
      $SRC_DIR/com/fastpath/bench/*.java 2>&1

echo "✓ JMH annotations processed"

# ========== Step 5: Build fat jar ==========
echo ""
echo "=== Building benchmark JAR ==="
FAT_JAR="$BUILD_DIR/benchmark.jar"

# Create temp directory for combining all classes
FAT_TMP="$BUILD_DIR/fat-jar"
rm -rf "$FAT_TMP"
mkdir -p "$FAT_TMP"

# Extract benchmark classes
(cd "$BUILD_DIR/classes" && jar cf /tmp/classes.tar .)
(cd "$FAT_TMP" && jar xf /tmp/classes.tar)
rm -f /tmp/classes.tar

# Extract each dependency JAR
for j in "$LIB_DIR"/*.jar; do
    echo "  Adding $(basename $j)..."
    (cd "$FAT_TMP" && jar xf "$j")
done

# Remove duplicate manifest
rm -f "$FAT_TMP/META-INF/MANIFEST.MF"

# Create manifest
mkdir -p "$BUILD_DIR/meta"
cat > "$BUILD_DIR/meta/MANIFEST.MF" <<EOF
Main-Class: org.openjdk.jmh.Main
Class-Path: .
EOF

# Create the fat JAR
(cd "$FAT_TMP" && jar cfm "$FAT_JAR" "$BUILD_DIR/meta/MANIFEST.MF" .)
rm -rf "$FAT_TMP"

echo "✓ Fat JAR built: $FAT_JAR ($(du -h "$FAT_JAR" | cut -f1))"

# ========== Step 6: Verify ==========
echo ""
echo "=== Verification ==="
echo "Benchmark JAR: $FAT_JAR"
echo "Patch directory: $PATCH_DIR"
echo "Results directory: $RESULTS_DIR"
mkdir -p "$RESULTS_DIR"

# Quick sanity check - list available benchmarks
echo ""
echo "Available benchmarks:"
java -cp "$FAT_JAR" org.openjdk.jmh.Main -l 2>/dev/null || true

echo ""
echo "=== Build complete ==="
