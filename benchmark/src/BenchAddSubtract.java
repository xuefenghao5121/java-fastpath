import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Manual microbenchmark for BigDecimal add/subtract fastpath.
 * Uses proper warmup + iteration methodology.
 */
public class BenchAddSubtract {
    static final int WARMUP_ITERS = 50_000;
    static final int MEASURE_ITERS = 200_000;
    static final int BATCH_SIZE = 1000;

    public static void main(String[] args) {
        System.out.println("BigDecimal Add/Subtract Microbenchmark");
        System.out.println("Warmup: " + WARMUP_ITERS + " iters | Measure: " + MEASURE_ITERS + " iters");
        System.out.println("================================================================");

        // Test data
        BigDecimal a100_50 = new BigDecimal("100.50");
        BigDecimal a200_75 = new BigDecimal("200.75");
        BigDecimal a1M_99 = new BigDecimal("1000000.99");
        BigDecimal neg50_25 = new BigDecimal("-50.25");
        BigDecimal scale2val = new BigDecimal("123.45");
        BigDecimal scale4val = new BigDecimal("0.1234");
        BigDecimal scale0val = new BigDecimal("99999");
        BigDecimal bigAmount = new BigDecimal("99999999.99");
        BigDecimal taxRate = new BigDecimal("0.1300");

        BigDecimal[] amounts = new BigDecimal[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            amounts[i] = new BigDecimal((i % 1000 + 1) + "." + (i % 100));
        }

        // ========== WARMUP ==========
        System.out.print("Warming up... ");
        BigDecimal sink = BigDecimal.ZERO;
        for (int i = 0; i < WARMUP_ITERS; i++) {
            sink = a100_50.add(a200_75);
            sink = a200_75.subtract(a100_50);
            sink = BigDecimal.ZERO.add(scale2val);
            sink = a100_50.add(neg50_25);
        }
        System.out.println("done (" + sink + ")");

        // ========== MEASURE ==========
        System.out.println();
        System.out.printf("%-45s %12s %12s%n", "Benchmark", "Avg (ns/op)", "Result");
        System.out.println("-".repeat(72));

        // 1. Same-scale add (small values)
        bench("add same-scale (100.50 + 200.75)", () -> a100_50.add(a200_75));

        // 2. Same-scale add (large values)
        bench("add same-scale (1M.99 + 200.75)", () -> a1M_99.add(a200_75));

        // 3. Same-scale add with negative
        bench("add same-scale (100.50 + -50.25)", () -> a100_50.add(neg50_25));

        // 4. Different-scale add (0 vs 2)
        bench("add diff-scale (0 + 123.45)", () -> BigDecimal.ZERO.add(scale2val));

        // 5. Different-scale add (2 vs 4)
        bench("add diff-scale (123.45 + 0.1234)", () -> scale2val.add(scale4val));

        // 6. Same-scale subtract
        bench("sub same-scale (200.75 - 100.50)", () -> a200_75.subtract(a100_50));

        // 7. Same-scale subtract (negative result)
        bench("sub same-scale (100.50 - 200.75)", () -> a100_50.subtract(a200_75));

        // 8. Same-scale subtract (with negative)
        bench("sub same-scale (100.50 - (-50.25))", () -> a100_50.subtract(neg50_25));

        // 9. Different-scale subtract
        bench("sub diff-scale (99999 - 123.45)", () -> scale0val.subtract(scale2val));

        // 10. Accumulation (1000 values)
        bench("accumulate 1000 values", () -> {
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < BATCH_SIZE; i++) {
                total = total.add(amounts[i]);
            }
            return total;
        });

        // 11. Multiply + Add (tax calc pattern)
        bench("multiply+add (amount*rate + amount)", () -> bigAmount.multiply(taxRate).add(bigAmount));

        // 12. Add zero
        bench("add zero (123.45 + 0)", () -> scale2val.add(BigDecimal.ZERO));

        // 13. Subtract self
        bench("sub self (123.45 - 123.45)", () -> scale2val.subtract(scale2val));

        // 14. Multiple chained ops
        bench("a-b+c-d chain", () -> 
            a200_75.subtract(a100_50).add(neg50_25).subtract(a200_75));

        // 15. Add with MathContext
        MathContext mc = new MathContext(10, RoundingMode.HALF_UP);
        bench("add with MathContext(10)", () -> a100_50.add(a200_75, mc));
    }

    @FunctionalInterface
    interface Op {
        BigDecimal run();
    }

    static void bench(String name, Op op) {
        // Force JIT compilation
        for (int i = 0; i < 10_000; i++) {
            op.run();
        }

        // Measure
        long[] times = new long[5];
        BigDecimal result = null;
        for (int run = 0; run < 5; run++) {
            long start = System.nanoTime();
            for (int i = 0; i < MEASURE_ITERS; i++) {
                result = op.run();
            }
            long end = System.nanoTime();
            times[run] = end - start;
        }

        // Use median
        java.util.Arrays.sort(times);
        long median = times[2];
        double nsPerOp = (double) median / MEASURE_ITERS;

        System.out.printf("%-45s %12.2f %12s%n", name, nsPerOp, 
            result != null && result.signum() != 0 ? "✓" : "=0");
    }
}
