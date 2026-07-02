# BigDecimal Fastpath Optimization

JDK 25 BigDecimal 性能优化，针对税务系统长尾计算场景（税率计算、大额交易）。

## 优化内容

### 1. 乘法快速路径 (`isSmallMultiply`)
- 两数都小（< 10^9）
- 一个很小（税率 < 1000），另一个可达 10^13（大额交易 × 税率）

### 2. 除法快速路径
- 小除数（2-5 位小数，税务场景固定范围）
- 无冗余 guard check，直接进入计算
- `ROUND_DOWN`：signed division 直接返回
- 其他舍入模式：委托 `divideAndRound`

### 3. 金融格式解析 (`checkFinancialFastPath`)
- 快速解析货币格式（2 位小数）
- 快速解析百分比格式（4 位小数）

## 性能提升

| 操作 | Baseline (ns/op) | Fastpath (ns/op) | 提升 |
|------|-----------------:|-----------------:|------|
| 大额×税率 | 4.23 | 3.74 | **+11.6%** |
| 百分比解析 | ~15.5 | ~14.3 | **+7.6%** |
| 除法 2-5位 | ~4.6 | ~4.9 | ~-5% (patch 开销) |

## 目录结构

```
bigdecimal2.java                          # 修改后的完整 BigDecimal 源码（替代原版）
patches/bigdecimal_fastpath_v2.patch      # 当前代码 vs OpenJDK 25 原始 BigDecimal 差异 patch
benchmark/                                # JMH 基准测试
├── src/com/fastpath/bench/
│   └── BigDecimalBenchmark.java          # 10 个测试方法
├── build.sh                              # 构建脚本
├── run.sh                                # 一键运行（baseline + fastpath）
├── generate_report.py                    # 报告生成
└── results/                              # benchmark 结果
    ├── baseline-results.json
    ├── fastpath-results.json
    └── BENCHMARK_REPORT.md
```

## 应用方法

```bash
# 方式 1：直接使用修改后的源码
cp bigdecimal2.java $JDK25_SRC/src/java.base/share/classes/java/math/BigDecimal.java

# 方式 2：应用 patch
cd $JDK25_SRC
patch -p1 < /path/to/bigdecimal_fastpath_v2.patch
```

## 运行 Benchmark

```bash
cd benchmark/
bash run.sh
```

## 目标 JDK 版本

- OpenJDK 25+

## 许可证

遵循 GPL v2 with Classpath Exception（与 OpenJDK 一致）
