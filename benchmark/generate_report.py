#!/usr/bin/env python3
"""Generate benchmark comparison report from JMH JSON results."""

import json
import sys
import os
from datetime import datetime

def load_jmh_json(path):
    """Load JMH JSON result file."""
    if not os.path.exists(path):
        print(f"WARNING: {path} not found")
        return []
    with open(path) as f:
        return json.load(f)

def safe_float(val, default=0.0):
    """Safely convert a value to float, handling 'NaN' strings."""
    try:
        f = float(val)
        if f != f:  # NaN check
            return default
        return f
    except (ValueError, TypeError):
        return default

def extract_results(jmh_data, label):
    """Extract benchmark results into a dict keyed by benchmark name."""
    results = {}
    for entry in jmh_data:
        name = entry.get("benchmark", "")
        # Shorten name: com.fastpath.bench.BigDecimalBenchmark.xxx -> xxx
        short = name.split(".")[-1] if "." in name else name
        params = entry.get("params", {})
        param_str = ""
        for k in sorted(params.keys()):
            param_str += f" [{k}={params[k]}]"
        key = f"{short}{param_str}"
        results[key] = {
            "label": label,
            "score": safe_float(entry.get("primaryMetric", {}).get("score", 0)),
            "scoreError": safe_float(entry.get("primaryMetric", {}).get("scoreError", 0)),
            "unit": entry.get("primaryMetric", {}).get("scoreUnit", "ns/op"),
            "rawData": entry.get("primaryMetric", {}).get("rawData", []),
        }
    return results

def generate_report(baseline_path, fastpath_path, merged_path, report_path):
    """Generate comparison report."""
    baseline_data = load_jmh_json(baseline_path)
    fastpath_data = load_jmh_json(fastpath_path)

    baseline = extract_results(baseline_data, "Baseline")
    fastpath = extract_results(fastpath_data, "Fastpath")

    # Merge for JSON output
    merged = {
        "timestamp": datetime.now().isoformat(),
        "baseline": baseline,
        "fastpath": fastpath,
    }
    with open(merged_path, "w") as f:
        json.dump(merged, f, indent=2, ensure_ascii=False)

    # Generate Markdown report
    lines = []
    lines.append("# BigDecimal Fastpath Benchmark Report")
    lines.append("")
    lines.append(f"**Generated:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"**JDK:** {os.popen('java -version 2>&1 | head -1').read().strip()}")
    lines.append(f"**Baseline:** Standard JDK 25 BigDecimal")
    lines.append(f"**Fastpath:** Patched BigDecimal (--patch-module java.base)")
    lines.append("")
    lines.append("## JMH Configuration")
    lines.append("")
    lines.append("| Parameter | Value |")
    lines.append("|-----------|-------|")
    lines.append("| Mode | AverageTime |")
    lines.append("| Time Unit | ns/op |")
    lines.append("| Warmup | 1 iteration × 1s |")
    lines.append("| Measurement | 2 iterations × 1s |")
    lines.append("| Forks | 1 |")
    lines.append("")

    # Comparison table
    lines.append("## Results Comparison")
    lines.append("")

    # Collect all benchmark names
    all_benchmarks = sorted(set(list(baseline.keys()) + list(fastpath.keys())))

    if not all_benchmarks:
        lines.append("No benchmark results found.")
    else:
        lines.append("| Benchmark | Baseline (ns/op) | Fastpath (ns/op) | Speedup | Δ % |")
        lines.append("|-----------|------------------:|------------------:|--------:|----:|")

        for name in all_benchmarks:
            b = baseline.get(name)
            f = fastpath.get(name)

            if b and f:
                b_score = b["score"]
                f_score = f["score"]
                if f_score > 0:
                    speedup = b_score / f_score
                    pct = ((b_score - f_score) / b_score) * 100
                else:
                    speedup = 0
                    pct = 0
                speedup_str = f"{speedup:.2f}x" if speedup >= 1 else f"{1/speedup:.2f}x slower"
                pct_str = f"{pct:+.1f}%" if pct != 0 else "0%"
                lines.append(f"| {name} | {b_score:.2f} ± {b['scoreError']:.2f} | {f_score:.2f} ± {f['scoreError']:.2f} | {speedup_str} | {pct_str} |")
            elif b:
                lines.append(f"| {name} | {b['score']:.2f} ± {b['scoreError']:.2f} | N/A | N/A | N/A |")
            elif f:
                lines.append(f"| {name} | N/A | {f['score']:.2f} ± {f['scoreError']:.2f} | N/A | N/A |")

    lines.append("")

    # Category summaries
    lines.append("## Category Summary")
    lines.append("")

    categories = {
        "Multiply": [n for n in all_benchmarks if "multiply" in n.lower() and "Baseline" not in n],
        "Multiply Baseline (BigInteger)": [n for n in all_benchmarks if "multiply" in n.lower() and "Baseline" in n],
        "Divide": [n for n in all_benchmarks if "divide" in n.lower() and "Baseline" not in n],
        "Divide Baseline (BigInteger)": [n for n in all_benchmarks if "divide" in n.lower() and "Baseline" in n],
        "Parse": [n for n in all_benchmarks if "parse" in n.lower()],
    }

    for cat, benches in categories.items():
        if not benches:
            continue
        lines.append(f"### {cat}")
        lines.append("")
        for name in benches:
            b = baseline.get(name)
            f = fastpath.get(name)
            if b and f:
                speedup = b["score"] / f["score"] if f["score"] > 0 else 0
                pct = ((b["score"] - f["score"]) / b["score"]) * 100 if b["score"] > 0 else 0
                lines.append(f"- **{name}**: {b['score']:.2f} → {f['score']:.2f} ns/op ({speedup:.2f}x, {pct:+.1f}%)")
            elif b:
                lines.append(f"- **{name}**: {b['score']:.2f} ns/op (baseline only)")
            elif f:
                lines.append(f"- **{name}**: {f['score']:.2f} ns/op (fastpath only)")
        lines.append("")

    # Notes
    lines.append("## Notes")
    lines.append("")
    lines.append("- **Baseline**: JDK 25 standard BigDecimal (no fastpath modifications)")
    lines.append("- **Fastpath**: JDK 25 with patched BigDecimal (--patch-module java.base)")
    lines.append("- **BigInteger Baselines**: Use BigInteger directly, unaffected by patch; serve as control")
    lines.append("- Speedup > 1.0x means fastpath is faster")
    lines.append("- Negative Δ% means fastpath is faster (less time)")
    lines.append("")

    report_text = "\n".join(lines)
    with open(report_path, "w") as f:
        f.write(report_text)

    print(f"Report generated: {report_path}")
    print(f"Merged JSON: {merged_path}")

    # Print summary to console
    print("\n=== Summary ===")
    for name in all_benchmarks:
        b = baseline.get(name)
        f = fastpath.get(name)
        if b and f:
            speedup = b["score"] / f["score"] if f["score"] > 0 else 0
            pct = ((b["score"] - f["score"]) / b["score"]) * 100 if b["score"] > 0 else 0
            print(f"  {name}: {b['score']:.2f} → {f['score']:.2f} ns/op ({speedup:.2f}x, {pct:+.1f}%)")

if __name__ == "__main__":
    if len(sys.argv) != 5:
        print(f"Usage: {sys.argv[0]} <baseline.json> <fastpath.json> <merged.json> <report.md>")
        sys.exit(1)
    generate_report(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
