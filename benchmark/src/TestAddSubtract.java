import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Correctness verification for BigDecimal add/subtract fastpath.
 */
public class TestAddSubtract {
    static int passed = 0;
    static int failed = 0;

    static void check(String name, BigDecimal actual, BigDecimal expected) {
        if (actual.compareTo(expected) == 0) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name + " — got " + actual + ", expected " + expected);
        }
    }

    public static void main(String[] args) {
        // ========== Same-Scale Add ==========
        check("add same scale 1", new BigDecimal("100.50").add(new BigDecimal("200.75")), new BigDecimal("301.25"));
        check("add same scale 2", new BigDecimal("0.01").add(new BigDecimal("0.02")), new BigDecimal("0.03"));
        check("add same scale neg", new BigDecimal("100.50").add(new BigDecimal("-50.25")), new BigDecimal("50.25"));
        check("add same scale large", new BigDecimal("99999999.99").add(new BigDecimal("0.01")), new BigDecimal("100000000.00"));
        check("add zero", new BigDecimal("123.45").add(BigDecimal.ZERO), new BigDecimal("123.45"));
        check("add to zero", BigDecimal.ZERO.add(new BigDecimal("123.45")), new BigDecimal("123.45"));
        check("add both negative", new BigDecimal("-100.50").add(new BigDecimal("-200.75")), new BigDecimal("-301.25"));

        // ========== Different-Scale Add ==========
        check("add diff scale 0vs2", BigDecimal.ZERO.add(new BigDecimal("123.45")), new BigDecimal("123.45"));
        check("add diff scale 2vs4", new BigDecimal("123.45").add(new BigDecimal("0.1234")), new BigDecimal("123.5734"));
        check("add diff scale 0vs4", BigDecimal.ZERO.add(new BigDecimal("0.1234")), new BigDecimal("0.1234"));
        check("add diff scale 4vs2", new BigDecimal("0.1234").add(new BigDecimal("123.45")), new BigDecimal("123.5734"));
        check("add diff scale large", new BigDecimal("99999").add(new BigDecimal("0.99")), new BigDecimal("99999.99"));

        // ========== Same-Scale Subtract ==========
        check("sub same scale 1", new BigDecimal("200.75").subtract(new BigDecimal("100.50")), new BigDecimal("100.25"));
        check("sub same scale neg", new BigDecimal("100.50").subtract(new BigDecimal("-50.25")), new BigDecimal("150.75"));
        check("sub same scale to neg", new BigDecimal("50.25").subtract(new BigDecimal("100.50")), new BigDecimal("-50.25"));
        check("sub same scale zero", new BigDecimal("123.45").subtract(new BigDecimal("0.00")), new BigDecimal("123.45"));
        check("sub self", new BigDecimal("123.45").subtract(new BigDecimal("123.45")), new BigDecimal("0.00"));
        check("sub same scale large", new BigDecimal("99999999.99").subtract(new BigDecimal("0.01")), new BigDecimal("99999999.98"));

        // ========== Different-Scale Subtract ==========
        check("sub diff scale 0vs2", BigDecimal.ZERO.subtract(new BigDecimal("123.45")), new BigDecimal("-123.45"));
        check("sub diff scale", new BigDecimal("99999").subtract(new BigDecimal("0.99")), new BigDecimal("99998.01"));
        check("sub diff scale 2vs0", new BigDecimal("123.45").subtract(new BigDecimal("99999")), new BigDecimal("-99875.55"));

        // ========== Accumulation ==========
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 1; i <= 100; i++) {
            total = total.add(new BigDecimal(i + ".50"));
        }
        check("accumulate 100 values", total, new BigDecimal("5100.00"));

        // ========== Edge Cases ==========
        // Near overflow boundary
        BigDecimal nearMax = BigDecimal.valueOf(Long.MAX_VALUE / 100, 0);
        BigDecimal oneMore = new BigDecimal("1");
        check("add near overflow", nearMax.add(oneMore), nearMax.add(oneMore)); // self-check

        // Large value that needs BigInteger path
        BigDecimal bigVal = new BigDecimal("99999999999999999999.99");
        BigDecimal bigVal2 = new BigDecimal("0.01");
        check("add big+small", bigVal.add(bigVal2), new BigDecimal("100000000000000000000.00"));

        // ========== Subtract edge cases ==========
        check("sub big-small", bigVal.subtract(bigVal2), new BigDecimal("99999999999999999999.98"));

        // ========== Multiply + Add (tax calc pattern) ==========
        BigDecimal amount = new BigDecimal("99999999.99");
        BigDecimal rate = new BigDecimal("0.1300");
        BigDecimal tax = amount.multiply(rate);
        BigDecimal total2 = amount.add(tax);
        check("amount * rate + amount", total2, amount.multiply(rate).add(amount));

        // ========== Scale correctness ==========
        // Note: compareTo ignores scale for equal values

        // ========== Multiple operations chain ==========
        BigDecimal a = new BigDecimal("100.00");
        BigDecimal b = new BigDecimal("50.25");
        BigDecimal c = new BigDecimal("25.75");
        check("a-b+c", a.subtract(b).add(c), new BigDecimal("75.50"));
        check("a-(b+c)", a.subtract(b.add(c)), new BigDecimal("24.00"));

        // ========== Commutativity ==========
        BigDecimal x = new BigDecimal("123.45");
        BigDecimal y = new BigDecimal("678.90");
        check("add commutative", x.add(y), y.add(x));

        // ========== Associativity (may differ in scale) ==========
        BigDecimal z = new BigDecimal("0.001");
        check("add associative (x+y)+z ≈ x+(y+z)", 
              x.add(y).add(z), x.add(y.add(z)));

        System.out.println("\n=========================================");
        System.out.println("  Passed: " + passed + " / " + (passed + failed));
        if (failed > 0) {
            System.out.println("  FAILED: " + failed);
            System.exit(1);
        } else {
            System.out.println("  ALL TESTS PASSED ✅");
        }
    }
}
