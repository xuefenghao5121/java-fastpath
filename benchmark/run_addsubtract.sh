#!/bin/bash
# Run add/subtract benchmark: baseline (JDK stock) vs fastpath (patched)
set -e

BENCH_SRC=src
CLASSES=target/classes
JMH_VERSION=1.37
JMH_JAR=target/jmh-core-${JMH_VERSION}.jar
JMH_OPT_JAR=target/jopt-simple-5.0.4.jar
JMH_COMMONS_JAR=target/commons-math3-3.6.1.jar

# Download JMH deps if needed
mkdir -p target
for jar_url in \
    "https://repo1.maven.org/maven2/org/openjdk/jmh/jmh-core/${JMH_VERSION}/jmh-core-${JMH_VERSION}.jar|jmh-core-${JMH_VERSION}.jar" \
    "https://repo1.maven.org/maven2/org/openjdk/jmh/jopt-simple/5.0.4/jopt-simple-5.0.4.jar|jopt-simple-5.0.4.jar" \
    "https://repo1.maven.org/maven2/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar|commons-math3-3.6.1.jar"; do
    url="${jar_url%%|*}"
    fname="${jar_url##*|}"
    if [ ! -f "target/$fname" ]; then
        echo "Downloading $fname..."
        wget -q -O "target/$fname" "$url"
    fi
done

# Compile benchmark
echo "Compiling benchmark..."
mkdir -p $CLASSES
find $BENCH_SRC -name '*.java' > target/sources.txt
javac -cp "target/*" -d $CLASSES @target/sources.txt

CP="$CLASSES:target/*"

JAVA=$(which java)
JAVA25_HOME=""

# Try to find JDK 25
for candidate in /usr/lib/jvm/java-25* /opt/jdk-25* /usr/local/java-25* $JAVA_HOME; do
    if [ -x "$candidate/bin/java" ]; then
        JAVA25_HOME="$candidate"
        break
    fi
done

BIGDECIMAL_PATCHED=/tmp/java-fastpath/bigdecimal2.java

# Extract the package directory structure for the patched class
mkdir -p /tmp/bd-patch/java/math
cp $BIGDECIMAL_PATCHED /tmp/bd-patch/java/math/BigDecimal.java

run_benchmark() {
    local label=$1
    local extra_flags=$2
    
    echo ""
    echo "==============================================="
    echo "  Running: $label"
    echo "==============================================="
    
    $JAVA $extra_flags \
        -cp "$CP" \
        com.fastpath.bench.AddSubtractBenchmark \
        -rf json \
        -rff "target/${label}-results.json" \
        -wi 2 -i 3 -f 1
}

# Run baseline (stock JDK)
run_benchmark "baseline" ""

# Run fastpath (patched BigDecimal)
run_benchmark "fastpath" "-Xpatch:java.base=/tmp/bd-patch"

echo ""
echo "Done! Results in target/baseline-results.json and target/fastpath-results.json"
