# Knuth Algorithm D Optimization for MutableBigInteger

## Overview
Optimizes the inner loop of Knuth's Algorithm D (TAOCP Vol. 2, §4.3.1) in
`MutableBigInteger.java`, used by BigDecimal division when operands exceed
long arithmetic range.

## Optimizations

### 1. Reciprocal Multiplication for qhat Estimation
Replaces `Long.divideUnsigned()` + `Long.remainderUnsigned()` (2 hardware
divisions) with `Math.unsignedMultiplyHigh()` (1 UMULH) + 1 multiply.

- **Precompute**: `v = ceil(2^64 / dh)` (once per division)
- **Per quotient digit**: `qhat = UMULH(nChunk, v)`, then correct with multiply+compare
- **ARM benefit**: UMULH (3-5 cyc) vs UDIV (12-20 cyc) → save ~10-15 cyc/digit
- **x86 impact**: Neutral (UMULH = 2 MULs, similar cost to DIV)
- Reference: Granlund & Möller, "Division by Invariant Integers", PLDI 1994

### 2. dlen==2 Inlined Fast Path
When the divisor has exactly 2 ints (64-bit), uses `mulsubLong()`/`divaddLong()`
(unrolled, no loop) instead of generic `mulsub()`/`divadd()` (looped).

- Inlined at call site (not extracted to separate method) to avoid JIT inlining failure
- Branch predictor predicts well since dlen is constant within a division

### 3. Applied to Three Code Paths
- `divideMagnitude()` — general Knuth D (dlen > 1)
- `divideLongMagnitude()` — 64-bit long divisor path
- `divideOneWord()` — unchanged (already uses hardware division efficiently)

## Files Modified
- `MutableBigInteger.java` — core Knuth D implementation

## Verification
- 9,208 functional tests pass (edge cases + random + MathContext)
- ScenarioBenchmark: 10-19% improvement on divS2/divS10 (common case)
- x86 neutral on large divisions (ARM-specific benefit expected)
