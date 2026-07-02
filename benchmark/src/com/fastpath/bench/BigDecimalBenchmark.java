package com.fastpath.bench;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for BigDecimal Fastpath Optimization (Simplified)
 *
 * Tests core tax-system long-tail calculation scenarios:
 * - Multiply: large amount × tax rate
 * - Divide: 2-digit, 3-digit, 5-digit big divisors
 * - Parse: percentage format strings
 *
 * Two comparison modes:
 * 1. JDK Patched (fastpath): run with --patch-module java.base=<dir>
 * 2. JDK Standard (baseline): run without patch
 *
 * BigInteger-based baselines included for in-run comparison.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@State(Scope.Thread)
public class BigDecimalBenchmark {

    // ========== Test Data: Multiply ==========
    private BigDecimal largeAmount;      // 9999999999.99
    private BigDecimal taxRate13;        // 0.13 (13% VAT)

    private BigInteger largeAmount_bi;   // unscaled value of 9999999999.99
    private BigInteger taxRate13_bi;     // unscaled value of 0.13

    // ========== Test Data: Divide ==========
    private BigDecimal hundred;          // 100
    private BigDecimal divisor_001;      // 0.01
    private BigDecimal divisor_0001;     // 0.001
    private BigDecimal divisor_99999_2;  // 999.99

    // ========== Test Data: Parse ==========
    private String percentageStr;        // "13.0000%"

    @Setup
    public void setup() {
        // Multiply data
        largeAmount = new BigDecimal("9999999999.99");
        taxRate13 = new BigDecimal("0.13");

        largeAmount_bi = BigInteger.valueOf(999999999999L);
        taxRate13_bi = BigInteger.valueOf(13);

        // Divide data
        hundred = new BigDecimal("100");
        divisor_001 = new BigDecimal("0.01");
        divisor_0001 = new BigDecimal("0.001");
        divisor_99999_2 = new BigDecimal("999.99");

        // Parse data
        percentageStr = "13.0000%";
    }

    // ========== Multiply Benchmarks (2) ==========

    /** Large amount × tax rate: 9999999999.99 × 0.13 */
    @Benchmark
    public BigDecimal multiplyLargeTaxRate() {
        return largeAmount.multiply(taxRate13);
    }

    /** Baseline: 9999999999.99 × 0.13 via BigInteger */
    @Benchmark
    public BigDecimal multiplyLargeTaxRateBaseline() {
        BigInteger product = largeAmount_bi.multiply(taxRate13_bi);
        return new BigDecimal(product, 4);
    }

    // ========== Divide Benchmarks (6) ==========

    /** 100 / 0.01 (2-digit divisor) */
    @Benchmark
    public BigDecimal divideBy2Digit() {
        return hundred.divide(divisor_001, 4, RoundingMode.DOWN);
    }

    /** Baseline: 100 / 0.01 via BigInteger */
    @Benchmark
    public BigDecimal divideBy2DigitBaseline() {
        BigInteger dividend = BigInteger.valueOf(100);
        BigInteger divisor = BigInteger.valueOf(1);
        BigInteger scaledDividend = dividend.multiply(BigInteger.TEN.pow(6));
        BigInteger[] result = scaledDividend.divideAndRemainder(divisor);
        return new BigDecimal(result[0], 4);
    }

    /** 100 / 0.001 (3-digit divisor) */
    @Benchmark
    public BigDecimal divideBy3Digit() {
        return hundred.divide(divisor_0001, 6, RoundingMode.DOWN);
    }

    /** Baseline: 100 / 0.001 via BigInteger */
    @Benchmark
    public BigDecimal divideBy3DigitBaseline() {
        BigInteger dividend = BigInteger.valueOf(100);
        BigInteger divisor = BigInteger.valueOf(1);
        BigInteger scaledDividend = dividend.multiply(BigInteger.TEN.pow(9));
        BigInteger[] result = scaledDividend.divideAndRemainder(divisor);
        return new BigDecimal(result[0], 6);
    }

    /** 100 / 999.99 (5-digit big divisor) */
    @Benchmark
    public BigDecimal divideBy5DigitBig() {
        return hundred.divide(divisor_99999_2, 4, RoundingMode.DOWN);
    }

    /** Baseline: 100 / 999.99 via BigInteger */
    @Benchmark
    public BigDecimal divideBy5DigitBigBaseline() {
        // 100 / 999.99
        // dividend unscaled = 100, scale = 0
        // divisor unscaled = 99999, scale = 2
        // scaleDiff = 0 - 2 = -2
        // target scale = 4, raise = 4 - (-2) = 6
        BigInteger dividend = BigInteger.valueOf(100);
        BigInteger divisor = BigInteger.valueOf(99999);
        BigInteger scaledDividend = dividend.multiply(BigInteger.TEN.pow(6));
        BigInteger[] result = scaledDividend.divideAndRemainder(divisor);
        return new BigDecimal(result[0], 4);
    }

    // ========== Parse Benchmarks (2) ==========

    /** Parse percentage format: "13.0000%" */
    @Benchmark
    public BigDecimal parsePercentage() {
        String s = percentageStr;
        if (s.endsWith("%")) {
            s = s.substring(0, s.length() - 1);
        }
        return new BigDecimal(s);
    }

    /** Baseline: parse "13.0000%" via manual BigInteger */
    @Benchmark
    public BigDecimal parsePercentageBaseline() {
        String s = percentageStr;
        if (s.endsWith("%")) {
            s = s.substring(0, s.length() - 1);
        }
        int dot = s.indexOf('.');
        String intPart = dot >= 0 ? s.substring(0, dot) : s;
        String fracPart = dot >= 0 ? s.substring(dot + 1) : "";
        String combined = intPart + fracPart;
        BigInteger unscaled = new BigInteger(combined);
        int scale = fracPart.length();
        return new BigDecimal(unscaled, scale);
    }
}
