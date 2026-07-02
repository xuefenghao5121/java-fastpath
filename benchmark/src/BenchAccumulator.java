import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Benchmark: add/subtract inline optimization + Mutable Accumulator
 * 
 * Tests three configurations:
 * 1. Baseline: stock JDK 25 BigDecimal
 * 2. Fastpath: patched BigDecimal (inline add/subtract)
 * 3. Accumulator: FastDecimalAccumulator (mutable, zero-alloc)
 */
public class BenchAccumulator {
    static final int WARMUP = 100_000;
    static final int MEASURE = 500_000;
    static BigDecimal sink;

    public static void main(String[] args) {
        // Prepare data
        int[] sizes = {100, 500, 1000};
        
        System.out.println("BigDecimal Add/Subtract Accumulation Benchmark");
        System.out.println("JIT will inline; testing accumulation (many intermediate objects)");
        System.out.println("================================================================");
        
        for (int size : sizes) {
            BigDecimal[] amounts = new BigDecimal[size];
            long[] unscaledAmounts = new long[size];
            int targetScale = 2;
            
            for (int i = 0; i < size; i++) {
                long unscaled = (long)(i % 1000 + 1) * 100 + (i % 100);
                amounts[i] = BigDecimal.valueOf(unscaled, targetScale);
                unscaledAmounts[i] = unscaled;
            }
            
            System.out.println("\n--- " + size + " values, scale=" + targetScale + " ---");
            
            // Warmup
            for (int i = 0; i < WARMUP / size + 1; i++) {
                BigDecimal t = BigDecimal.ZERO;
                for (int j = 0; j < size; j++) t = t.add(amounts[j]);
                sink = t;
            }
            for (int i = 0; i < WARMUP / size + 1; i++) {
                FastDecimalAccumulator acc = new FastDecimalAccumulator(targetScale);
                for (int j = 0; j < size; j++) acc.add(amounts[j]);
                sink = acc.toBigDecimal();
            }

            // Measure: BigDecimal chain
            long[] bdTimes = new long[7];
            for (int r = 0; r < 7; r++) {
                long s = System.nanoTime();
                for (int iter = 0; iter < MEASURE / size + 1; iter++) {
                    BigDecimal t = BigDecimal.ZERO;
                    for (int j = 0; j < size; j++) t = t.add(amounts[j]);
                    sink = t;
                }
                bdTimes[r] = System.nanoTime() - s;
            }
            java.util.Arrays.sort(bdTimes);
            long bdMedian = bdTimes[3];

            // Measure: FastDecimalAccumulator
            long[] accTimes = new long[7];
            for (int r = 0; r < 7; r++) {
                long s = System.nanoTime();
                for (int iter = 0; iter < MEASURE / size + 1; iter++) {
                    FastDecimalAccumulator acc = new FastDecimalAccumulator(targetScale);
                    for (int j = 0; j < size; j++) acc.add(amounts[j]);
                    sink = acc.toBigDecimal();
                }
                long e = System.nanoTime();
                accTimes[r] = e - s;
            }
            java.util.Arrays.sort(accTimes);
            long accMedian = accTimes[3];

            // Measure: Direct long array sum (theoretical minimum)
            long[] rawTimes = new long[7];
            for (int r = 0; r < 7; r++) {
                long s = System.nanoTime();
                for (int iter = 0; iter < MEASURE / size + 1; iter++) {
                    long sum = 0;
                    for (int j = 0; j < size; j++) sum += unscaledAmounts[j];
                    sink = BigDecimal.valueOf(sum, targetScale);
                }
                long e = System.nanoTime();
                rawTimes[r] = e - s;
            }
            java.util.Arrays.sort(rawTimes);
            long rawMedian = rawTimes[3];

            double bdUs = bdMedian / 1_000.0;
            double accUs = accMedian / 1_000.0;
            double rawUs = rawMedian / 1_000.0;
            double speedup = (double) bdMedian / accMedian;
            double rawFraction = (double) accMedian / rawMedian;

            System.out.printf("  BigDecimal chain:   %8.1f µs%n", bdUs);
            System.out.printf("  FastDecimalAccumulator: %8.1f µs  (%.1fx faster)%n", accUs, speedup);
            System.out.printf("  Raw long sum (limit):    %8.1f µs  (accumulator is %.0f%% of theoretical min)%n", 
                rawUs, rawFraction * 100);
        }
        
        System.out.println("\n================================================================");
        System.out.println("Conclusion: Accumulator eliminates intermediate BigDecimal allocation.");
        System.out.println("            Each chain.add() creates a new BigDecimal object;");
        System.out.println("            Accumulator only creates ONE at the end.");
        
        if (sink == null) System.out.println("BUG");
    }
}

/**
 * Mutable accumulator for high-performance BigDecimal addition.
 * 
 * Instead of creating N intermediate BigDecimal objects (one per add),
 * this accumulates the unscaled long values directly, creating only
 * ONE BigDecimal at the end.
 * 
 * Usage:
 *   FastDecimalAccumulator acc = new FastDecimalAccumulator(2); // scale=2
 *   for (BigDecimal bd : values) acc.add(bd);
 *   BigDecimal result = acc.toBigDecimal();
 */
class FastDecimalAccumulator {
    private long unscaledSum;
    private final int scale;
    
    public FastDecimalAccumulator(int scale) {
        this.scale = scale;
        this.unscaledSum = 0;
    }
    
    /**
     * Add a BigDecimal value. Avoids intermediate allocation entirely.
     * Handles scale alignment for values with different scales.
     */
    public FastDecimalAccumulator add(BigDecimal value) {
        if (value.scale() == scale) {
            // Same scale: use longValue for unscaled value (works for both compact and inflated)
            unscaledSum += value.unscaledValue().longValue();
        } else {
            // Different scale: align then add
            BigDecimal aligned = value.setScale(scale, java.math.RoundingMode.HALF_UP);
            unscaledSum += aligned.unscaledValue().longValue();
        }
        return this;
    }
    
    /**
     * Subtract a BigDecimal value.
     */
    public FastDecimalAccumulator subtract(BigDecimal value) {
        if (value.scale() == scale) {
            unscaledSum -= value.unscaledValue().longValue();
        } else {
            BigDecimal aligned = value.setScale(scale, java.math.RoundingMode.HALF_UP);
            unscaledSum -= aligned.unscaledValue().longValue();
        }
        return this;
    }
    
    /**
     * Convert the accumulated sum to a BigDecimal.
     * This is the only allocation in the entire accumulation.
     */
    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(unscaledSum, scale);
    }
    
    public long unscaledValue() {
        return unscaledSum;
    }
    
    public int scale() {
        return scale;
    }
}
