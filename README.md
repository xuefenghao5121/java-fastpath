# java-fastpath

BigDecimal 快速路径优化，面向 ARM AArch64 (Kunpeng 930 / Neoverse V2)。

## 使用

### 方式 1: --patch-module（推荐）

```bash
# 编译
javac --patch-module java.base=patched/java.base \
  --add-exports java.base/jdk.internal.access=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.math=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.util=ALL-UNNAMED \
  -d patched_classes patched/java.base/java/math/BigDecimal.java

# 运行
java --patch-module java.base=/path/to/patched_module \
  --add-opens java.base/java.math=ALL-UNNAMED \
  -cp your_app.jar com.example.Main
```

### 方式 2: 应用 patch

```bash
# 仅 BigDecimal 快速路径
cd $OPENJDK25_SRC
patch -p1 < bigdecimal_v10.patch

# 包含 Knuth D 优化（MutableBigInteger）
patch -p1 < full_optimization.patch
```

## 文件

| 文件 | 说明 |
|------|------|
| `BigDecimal.java` | 修改后的完整源码，覆盖 JDK 25 原文件即可 |
| `patches/bigdecimal_v10.patch` | BigDecimal 快速路径 diff vs OpenJDK 25 |
| `patches/mutablebigint_knuth.patch` | MutableBigInteger Knuth D 优化 diff |
| `patches/full_optimization.patch` | 上述两个 patch 合并 |

## 优化内容

### BigDecimal 快速路径 (v1-v10)

- **乘法**: `isSmallMultiply` 快速路径，大额×税率场景
- **除法**: 扩大 `divideSmallFastPath` 覆盖范围，支持更大 scale/precision
- **128-bit 直通路径**: `Math.unsignedMultiplyHigh` (JDK 18+ intrinsic, ARM→UMULH)
- **精确除法检查**: 前置过滤，避免不必要的 BigInteger 运算
- **内联优化**: divide/setScale fast path 直接内联，减少方法调用开销

### Knuth Algorithm D (MutableBigInteger)

- **倒数乘法**: 用 `Math.unsignedMultiplyHigh` 替代 UDIV 做 qhat 估算
- **dlen==2 快速路径**: `mulsubLong`/`divaddLong` 展开替代通用循环
- 适用场景: scale=50 大数除法、BigInteger 级运算

## 验证

- 差分测试: 6993 cases vs stock JDK 25 全部一致
- 功能测试: 9208 cases 通过
- 平台: x86 验证，ARM 待测

## 目标平台

- ARM AArch64 (Kunpeng 930 / Neoverse V2), JDK 25+
- x86 仅用于开发验证
