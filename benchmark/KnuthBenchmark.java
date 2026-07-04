import java.math.*;
import java.util.*;

/**
 * Benchmark for Knuth Algorithm D optimizations.
 * Measures division performance for cases that exercise the Knuth path
 * (BigInteger division with various divisor lengths).
 */
public class KnuthBenchmark {

    static void benchmarkDiv(int warmup, int iterations) {
        Random rnd = new Random(42);

        // Generate test cases that force the Knuth D path
        // (BigInteger division, various dlen sizes)

        // === Category 1: dlen==2 (64-bit divisor) ===
        BigDecimal[][] dlen2Cases = new BigDecimal[100][2];
        for (int i = 0; i < 100; i++) {
            BigInteger num = new BigInteger(200 + rnd.nextInt(300), rnd).abs();
            if (num.equals(BigInteger.ZERO)) num = BigInteger.ONE;
            BigInteger den = new BigInteger(33 + rnd.nextInt(31), rnd).abs();
            if (den.equals(BigInteger.ZERO)) den = BigInteger.ONE;
            den = den.setBit(32); // ensure dlen==2
            dlen2Cases[i][0] = new BigDecimal(num);
            dlen2Cases[i][1] = new BigDecimal(den);
        }

        // === Category 2: dlen==3-5 (medium divisor) ===
        BigDecimal[][] dlen3to5Cases = new BigDecimal[100][2];
        for (int i = 0; i < 100; i++) {
            BigInteger num = new BigInteger(300 + rnd.nextInt(400), rnd).abs();
            if (num.equals(BigInteger.ZERO)) num = BigInteger.ONE;
            BigInteger den = new BigInteger(96 + rnd.nextInt(64), rnd).abs();
            if (den.equals(BigInteger.ZERO)) den = BigInteger.ONE;
            dlen3to5Cases[i][0] = new BigDecimal(num);
            dlen3to5Cases[i][1] = new BigDecimal(den);
        }

        // === Category 3: dlen==6-20 (large divisor) ===
        BigDecimal[][] dlenLargeCases = new BigDecimal[100][2];
        for (int i = 0; i < 100; i++) {
            BigInteger num = new BigInteger(800 + rnd.nextInt(400), rnd).abs();
            if (num.equals(BigInteger.ZERO)) num = BigInteger.ONE;
            BigInteger den = new BigInteger(192 + rnd.nextInt(448), rnd).abs();
            if (den.equals(BigInteger.ZERO)) den = BigInteger.ONE;
            dlenLargeCases[i][0] = new BigDecimal(num);
            dlenLargeCases[i][1] = new BigDecimal(den);
        }

        // === Category 4: BigDecimal with scale (forces specific paths) ===
        BigDecimal[][] scaledCases = new BigDecimal[100][2];
        for (int i = 0; i < 100; i++) {
            // High scale forces BigInteger path even for small numbers
            BigDecimal num = BigDecimal.valueOf(rnd.nextLong()).setScale(18 + rnd.nextInt(10));
            BigDecimal den = BigDecimal.valueOf(1L + rnd.nextInt(Integer.MAX_VALUE - 1)).setScale(15 + rnd.nextInt(10));
            if (den.signum() == 0) den = BigDecimal.ONE;
            scaledCases[i][0] = num;
            scaledCases[i][1] = den;
        }

        MathContext mc50 = new MathContext(50, RoundingMode.HALF_UP);

        // Warmup
        System.out.println("Warming up...");
        for (int w = 0; w < warmup; w++) {
            runCategory(dlen2Cases, mc50);
            runCategory(dlen3to5Cases, mc50);
            runCategory(dlenLargeCases, mc50);
            runCategory(scaledCases, mc50);
        }

        // Benchmark
        System.out.println("\n=== Knuth Division Benchmark ===\n");

        long start, end;

        // dlen==2
        start = System.nanoTime();
        BigDecimal[] results1 = new BigDecimal[dlen2Cases.length];
        for (int iter = 0; iter < iterations; iter++) {
            results1 = runCategory(dlen2Cases, mc50);
        }
        end = System.nanoTime();
        printResult("dlen==2 (64-bit divisor)", dlen2Cases.length * iterations, start, end);

        // dlen 3-5
        start = System.nanoTime();
        BigDecimal[] results2 = new BigDecimal[dlen3to5Cases.length];
        for (int iter = 0; iter < iterations; iter++) {
            results2 = runCategory(dlen3to5Cases, mc50);
        }
        end = System.nanoTime();
        printResult("dlen 3-5 (medium divisor)", dlen3to5Cases.length * iterations, start, end);

        // dlen 6-20
        start = System.nanoTime();
        BigDecimal[] results3 = new BigDecimal[dlenLargeCases.length];
        for (int iter = 0; iter < iterations; iter++) {
            results3 = runCategory(dlenLargeCases, mc50);
        }
        end = System.nanoTime();
        printResult("dlen 6-20 (large divisor)", dlenLargeCases.length * iterations, start, end);

        // Scaled
        start = System.nanoTime();
        BigDecimal[] results4 = new BigDecimal[scaledCases.length];
        for (int iter = 0; iter < iterations; iter++) {
            results4 = runCategory(scaledCases, mc50);
        }
        end = System.nanoTime();
        printResult("Scaled (high scale)", scaledCases.length * iterations, start, end);

        // Prevent JIT dead-code elimination
        long sink = 0;
        for (BigDecimal r : results1) sink ^= r.hashCode();
        for (BigDecimal r : results2) sink ^= r.hashCode();
        for (BigDecimal r : results3) sink ^= r.hashCode();
        for (BigDecimal r : results4) sink ^= r.hashCode();
        System.out.println("\n(sink: " + sink + ")");
    }

    static BigDecimal[] runCategory(BigDecimal[][] cases, MathContext mc) {
        BigDecimal[] results = new BigDecimal[cases.length];
        for (int i = 0; i < cases.length; i++) {
            results[i] = cases[i][0].divide(cases[i][1], mc);
        }
        return results;
    }

    static void printResult(String label, int ops, long startNs, long endNs) {
        double totalMs = (endNs - startNs) / 1_000_000.0;
        double perOpNs = (endNs - startNs) / (double) ops;
        System.out.printf("%-35s %8.1f ms total | %8.1f ns/op%n", label, totalMs, perOpNs);
    }

    public static void main(String[] args) {
        int warmup = 5;
        int iterations = 20;

        if (args.length >= 1) warmup = Integer.parseInt(args[0]);
        if (args.length >= 2) iterations = Integer.parseInt(args[1]);

        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("Warmup rounds: " + warmup);
        System.out.println("Benchmark iterations: " + iterations);

        benchmarkDiv(warmup, iterations);
    }
}
