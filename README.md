# java-fastpath

JDK 25 BigDecimal 快速路径优化，税务系统长尾计算场景。

## 优化内容

### v3 (2026-07-03) — 除法优化

1. **精确除法快速路径** (`divide(BigDecimal, MathContext)` 公有方法)
   - 在 precision 归一化之前检查 `xs % ys == 0`
   - 使用实际 scale（非 precision）计算，避免 BigInteger 分配
   - 预过滤：`|xs| < |ys|` 时跳过（仅 1 次比较，~0.5ns 开销）
   - 效果：100÷0.001 从 178ns → 8ns (**-95%**)

2. **128-bit 分裂乘法路径** (`divide(long, int, long, int, ...)` 内部方法)
   - mcp≥18 时，将 `10^effMcp` 分解为 `10^18 × 10^remainder`
   - 用 `multiplyDivideAndRound`（128-bit）替代 BigInteger 除法
   - 精度预检查：`part2/ys ≥ 10^(mcp-19)` 确保商有足够有效数字
   - 范围预检查：`part2/ys < 18` 确保商适合 unsigned long
   - 效果：100÷99.999 从 45ns → 32ns (**-29%**)

3. **条件 actual-scale 入口** (`divide(BigDecimal, MathContext)` 公有方法)
   - 当 `dividend.precision() > divisor.precision()` 时，用实际 scale 替代 precision
   - 使 fastpath 条件 `xscale <= yscale` 能在更多场景触发
   - 仅在 precision 条件失败时启用，不影响已有路径正确性

4. **subtract 编译修复**
   - 修复 `BigInteger.valueOf(xs).subtract(ys)` 类型错误（ys 为 long）

### v2 (2026-07-02) — 乘法 + 解析优化

1. 乘法快速路径 (`isSmallMultiply`)：大额×税率场景 +11.6%
2. 除法快速路径：移除冗余 guard check
3. 金融格式解析：货币(2位)/百分比(4位) +7.6%

## 文件

- `bigdecimal2.java` — 修改后的完整 BigDecimal 源码
- `patches/bigdecimal_fastpath_v3.patch` — v3 差异补丁
- `patches/bigdecimal_fastpath_v2.patch` — v2 差异补丁

## 测试

base=100, 除数: 0.001 ~ 1.23456789E+10, mcp=10/17/20

| 场景 | mcp=20 | mcp=17 | mcp=10 |
|------|--------|--------|--------|
| 精确除法 (÷0.001, ÷0.01) | -95% | -75% | -69% |
| 近似整除 (÷99.999) | -29% | +8%* | ≈ |
| 非精确除法 | +9~13%* | +8%* | ≈ |

*非精确除法的回退来自精确除法预检查的 ~3ns 固有开销

## 使用

```bash
# 用 patch 版本运行
java --patch-module java.base=/path/to/patch_module \
  --add-opens java.base/java.math=ALL-UNNAMED \
  -cp your_app.jar com.example.Main
```
