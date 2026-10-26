# 计算器 · Calculator

一个干净、离线、无广告的 Android 计算器。纯 Java + Android 原生 View 实现，**零第三方依赖**，安装包仅 85 KB。

2.1.0 起界面改为**液态玻璃（Liquid Glass）+ 光线追踪（Ray Tracing）**：整个背景是一帧真实的 CPU 光线追踪渲染，键盘面板是一块有厚度、有倒角、有折射与色散的玻璃。

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
- **触摸辉光**：手指按下时玻璃表面泛起一团柔光
- **液态光泽**：一条极淡的高光带在玻璃上缓慢流动

## 液态玻璃 + 光线追踪

### 背景：CPU 光线追踪

`RayTracer` 是一个只依赖 `java.lang` 的小型路径求交器，场景里只有三样东西：

| 物体 | 几何 | 作用 |
| --- | --- | --- |
| 蓝色光板 | 竖直 AABB 面片 | 左侧竖向冷光，玻璃折射时最显眼的色块 |
| 暖色光球 | 球体 | 右上角暖色圆形高光，给画面一个焦点 |
| 白色灯条 | 水平 AABB 面片 | 顶部横向柔光，负责玻璃顶部边缘的亮线 |

地面用 `smoothstep` 与天空融合出地平线，避免硬边；灯光带柔和的衰减光晕。渲染结果经 ACES 色调映射 + sRGB gamma 编码。

相机在 `CAM_Z = 5.80`，46° 视场角。背景按 **1/2 分辨率**渲染后双线性放大——场景本身全是柔和的光，放大看不出差别，但耗时降到 1/4。

### 玻璃面板

`GlassRenderer` 逐像素做：

1. **圆角 SDF + 噪声轮廓**：`sdRoundRect` 算有符号距离场，再叠加两层低频值噪声，让边缘不是数学般完美的圆弧，而是有轻微起伏的液态轮廓。
2. **倒角法线**：SDF 数值梯度当作外法线，倒角带内法线逐渐从 `(0,0,1)` 转向侧壁，产生真实的厚度感。
3. **折射偏移**：偏移量随倒角强度增长（中心几乎不偏、边缘强烈弯折），并叠加一点「透镜收缩」，让边缘内容向面板内部卷曲。
4. **色散**：R/G/B 三通道用略微不同的折射率采样，玻璃边缘出现轻微彩虹边。
5. **磨砂分层**：中心清晰透过背景，只有倒角带才做模糊——这是液态玻璃与普通毛玻璃的核心区别。
6. **反射**：`R = reflect(IN, N)`，用倒角法线去采样环境贴图（环境贴图由 `renderEquirect` 生成，经 `EnvMap` 做 mip 与双线性插值），再按 Fresnel 加权。
7. **高光与边缘光**：Blinn-Phong 高光收敛在右上角，边缘按「朝光方向」做方向性亮线。
8. **乘法染色**：玻璃对背景做的是吸收而不是叠加，暗部不会被提亮。

### 性能策略

纯 CPU 逐像素渲染不可能实时，所以：

- 背景与玻璃位图**只在尺寸变化时重建一次**（`SceneView.requestScene` 逐帧比较宽高、面板矩形、圆角，全都相同就直接返回）
- 渲染跑在独立线程（`scene-build`，`Thread.NORM_PRIORITY - 1`），完成后 `post` 回主线程换图
- 逐帧绘制时只是两张 `drawBitmap`
- 触摸辉光与光泽带是 `Canvas` 上的 `RadialGradient` / `LinearGradient`，几乎零成本
- `onDetachedFromWindow` 里回收位图、取消所有回调

## 安装

1. 下载 [`release/Calculator-2.1.0.apk`](release/Calculator-2.1.0.apk)
2. 在手机上点开，允许「安装未知来源应用」
3. 安装完成后桌面会出现蓝色的「计算器」图标

> 支持 Android 5.0（API 21）及以上。已在 Android 16（vivo V2417A）上验证。

## 项目结构

```
calculator-android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/mapeople/calculator/
│   │   ├── CalcEngine.java       # 纯逻辑计算引擎（不依赖 Android API）
│   │   ├── RayTracer.java        # CPU 光线追踪：环境贴图 + 背景
│   │   ├── EnvMap.java           # 环境贴图采样（mip + 双线性）
│   │   ├── GlassRenderer.java    # 液态玻璃面板渲染
│   │   ├── ScenePainter.java     # 后台线程构建场景位图
│   │   ├── SceneView.java        # 自定义 View：绘制 + 辉光 + 光泽动画
│   │   └── MainActivity.java     # 界面与事件绑定
│   └── res/
│       ├── layout/activity_main.xml
│       ├── values/{strings,colors,styles}.xml
│       ├── drawable/bg_key_*.xml
│       └── mipmap-*/ic_launcher.png
├── app/src/test/java/.../EngineTest.java   # 纯 JVM 单元测试
├── preview/                     # 桌面预览工具（AWT 合成整屏，不需要手机）
├── build.sh                     # 无 Gradle 构建脚本
├── test.sh                      # 逻辑层测试脚本
├── release/Calculator-2.1.0.apk # 预构建安装包
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

产物：`build/Calculator-2.1.0.apk`

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

### 桌面预览（不需要手机）

`preview/AppPreview.java` 用 AWT 按 App 的真实尺寸合成一整屏，用来在装进手机之前检查观感：

```bash
javac -encoding UTF-8 -d /tmp/gp \
  app/src/main/java/com/mapeople/calculator/{RayTracer,EnvMap,GlassRenderer}.java \
  preview/AppPreview.java
java -cp /tmp/gp AppPreview /tmp/app
```

产物：`/tmp/app/app.png`

## 计算引擎设计

`CalcEngine` 把状态分成三块：

- `tokens` —— 已确认的数字与运算符（数字以十进制字符串保存，保留原始输入）
- `entry` —— 正在输入的数字
- `justEvaluated` / `error` —— 状态标记

求值走**调度场算法**（shunting-yard）转后缀表达式，`× ÷` 优先级高于 `+ −`，再用 `BigDecimal` 做十进制运算，最后 `stripTrailingZeros()` 去尾零、限制 10 位小数。

关键取舍：

- **为什么用 BigDecimal 而不是 double**：`0.1 + 0.2` 用 double 得 `0.30000000000000004`，计算器上这是明显的 bug。
- **为什么自己写 shunting-yard 而不是递归下降**：token 列表天然是「数字运算符交替」的扁平结构，调度场算法实现更短、更好测。
- **为什么把引擎独立成类**：可以在没有 Android 设备的情况下用 `javac` + `java` 直接验证用例，构建与测试解耦。

## 版本历史

| 版本 | 变更 |
| --- | --- |
| 2.1.0 | 精简功能线：移除历史记录/陀螺仪视差/崩溃日志，保留液态玻璃 + 光线追踪核心 |
| 2.0.7 | 崩溃日志、陀螺仪视差、液态光泽动画 |
| 2.0.0 | 重做界面：CPU 光线追踪背景 + 液态玻璃键盘面板 |
| 1.0.0 | 首个版本：纯 View 计算器，65 KB |

## 签名

仓库内的 `keystore/calc-release.jks` 是自签名证书，密码 `calc123456`，用于个人分发。如果要上架应用商店，请替换成自己的正式证书。

证书指纹（SHA-256）：

```
f69e0049cf0a6672af86be43bfaf2d1236e69eb351f4a7a1c13515f6eef082f7
```

## 许可

MIT
