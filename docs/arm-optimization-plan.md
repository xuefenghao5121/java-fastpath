# ARM 平台 BigDecimal 除法优化方案

> 基于 AArch64 微架构分析，针对鲲鹏930 (Neoverse V2) 平台

---

## 1. 问题分析：为什么 x86 优化在 ARM 上无效

### 1.1 指令延迟对比

| 指令 | x86-64 (Skylake) | AArch64 (Neoverse V2) | 差异 |
|------|------------------|----------------------|------|
| `IDIV` / `UDIV` | 20-40 cycles | 12-20 cycles | ARM 快 2x |
| `IMUL` / `MUL` | 3 cycles | 3-5 cycles | 基本相同 |
| `MUL+DIV` (取模) | 23-43 cycles | 15-25 cycles | ARM 快 1.5x |
| 分支误预测 | ~15 cycles | ~15 cycles | 相同 |
| `UMULH` (128-bit mul) | N/A (需要 MUL+IMUL) | 3-5 cycles | ARM 原生支持 |

### 1.2 根因分析

**x86 上 BigInteger 为什么慢**：
- x86 `IDIV` 延迟 20-40 cycles，且不可流水化
- BigInteger 除法涉及多次 `IDIV` + 内存访问（对象分配）
- 我们的优化通过精确除法检查（1 次 `DIV` + `MSUB`）避免 BigInteger
- x86 上：检查成本 ~25 cycles vs 避免成本 ~100+ cycles → 净收益大

**ARM 上为什么不灵**：
- ARM `UDIV` 延迟仅 12-20 cycles，BigInteger 的相对开销更小
- ARM 上取模需要 `UDIV` + `MSUB` 两条指令（无硬件 remainder）
- 精确除法检查成本 ~18-25 cycles vs 避免成本 ~50-60 cycles → 净收益小
- **更关键**：ARM 的 BigInteger 算法使用了 `UMULH` 指令（64×64→128 高半部分），这在 ARM 上是原生的 3-5 cycle 操作，而在 x86 上需要两条 `MUL` 指令
- ARM JVM (HotSpot AArch64) 的 JIT 可能对 BigInteger 路径做了不同的内联决策

### 1.3 JIT 差异

| 方面 | x86 HotSpot | AArch64 HotSpot |
|------|-------------|-----------------|
| 方法内联阈值 | 默认 325 | 默认 325 (但代码膨胀策略不同) |
| `multiplyDivideAndRound` 内联 | 可能内联 | 可能不内联（AArch64 C2 更保守） |
| `BigInteger.divide` intrinsic | 无 | 无（但 `multiply` 有 NEON 优化） |
| SuperWord (自动向量化) | AVX2/AVX-512 | NEON/SVE (128/2048-bit) |

---

## 2. ARM 平台优化方案

### 方案 A：利用 UMULH 实现 128-bit 快速除法（推荐）

**核心思路**：ARM 原生支持 `UMULH`（64×64→128 高 64 位），可以用 2 条指令
（`MUL` + `UMULH`）完成 128-bit 乘法，比 x86 的 4 条指令快得多。

**适用场景**：`divide(divisor, scale, RoundingMode)` —— 你的核心业务场景

```
当前路径 (BigInteger):
  bigMultiplyPowerTen(xs, raise)  → BigInteger 分配 + 乘法
  divideAndRound(BigInteger, ys)  → BigInteger 除法
  
优化路径 (UMULH 128-bit):
  MUL  x0, xs, tenPow      → 低 64 位
  UMULH x1, xs, tenPow     → 高 64 位
  → 得到 128-bit 被除数 (x1:x0)
  → 用 divideAndRound128 做除法
```

**关键**：`multiplyDivideAndRound` 和 `divideAndRound128` 已经在 BigDecimal 中实现了
128-bit 除法。问题是当前的 fast path 条件 `mcp < 18` 阻止了它在高 precision 时被使用。

**优化**：在 ARM 上，由于 `UMULH` 快速，即使 `mcp >= 18`，也应该尝试 128-bit 路径。

### 方案 B：用 C2 JIT Intrinsics 替换热点方法

**核心思路**：为 BigDecimal 的关键方法编写 AArch64 intrinsic，让 HotSpot C2 编译器
直接生成优化的机器码。

**目标方法**：
1. `longMultiplyPowerTen(long, int)` → 用 `MUL` + `UMULH` 替代 BigInteger
2. `divideAndRound(long, long, int, int, long)` → 用 `UDIV` + `MSUB` 内联
3. `multiplyDivideAndRound(long, long, long, ...)` → 用 `MUL` + `UMULH` + `UDIV`

**实现方式**：
- 在 OpenJDK 源码中添加 `src/hotspot/cpu/aarch64/` 下的 intrinsic 实现
- 或用 JMH + JIT Watch 验证现有 intrinsic 是否已生效

### 方案 C：NEON/SVE 向量化批量除法（适合批量场景）

**核心思路**：如果你的业务中有批量 BigDecimal 除法（如遍历税率表），
可以用 NEON (128-bit) 或 SVE (鲲鹏930 支持) 做向量化除法。

```
NEON: 2 个 64-bit 除法并行 (VDIV.U64)
SVE:  可变宽度 (最多 256-bit = 4 个 64-bit 并行)
```

**限制**：需要改写业务代码为批量模式，不适合单次调用。

### 方案 D：JNI 原生方法（最激进）

**核心思路**：将 BigDecimal 的除法热点路径用 C/C++ + ARM 内联汇编实现，
通过 JNI 调用。

```c
// ARM64 原生 128-bit 除法
static inline uint128_t mul_div(uint64_t a, uint64_t b, uint64_t divisor) {
    uint64_t lo, hi;
    __asm__("mul %0, %2, %3\n\t"
            "umulh %1, %2, %3"
            : "=&r"(lo), "=&r"(hi)
            : "r"(a), "r"(b));
    // 128-bit / 64-bit division
    if (hi < divisor) {
        uint64_t q = 0;
        // Knuth's Algorithm D for 128/64
        ...
        return (uint128_t){q, hi - q * divisor};  // quotient + remainder
    }
    return (uint128_t){0, 0};  // overflow
}
```

**优势**：完全控制指令选择，避免 JIT 不确定性
**劣势**：JNI 调用开销 ~50ns，只适合计算密集场景

---

## 3. 推荐实施路径

### Phase 1: 验证 JIT 行为（1天）

在 ARM 服务器上运行以下诊断：

```bash
# 1. 检查 JIT 是否内联了关键方法
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining \
  -XX:CompileCommand=print,*BigDecimal.divide \
  -cp benchmark.jar ScenarioBenchmark 2>&1 | grep -E "inline|divide|multiply"

# 2. 检查是否生成了 UDIV 指令
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly \
  -XX:CompileCommand=print,*BigDecimal.divide \
  -cp benchmark.jar ScenarioBenchmark 2>&1 | grep -E "udiv|umulh|madd"

# 3. 对比禁用 C2 后的性能
java -Xint -cp benchmark.jar ScenarioBenchmark    # 纯解释器
java -XX:TieredStopAtLevel=1 -cp benchmark.jar ScenarioBenchmark  # 只用 C1
java -cp benchmark.jar ScenarioBenchmark          # C1 + C2
```

### Phase 2: 调整 fastpath 条件（1天）

根据 ARM UDIV 延迟更低的特性，调整优化策略：

```java
// ARM 上 UDIV 12-20 cycles (vs x86 20-40 cycles)
// 精确除法检查成本更低，可以更积极地使用
// 但 BigInteger 开销也更低，需要找到新的盈亏平衡点

// 方案：在 divide(divisor, scale, RoundingMode) 路径中，
// 用 UMULH 实现的 128-bit 乘法替代 BigInteger
// 关键：divide(divisor, scale, RM) 内部调用 divide(divisor, MathContext)
// 而 MathContext 的 precision = scale + ... (取决于具体场景)
```

### Phase 3: 实现 ARM-specific 128-bit 路径（3天）

```java
// 新增方法：利用 128-bit 算术的快速除法
private static BigDecimal divide128FastPath(
    long xs, int xscale, long ys, int yscale,
    int resultScale, int roundingMode
) {
    // 计算需要放大的倍数
    int raise = resultScale - (xscale - yscale);
    if (raise < 0) {
        // 缩小除数 instead
        ...
    }
    
    // 用 MUL + UMULH 计算 xs * 10^raise (128-bit)
    long tenPow = LONG_TEN_POWERS_TABLE[raise];  // raise < 19
    long lo = xs * tenPow;
    long hi;
    // 在 Java 层面，用 multiplyHigh() (JDK 18+)
    hi = Math.unsignedMultiplyHigh(xs, tenPow);
    
    // 128-bit / 64-bit 除法
    if (hi < ys) {
        // 商适合 64-bit，用 divideAndRound128
        return divideAndRound128(hi, lo, ys, ...);
    }
    return null; // 需要 BigInteger
}
```

**关键 JDK API**：`Math.unsignedMultiplyHigh(long, long)` (JDK 18+)
在 ARM 上会被 JIT 编译为 `UMULH` 指令！

### Phase 4: 优化 divide(divisor, scale, RoundingMode) 路径（2天）

当前 `divide(divisor, int scale, RoundingMode)` 的实现：
```java
public BigDecimal divide(BigDecimal divisor, int scale, RoundingMode rm) {
    // 内部构造 MathContext 并调用 divide(divisor, MathContext)
    // precision = ... (复杂计算)
    return divide(divisor, ...);
}
```

**优化**：绕过 MathContext 构造，直接用 128-bit 路径：
```java
public BigDecimal divide(BigDecimal divisor, int scale, RoundingMode rm) {
    // 直接计算，不走 MathContext 路径
    if (this.intCompact != INFLATED && divisor.intCompact != INFLATED) {
        // 用 unsignedMultiplyHigh 做 128-bit 除法
        // 避免 MathContext 对象分配和 precision 归一化
    }
    // 原始路径
}
```

---

## 4. ARM vs x86 优化策略差异总结

| 策略 | x86 效果 | ARM 效果 | 原因 |
|------|---------|---------|------|
| 精确除法检查 (modulo) | ✅ -95% | ≈ | x86 IDIV 慢，避免 BigInteger 收益大；ARM UDIV 快，BigInteger 开销也小 |
| 128-bit split 路径 | ✅ -30% | 待验证 | ARM UMULH 更快，但 BigInteger 也快了 |
| 乘法 fastpath (isSmallMultiply) | ✅ -7% | 待验证 | 乘法延迟相同，但 JIT 内联可能不同 |
| `Math.unsignedMultiplyHigh` | 未用 | **推荐** | ARM 原生 UMULH，x86 需要两条 MUL |
| 绕过 MathContext 构造 | 未优化 | **推荐** | 减少 GC 压力，ARM 对对象分配更敏感 |
| NEON/SVE 向量化 | N/A | 可选 | 适合批量场景 |

---

## 5. 下一步

1. **在 ARM 服务器上跑 JIT 诊断**（Phase 1），确认热点方法是否被正确编译
2. **验证 `Math.unsignedMultiplyHigh` 在 ARM 上是否生成 UMULH**
3. **实现 divide(divisor, scale, RoundingMode) 的 128-bit 直通路径**
4. **重新跑 ScenarioBenchmark 在 ARM 上对比**
