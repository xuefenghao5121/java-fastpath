package com.fastpath.bench;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for BigDecimal add/subtract fastpath optimization.
 *
 * Tests cover:
 * 1. Same-scale add (most common financial case)
 * 2. Different-scale add (accumulation with scale alignment)
 * 3. Same-scale subtract
 * 4. Different-scale subtract
 * 5. Batch accumulation (sum loop)
 * 6. Mixed add + subtract
 * 7. Edge cases: zero, negative, overflow boundary
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class AddSubtractBenchmark {

    // ========== Test Data ==========

    // Same-scale values (scale=2, typical currency)
    private final BigDecimal amount100_50 = new BigDecimal("100.50");
    private final BigDecimal amount200_75 = new BigDecimal("200.75");
    private final BigDecimal amount1M_99 = new BigDecimal("1000000.99");
    private final BigDecimal neg50_25 = new BigDecimal("-50.25");

    // Different-scale values
    private final BigDecimal intZero = BigDecimal.ZERO;          // scale 0
    private final BigDecimal scale2val = new BigDecimal("123.45"); // scale 2
    private final BigDecimal scale4val = new BigDecimal("0.1234"); // scale 4
    private final BigDecimal scale0val = new BigDecimal("99999");   // scale 0

    // Tax rate (scale=4, common for percentage)
    private final BigDecimal taxRate = new BigDecimal("0.1300");
    private final BigDecimal bigAmount = new BigDecimal("99999999.99");

    // Overflow boundary values
    private final BigDecimal nearMax = BigDecimal.valueOf(Long.MAX_VALUE - 1, 2);
    private final BigDecimal smallAdd = new BigDecimal("0.01");

    // Accumulation array
    private BigDecimal[] amounts;
    private static final int ARRAY_SIZE = 1000;

    @Setup
    public void setup() {
        amounts = new BigDecimal[ARRAY_SIZE];
        for (int i = 0; i < ARRAY_SIZE; i++) {
            amounts[i] = new BigDecimal((i % 1000 + 1) + "." + (i % 100));
        }
    }

    // ========== Same-Scale Add (HOT PATH) ==========

    @Benchmark
    public BigDecimal addSameScaleSmall() {
        return amount100_50.add(amount200_75);
    }

    @Benchmark
    public BigDecimal addSameScaleLarge() {
        return amount1M_99.add(amount200_75);
    }

    @Benchmark
    public BigDecimal addSameScaleNegative() {
        return amount100_50.add(neg50_25);
    }

    // ========== Different-Scale Add ==========

    @Benchmark
    public BigDecimal addDiffScale_0vs2() {
        return intZero.add(scale2val);
    }

    @Benchmark
    public BigDecimal addDiffScale_2vs4() {
        return scale2val.add(scale4val);
    }

    @Benchmark
    public BigDecimal addDiffScale_0vs4() {
        return intZero.add(scale4val);
    }

    // ========== Subtract ==========

    @Benchmark
    public BigDecimal subtractSameScale() {
        return amount200_75.subtract(amount100_50);
    }

    @Benchmark
    public BigDecimal subtractSameScaleLarge() {
        return amount1M_99.subtract(amount200_75);
    }

    @Benchmark
    public BigDecimal subtractDiffScale() {
        return scale0val.subtract(scale2val);
    }

    @Benchmark
    public BigDecimal subtractNegative() {
        return amount100_50.subtract(neg50_25);
    }

    // ========== Accumulation (most critical for tax calculation) ==========

    @Benchmark
    public BigDecimal accumulateSameScale(Blackhole bh) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < amounts.length; i++) {
            total = total.add(amounts[i]);
        }
        return total;
    }

    @Benchmark
    public BigDecimal accumulateDiffScale(Blackhole bh) {
        // Simulates: total starts at scale 0, amounts are scale 2
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 100; i++) {
            total = total.add(amounts[i]);
        }
        return total;
    }

    // ========== Multiply + Add (typical tax calc: amount * rate + amount) ==========

    @Benchmark
    public BigDecimal multiplyThenAdd() {
        return bigAmount.multiply(taxRate).add(bigAmount);
    }

    // ========== Edge Cases ==========

    @Benchmark
    public BigDecimal addZero() {
        return amount100_50.add(BigDecimal.ZERO);
    }

    @Benchmark
    public BigDecimal subtractSelf() {
        return amount100_50.subtract(amount100_50);
    }

    @Benchmark
    public BigDecimal addNearOverflow() {
        return nearMax.add(smallAdd);
    }

    // ========== With MathContext ==========

    @Benchmark
    public BigDecimal addWithMathContext() {
        return amount100_50.add(amount200_75, new MathContext(10, RoundingMode.HALF_UP));
    }

    @Benchmark
    public BigDecimal subtractWithMathContext() {
        return amount200_75.subtract(amount100_50, new MathContext(10, RoundingMode.HALF_UP));
    }
}
