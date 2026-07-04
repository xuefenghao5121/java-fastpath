# 向量化 BigDecimal 除法方案

## 核心数学

### 问题定义
```
批量计算: q[i] = round(xs[i] × 10^raise / ys, scale, HALF_UP)  for i=0..N-1
除数 ys 固定，被除数 xs[i] 变化
```

### 为什么不能直接向量化 UDIV？
- SVE2 的 `UDIVR` 只支持 32-bit 元素
- 64-bit long 除法没有向量指令
- **解决方案：倒数乘法**

### 倒数乘法的数学

预计算（每个除数一次）：
```
v = ⌈2^64 / ys⌉ = ⌊(2^64 - 1) / ys⌋ + 1
```

每次除法（替代 UDIV）：
```
q = UMULH(n, v)     // n × v 的高 64 位
// q ∈ {⌊n/ys⌋, ⌊n/ys⌋+1}，最多偏差 1
r = n - q × ys       // 用 MUL + SUB 算余数
if (r < 0) { q--; r += ys; }  // 修正
```

### UMULH 的 Vector API 实现

Java Vector API 没有 `UMULH`，用 schoolbook 分解：

```
UMULH(a, b) 其中 a, b 是 unsigned 64-bit:
  a_hi = a >>> 32,  a_lo = a & 0xFFFFFFFF
  b_hi = b >>> 32,  b_lo = b & 0xFFFFFFFF

  lo    = a_lo × b_lo                    // 64-bit mul, 只取低32位进位
  mid1  = a_hi × b_lo
  mid2  = a_lo × b_hi
  hi    = a_hi × b_hi

  carry = ((mid1 & MASK32) + (mid2 & MASK32) + (lo >>> 32)) >>> 32
  result = hi + (mid1 >>> 32) + (mid2 >>> 32) + carry
```

每 UMULH = **4 次向量 MUL + ~6 次 ADD/SHIFT/AND**
4-lane 向量一次算 4 个 UMULH。

### 性能估算 (ARM SVE 256-bit, 4 lanes)

| 操作 | 标量 (4 次) | 向量 (1 批 4 个) |
|------|------------|-----------------|
| MUL | 4 × 3cyc = 12 | 4 lanes × 3 = 3cyc (pipelined) |
| UDIV | 4 × 15cyc = 60 | 0 (用 UMULH 替代) |
| UMULH (4×MUL+6×ADD) | — | ~10 × 2cyc = 20 |
| correction (MUL+SUB+CMP+SEL) | 4 × 5 = 20 | ~4 × 2 = 8 |
| rounding (CMP+ADD) | 4 × 3 = 12 | ~2 × 2 = 4 |
| **总计** | **~104 cyc** | **~32 cyc** |
| **吞吐/element** | **~26 cyc** | **~8 cyc** |

**理论加速比: ~3.3×**（4-lane，受 schoolbook 开销拖累）

### 对比：如果 Vector API 原生支持 UMULH

| 操作 | 向量 (1 批 4) |
|------|--------------|
| UMULH (原生) | 3 cyc |
| correction | 8 cyc |
| rounding | 4 cyc |
| **总计** | **~15 cyc** |
| **吞吐/element** | **~3.75 cyc** |

**理论加速比: ~7×** — 这才是 SVE 的真正实力

## 两条实现路径

### 路径 A: JNI + C/SVE 内联函数（性能最优）

```c
#include <arm_sve.h>

// 批量除法: dividends[i] / divisor, rounded HALF_UP
// N 必须是 4 的倍数 (SVE 256-bit, 4 × uint64)
void batch_divide_half_up_sve(
    const uint64_t *dividends,
    uint64_t divisor,
    uint64_t *results,
    int n
) {
    // 预计算倒数
    uint64_t v = (UINT64_MAX / divisor) + 1;  // ⌈2^64 / d⌉

    // 向量常量
    svuint64_t v_div  = svdup_u64(divisor);
    svuint64_t v_recip = svdup_u64(v);
    svbool_t pg_all = svptrue_b64();

    int i = 0;
    while (svcntd() <= n - i) {
        svuint64_t n_vec = svld1_u64(pg_all, dividends + i);

        // q = UMULH(n, v) — 1 条 SVE 指令
        svuint64_t q = svmulh_u64_x(pg_all, n_vec, v_recip);

        // r = n - q * d
        svuint64_t prod = svmul_u64_x(pg_all, q, v_div);
        svuint64_t r = svsub_u64_x(pg_all, n_vec, prod);

        // Correction: if r >= divisor, q++, r -= divisor
        svbool_t over = svcmpge_u64(pg_all, r, v_div);
        q = svadd_m(over, q, 1);

        // Rounding HALF_UP: if 2*r >= divisor, q++
        svuint64_t r2 = svadd_u64_x(pg_all, r, r);  // 2*r
        svbool_t round_up = svcmpge_u64(pg_all, r2, v_div);
        // 但 2*r 可能溢出... 需要特殊处理
        // 用 r >= (d+1)/2 代替 2*r >= d
        svuint64_t half = svlsr_n_u64_x(pg_all, v_div, 1);      // d/2
        svuint64_t half_plus = svadd_n_u64_x(pg_all, half, 1);  // (d+1)/2 ... careful with odd d
        svbool_t round_up2 = svcmpge_u64(pg_all, r, half_plus);
        // 简化: HALF_UP → r >= (d+1)/2 时进位
        // 实际上需要: 2*r > d (strict) for HALF_UP
        // 用 r > d/2 || (r == d/2 && d is even) ... 复杂
        // 简化: r >= (d - r) 即可
        // 正确做法: cmp = svcmpge_u64(pg_all, r2, v_div) 但要处理溢出
        // 更好: sub = svsub_u64_x(pg_all, v_div, r); round_up = svcmpgt_u64(pg_all, r, sub);

        q = svadd_m(round_up2, q, 1);

        svst1_u64(pg_all, results + i, q);
        i += svcntd();
    }
    // 处理尾部...
}
```

优点：原生 `svmulh_u64_x` = 1 条指令，性能最优
缺点：JNI 调用开销（~5-10ns），需要编译 .so

### 路径 B: Java Vector API（纯 Java）

```java
import jdk.incubator.vector.*;

static final VectorSpecies<Long> S = LongVector.SPECIES_PREFERRED;
static final long MASK32 = 0xFFFFFFFFL;

// 向量化 UMULH: a × b 的高 64 位 (unsigned)
static LongVector umulh(LongVector a, LongVector b) {
    LongVector a_lo = a.and(MASK32);
    LongVector a_hi = a.lanewise(VectorOperators.LSHR, 32);
    LongVector b_lo = b.and(MASK32);
    LongVector b_hi = b.lanewise(VectorOperators.LSHR, 32);

    LongVector lo = a_lo.mul(b_lo);
    LongVector mid1 = a_hi.mul(b_lo);
    LongVector mid2 = a_lo.mul(b_hi);
    LongVector hi = a_hi.mul(b_hi);

    LongVector lo_carry = lo.lanewise(VectorOperators.LSHR, 32);
    LongVector mid_lo_sum = mid1.and(MASK32).add(mid2.and(MASK32)).add(lo_carry);
    LongVector carry = mid_lo_sum.lanewise(VectorOperators.LSHR, 32);

    return hi.add(mid1.lanewise(VectorOperators.LSHR, 32))
             .add(mid2.lanewise(VectorOperators.LSHR, 32))
             .add(carry);
}

// 批量除法: xs[i] / ys, HALF_UP rounding
static void batchDivideHalfUp(long[] xs, long ys, long[] results) {
    long v = Long.divideUnsigned(-1L, ys) + 1;  // reciprocal

    LongVector vDiv = LongVector.broadcast(S, ys);
    LongVector vRecip = LongVector.broadcast(S, v);

    int i = 0;
    for (; i < S.loopBound(xs.length); i += S.length()) {
        LongVector n = LongVector.fromArray(S, xs, i);
        LongVector q = umulh(n, vRecip);           // q ≈ n / ys
        LongVector prod = q.mul(vDiv);              // q × ys
        LongVector r = n.sub(prod);                  // r = n - q×ys

        // Correction
        VectorMask<Long> over = r.compare(VectorOperators.LT, 0); // signed < 0 = unsigned overflow
        q = q.add(1, over);                           // q++
        // r correction not needed if we use comparison directly

        // HALF_UP rounding: 2|r| >= |ys| → but need unsigned compare
        // Simplest: r.compare(ABS_GE_HALF, ...)
        // 实际: |2*r| vs |ys| → 用 longCompareMagnitude 逻辑
        // 向量化: 如果 r < 0, 比较 -r vs ys/2; 如果 r >= 0, 比较 r vs ys/2
        // 简化: 用 unsigned 比较 |r| vs (ys+1)/2
        LongVector absR = r.abs();  // |r|
        long halfUp = (ys >>> 1) + 1; // ⌈ys/2⌉
        VectorMask<Long> roundUp = absR.compare(VectorOperators.GE, halfUp);
        q = q.add(1, roundUp);

        q.intoArray(results, i);
    }
    // 尾部
    for (; i < xs.length; i++) {
        results[i] = xs[i] / ys;
        long r = xs[i] % ys;
        if (Math.abs(2L * r) >= ys) {
            results[i] += (xs[i] ^ ys) >= 0 ? 1 : -1;
        }
    }
}
```

优点：纯 Java，无 JNI
缺点：schoolbook UMULH 需要 ~10 次向量操作，抵消部分收益

## 完整的 BatchDivide API 设计

```java
package java.math;

public final class BatchDivide {
    private final long divisor;
    private final int divisorScale;
    private final long reciprocal;

    public BatchDivide(BigDecimal divisor) { ... }

    // 批量: prices[i] / divisor → results[i]
    public void divide(BigDecimal[] dividends, int resultScale,
                       RoundingMode mode, BigDecimal[] results) { ... }

    // 高性能: 原始 long 数组
    public void divideRaw(long[] unscaledDividends, int dividendScale,
                          int resultScale, int roundingMode,
                          long[] outUnscaled) { ... }
}
```

## 实施计划

1. 先实现 Java Vector API 版本（路径 B），在 x86 验证正确性
2. 在鲲鹏 930 上 benchmark
3. 如果 schoolbook UMULH 开销过大，实现 JNI + SVE 版本（路径 A）
4. 终极目标：提交 JEP 提案，让 Vector API 支持 UMULH lane op
