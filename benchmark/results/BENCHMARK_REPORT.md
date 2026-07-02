# BigDecimal Fastpath Benchmark Report

**Generated:** 2026-07-02 20:12:32
**JDK:** openjdk version "25.0.3" 2026-04-21
**Baseline:** Standard JDK 25 BigDecimal
**Fastpath:** Patched BigDecimal (--patch-module java.base)

## JMH Configuration

| Parameter | Value |
|-----------|-------|
| Mode | AverageTime |
| Time Unit | ns/op |
| Warmup | 1 iteration × 1s |
| Measurement | 2 iterations × 1s |
| Forks | 1 |

## Results Comparison

| Benchmark | Baseline (ns/op) | Fastpath (ns/op) | Speedup | Δ % |
|-----------|------------------:|------------------:|--------:|----:|
| divideBy2Digit | 4.64 ± 0.00 | 5.00 ± 0.00 | 1.08x slower | -7.7% |
| divideBy2DigitBaseline | 17.79 ± 0.00 | 17.03 ± 0.00 | 1.05x | +4.3% |
| divideBy3Digit | 4.72 ± 0.00 | 5.12 ± 0.00 | 1.09x slower | -8.5% |
| divideBy3DigitBaseline | 18.84 ± 0.00 | 18.60 ± 0.00 | 1.01x | +1.3% |
| divideBy5DigitBig | 4.67 ± 0.00 | 5.06 ± 0.00 | 1.08x slower | -8.3% |
| divideBy5DigitBigBaseline | 16.82 ± 0.00 | 16.89 ± 0.00 | 1.00x slower | -0.4% |
| multiplyLargeTaxRate | 4.23 ± 0.00 | 3.74 ± 0.00 | 1.13x | +11.6% |
| multiplyLargeTaxRateBaseline | 13.53 ± 0.00 | 13.58 ± 0.00 | 1.00x slower | -0.4% |
| parsePercentage | 20.69 ± 0.00 | 20.92 ± 0.00 | 1.01x slower | -1.1% |
| parsePercentageBaseline | 45.04 ± 0.00 | 45.39 ± 0.00 | 1.01x slower | -0.8% |

## Category Summary

### Multiply

- **multiplyLargeTaxRate**: 4.23 → 3.74 ns/op (1.13x, +11.6%)

### Multiply Baseline (BigInteger)

- **multiplyLargeTaxRateBaseline**: 13.53 → 13.58 ns/op (1.00x, -0.4%)

### Divide

- **divideBy2Digit**: 4.64 → 5.00 ns/op (0.93x, -7.7%)
- **divideBy3Digit**: 4.72 → 5.12 ns/op (0.92x, -8.5%)
- **divideBy5DigitBig**: 4.67 → 5.06 ns/op (0.92x, -8.3%)

### Divide Baseline (BigInteger)

- **divideBy2DigitBaseline**: 17.79 → 17.03 ns/op (1.05x, +4.3%)
- **divideBy3DigitBaseline**: 18.84 → 18.60 ns/op (1.01x, +1.3%)
- **divideBy5DigitBigBaseline**: 16.82 → 16.89 ns/op (1.00x, -0.4%)

### Parse

- **parsePercentage**: 20.69 → 20.92 ns/op (0.99x, -1.1%)
- **parsePercentageBaseline**: 45.04 → 45.39 ns/op (0.99x, -0.8%)

## Notes

- **Baseline**: JDK 25 standard BigDecimal (no fastpath modifications)
- **Fastpath**: JDK 25 with patched BigDecimal (--patch-module java.base)
- **BigInteger Baselines**: Use BigInteger directly, unaffected by patch; serve as control
- Speedup > 1.0x means fastpath is faster
- Negative Δ% means fastpath is faster (less time)
