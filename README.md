# 计算器 · Calculator

一个干净、离线、无广告的 Android 计算器。纯 Java + Android 原生 View 实现，**零第三方依赖**，安装包仅 65 KB。

![计算器界面](docs/screenshot.png)

## 功能

- **基础四则运算**：加、减、乘、除，支持多步连续运算
- **运算优先级**：`2 + 3 × 4 = 14`，符合数学直觉而不是简单顺序执行
- **实时预览**：边输入边算出结果，表达式与结果分两行显示
- **十进制精度**：内部使用 `BigDecimal`，`0.1 + 0.2` 精确等于 `0.3`，不会出现 `0.30000000000000004`
- **百分比**：`200 × 10% = 20`
- **正负号、退格、清空**：`±`、`⌫`、`C`
- **出错保护**：除以零显示「错误」，不会崩溃
- **自动缩字号**：结果太长时字号自动变小，不会溢出屏幕
- **触感反馈**：每次按键有轻微震动 + 缩放动画

## 安装

1. 下载 [`Calculator-1.0.0.apk`](Calculator-1.0.0.apk)
2. 在手机上点开，允许「安装未知来源应用」
3. 安装完成后桌面会出现蓝色的「计算器」图标

> 支持 Android 5.0（API 21）及以上。已在 Android 16 上验证。

## 截图

表达式行显示正在进行的算式，结果行实时预览；按 `=` 后结果转为大字号。

## 项目结构

```
calculator-android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/mapeople/calculator/
│   │   ├── CalcEngine.java      # 纯逻辑计算引擎（不依赖 Android API）
│   │   └── MainActivity.java    # 界面与事件绑定
│   └── res/
│       ├── layout/activity_main.xml
│       ├── values/{strings,colors,styles}.xml
│       ├── drawable/bg_key_*.xml
│       └── mipmap-*/ic_launcher.png
├── app/src/test/java/.../EngineTest.java   # 纯 JVM 单元测试
├── build.sh                     # 无 Gradle 构建脚本
├── test.sh                      # 逻辑层测试脚本
├── release/Calculator-1.0.0.apk # 预构建安装包
└── keystore/calc-release.jks    # 签名证书
```

## 构建

本项目**不使用 Gradle**，直接用 Android SDK 工具链手工构建，因此构建速度极快（约 3 秒），且不依赖网络。

### 依赖

- JDK 17
- Android SDK：`build-tools;34.0.0`、`platforms;android-34`

### 一键构建

```bash
./build.sh
```

产物：`build/Calculator-1.0.0.apk`

### 构建流水线

`build.sh` 依次执行：

| 步骤 | 工具 | 说明 |
| --- | --- | --- |
| 1 | `aapt2 compile` | 编译 `res/` 为 `res.zip` |
| 2 | `aapt2 link` | 链接资源 + 清单，生成 `unsigned.apk` 与 `R.java` |
| 3 | `javac` | 编译 Java 源码（`-bootclasspath` 指向 `android.jar` + `core-lambda-stubs.jar`） |
| 4 | `d8` | `.class` → `classes.dex` |
| 5 | `zipfile` + `zipalign` | 把 dex 塞进 APK 并对齐 |
| 6 | `apksigner` | v1 + v2 + v3 签名 |

### 逻辑层单元测试

`CalcEngine` 不依赖任何 Android API，可以直接在 JVM 上跑测试（27 条用例，覆盖四则运算、十进制精度、优先级、百分比、正负号、退格、连续等号等）：

```bash
./test.sh
```

输出：

```
== PASS 27 / FAIL 0 ==
```

## 计算引擎设计

`CalcEngine` 把状态分成三块：

- `tokens` —— 已确认的数字与运算符（数字以十进制字符串保存，保留原始输入）
- `entry` —— 正在输入的数字
- `justEvaluated` / `error` —— 状态标记

求值走**调度场算法**（shunting-yard）转后缀表达式，`× ÷` 优先级高于 `+ −`，再用 `BigDecimal` 做十进制运算，最后 `stripTrailingZeros()` 去尾零、限制 10 位小数。

关键取舍：

- **为什么用 BigDecimal 而不是 double**：`0.1 + 0.2` 用 double 得 `0.30000000000000004`，计算器上这是明显的 bug。
- **为什么自己写 shunting-yard 而不是递归下降**：token 列表天然是「数字运算符交替」的扁平结构，调度场算法实现更短、更好测。
- **为什么把引擎独立成类**：可以在没有 Android 设备的情况下用 `javac` + `java` 直接验证 28 条用例，构建与测试解耦。

## 签名

仓库内的 `keystore/calc-release.jks` 是自签名证书，密码 `calc123456`，用于个人分发。如果要上架应用商店，请替换成自己的正式证书。

证书指纹（SHA-256）：

```
f69e0049cf0a6672af86be43bfaf2d1236e69eb351f4a7a1c13515f6eef082f7
```

## 许可

MIT
