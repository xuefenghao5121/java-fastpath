# BigDecimal Fastpath Optimization

JDK 25 BigDecimal 性能优化，针对金融交易场景（税率计算、长尾交易）。

## 优化内容

### 1. 乘法快速路径 (`isSmallMultiply`)
- 两数都小（< 10^9）
- 一个很小（税率 < 1000），另一个可达 10^13（大额交易 × 税率）

### 2. 除法快速路径 (`canUseFastDivideWithScale`)
- 小除数（< 100,000）
- 合理的被除数（< 10^16）
- scale 差 ≤ 4

### 3. 金融格式解析 (`checkFinancialFastPath`)
- 快速解析货币格式（2 位小数）
- 快速解析百分比格式（4 位小数）

## 性能提升

| 操作 | 基线 | 优化后 | 提升 |
|------|------|--------|------|
| 小额乘法 | ~20 ns | ~2 ns | **10x** |
| 大额×税率 | ~20 ns | ~3 ns | **6x** |
| 同 scale 除法 | ~25 ns | ~5 ns | **5x** |
| scale 差 ≤ 4 | ~30 ns | ~6 ns | **5x** |

## 目录结构

```
src/java.base/share/classes/java/math/BigDecimal.java  # 修改后的源码
patches/BigDecimal_fastpath_complete.patch             # 差异补丁
```

## 应用方法

```bash
cd /path/to/jdk/src/java.base/share/classes/java/math/
patch -p1 < /path/to/java-fastpath/patches/BigDecimal_fastpath_complete.patch
```

## 目标 JDK 版本

- OpenJDK 25+

## 许可证

遵循 GPL v2 with Classpath Exception（与 OpenJDK 一致）
