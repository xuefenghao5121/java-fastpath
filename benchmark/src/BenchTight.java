import java.math.BigDecimal;

/**
 * Tight microbenchmark — no lambda overhead, tests the hot path directly.
 */
public class BenchTight {
    static final int ITERS = 2_000_000;
    static BigDecimal sink;

    public static void main(String[] args) {
        BigDecimal a = new BigDecimal("100.50");
        BigDecimal b = new BigDecimal("200.75");
        BigDecimal c = new BigDecimal("1000000.99");
        BigDecimal d = new BigDecimal("-50.25");
        BigDecimal e = new BigDecimal("123.45");
        BigDecimal f = new BigDecimal("0.1234");
        BigDecimal zero = BigDecimal.ZERO;

        // Warmup all paths
        System.out.print("Warming up... ");
        for (int i = 0; i < 500_000; i++) {
            sink = a.add(b);
            sink = a.add(d);
            sink = b.subtract(a);
            sink = zero.add(e);
            sink = e.add(f);
        }
        System.out.println("done");

        System.out.println("================================================================");
        System.out.printf("%-40s %10s%n", "Test", "ns/op");
        System.out.println("----------------------------------------------------------------");

        // 1. Same-scale add small
        measure("add same-scale (100.50+200.75)", () -> {
            for (int i = 0; i < ITERS; i++) sink = a.add(b);
        });

        // 2. Same-scale add large  
        measure("add same-scale (1M.99+200.75)", () -> {
            for (int i = 0; i < ITERS; i++) sink = c.add(b);
        });

        // 3. Same-scale add negative
        measure("add same-scale (100.50+(-50.25))", () -> {
            for (int i = 0; i < ITERS; i++) sink = a.add(d);
        });

        // 4. Different-scale add (scale 0→2)
        measure("add diff-scale (0+123.45)", () -> {
            for (int i = 0; i < ITERS; i++) sink = zero.add(e);
        });

        // 5. Different-scale add (scale 2→4)
        measure("add diff-scale (123.45+0.1234)", () -> {
            for (int i = 0; i < ITERS; i++) sink = e.add(f);
        });

        // 6. Same-scale subtract
        measure("sub same-scale (200.75-100.50)", () -> {
            for (int i = 0; i < ITERS; i++) sink = b.subtract(a);
        });

        // 7. Same-scale subtract (→negative)
        measure("sub same-scale (100.50-200.75)", () -> {
            for (int i = 0; i < ITERS; i++) sink = a.subtract(b);
        });

        // 8. Same-scale subtract negative
        measure("sub same-scale (100.50-(-50.25))", () -> {
            for (int i = 0; i < ITERS; i++) sink = a.subtract(d);
        });

        // 9. Different-scale subtract
        measure("sub diff-scale (99999-123.45)", () -> {
            BigDecimal big = new BigDecimal("99999");
            for (int i = 0; i < ITERS; i++) sink = big.subtract(e);
        });

        // 10. Add zero
        measure("add zero (123.45+0)", () -> {
            for (int i = 0; i < ITERS; i++) sink = e.add(zero);
        });

        // 11. Subtract self
        measure("sub self (123.45-123.45)", () -> {
            for (int i = 0; i < ITERS; i++) sink = e.subtract(e);
        });

        // 12. Accumulate 100 values
        BigDecimal[] arr = new BigDecimal[100];
        for (int i = 0; i < 100; i++) arr[i] = new BigDecimal((i+1) + ".50");
        measure("accumulate 100 values", () -> {
            BigDecimal t = BigDecimal.ZERO;
            for (int i = 0; i < 100; i++) t = t.add(arr[i]);
            sink = t;
        });

        // 13. Accumulate 1000 values
        BigDecimal[] arr2 = new BigDecimal[1000];
        for (int i = 0; i < 1000; i++) arr2[i] = new BigDecimal((i%1000+1) + ".50");
        measure("accumulate 1000 values", () -> {
            BigDecimal t = BigDecimal.ZERO;
            for (int i = 0; i < 1000; i++) t = t.add(arr2[i]);
            sink = t;
        });

        System.out.println("================================================================");
        // Prevent dead code elimination
        if (sink == null) System.out.println("BUG");
    }

    @FunctionalInterface
    interface Block { void run(); }

    static void measure(String name, Block block) {
        // Extra warmup
        block.run();
        
        // Measure 5 runs
        long[] times = new long[5];
        for (int r = 0; r < 5; r++) {
            long s = System.nanoTime();
            block.run();
            long e = System.nanoTime();
            times[r] = e - s;
        }
        java.util.Arrays.sort(times);
        long median = times[2];
        double nsPerOp = (double) median / ITERS;
        System.out.printf("%-40s %10.2f%n", name, nsPerOp);
    }
}
