import java.math.*;
import java.util.*;

/**
 * Alternating benchmark: runs optimized and baseline in the same process
 * to minimize system variance. Uses --patch-module to select which version.
 * Pass "patched" or "stock" as arg[0].
 */
public class KnuthAltBenchmark {

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "unknown";
        int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 50;

        Random rnd = new Random(42); // fixed seed for reproducibility

        // Generate test data
        BigDecimal[][] dlen2 = new BigDecimal[200][2];
        BigDecimal[][] dlen3to5 = new BigDecimal[200][2];
        BigDecimal[][] dlenLarge = new BigDecimal[200][2];

        for (int i = 0; i < 200; i++) {
            BigInteger num = new BigInteger(200 + rnd.nextInt(300), rnd).abs();
            if (num.equals(BigInteger.ZERO)) num = BigInteger.ONE;
            BigInteger den = new BigInteger(33 + rnd.nextInt(31), rnd).abs();
            if (den.equals(BigInteger.ZERO)) den = BigInteger.ONE;
            den = den.setBit(32);
            dlen2[i][0] = new BigDecimal(num);
            dlen2[i][1] = new BigDecimal(den);
        }
        for (int i = 0; i < 200; i++) {
            BigInteger num = new BigInteger(300 + rnd.nextInt(400), rnd).abs();
            if (num.equals(BigInteger.ZERO)) num = BigInteger.ONE;
            BigInteger den = new BigInteger(96 + rnd.nextInt(64), rnd).abs();
            if (den.equals(BigInteger.ZERO)) den = BigInteger.ONE;
            dlen3to5[i][0] = new BigDecimal(num);
            dlen3to5[i][1] = new BigDecimal(den);
        }
        for (int i = 0; i < 200; i++) {
            BigInteger num = new BigInteger(800 + rnd.nextInt(400), rnd).abs();
            if (num.equals(BigInteger.ZERO)) num = BigInteger.ONE;
            BigInteger den = new BigInteger(192 + rnd.nextInt(448), rnd).abs();
            if (den.equals(BigInteger.ZERO)) den = BigInteger.ONE;
            dlenLarge[i][0] = new BigDecimal(num);
            dlenLarge[i][1] = new BigDecimal(den);
        }

        MathContext mc = new MathContext(50, RoundingMode.HALF_UP);

        // Warmup
        for (int w = 0; w < warmup; w++) {
            runAll(dlen2, dlen3to5, dlenLarge, mc);
        }

        // Measure each category separately with many rounds
        System.out.println("MODE: " + mode);

        // dlen==2
        long best2 = Long.MAX_VALUE;
        for (int r = 0; r < rounds; r++) {
            long start = System.nanoTime();
            BigDecimal[] res = runCategory(dlen2, mc);
            long elapsed = System.nanoTime() - start;
            best2 = Math.min(best2, elapsed);
            long sink = res[0].hashCode();
        }
        System.out.printf("dlen==2:     %.1f ns/op (best of %d)%n", best2 / 200.0, rounds);

        // dlen 3-5
        long best3 = Long.MAX_VALUE;
        for (int r = 0; r < rounds; r++) {
            long start = System.nanoTime();
            BigDecimal[] res = runCategory(dlen3to5, mc);
            long elapsed = System.nanoTime() - start;
            best3 = Math.min(best3, elapsed);
        }
        System.out.printf("dlen3to5:    %.1f ns/op (best of %d)%n", best3 / 200.0, rounds);

        // dlen large
        long bestL = Long.MAX_VALUE;
        for (int r = 0; r < rounds; r++) {
            long start = System.nanoTime();
            BigDecimal[] res = runCategory(dlenLarge, mc);
            long elapsed = System.nanoTime() - start;
            bestL = Math.min(bestL, elapsed);
        }
        System.out.printf("dlenLarge:   %.1f ns/op (best of %d)%n", bestL / 200.0, rounds);
    }

    static void runAll(BigDecimal[][] d2, BigDecimal[][] d3, BigDecimal[][] dL, MathContext mc) {
        runCategory(d2, mc);
        runCategory(d3, mc);
        runCategory(dL, mc);
    }

    static BigDecimal[] runCategory(BigDecimal[][] cases, MathContext mc) {
        BigDecimal[] results = new BigDecimal[cases.length];
        for (int i = 0; i < cases.length; i++) {
            results[i] = cases[i][0].divide(cases[i][1], mc);
        }
        return results;
    }
}
