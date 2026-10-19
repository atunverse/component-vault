# ComponentVault · 元器件库存

安卓端的电子元器件库存管理应用。手机即服务端，电脑在同一局域网用浏览器就能管库存。

## 功能

| 模块 | 能力 |
|---|---|
| 快速录入 | 只输型号或代号，自动补全参数、封装、分类、功能标签与一句话简介 |
| 库存检索 | 按分类筛选 + 关键词检索（型号/封装/标签/仓位/内部编号），点击直接调整库存 |
| BOM 分析 | 粘贴 BOM（每行「型号,数量」），自动比对库存并标出齐备 / 不足 / 缺失 |
| 局域网管理 | 手机开前台服务，电脑浏览器访问 `http://手机IP:8080` 增删改查，含 REST API |
| 流水审计 | 每次数量变动都记 before / after / delta，带幂等键，重放不会重复扣减 |

## 补全是怎么做到的

不依赖网络也能出结果，靠的是**分层降级**：

1. **内置型号库** —— 约 35 个高频型号的完整参数（SS34、AO3400、AMS1117-3.3、STM32F103C8T6、CH340C 等）
2. **型号规则解析** —— 这部分是核心，纯本地推导：
   - 三位容码：`104` → 100nF、`103` → 10nF
   - 三位阻码：`103` → 10kΩ
   - 四位阻码：`1002` → 10kΩ
   - R 记法：`4R7` → 4.7Ω、`R47` → 0.47Ω
   - EIA-96：`01C` → 10kΩ（完整 96 码表）
   - 带单位：`10K` → 10kΩ、`100N` → 100nF、`4.7UH` → 4.7µH
   - 封装识别：`0603` → 0603 (1608)，以及 SOT-23 / SOP-8 / LQFP-48 / SMA 等
3. **立创商城开放平台** —— 填了 API Key 才启用，见下方说明

规则推断出的参数在界面上会标注「参数为规则推断值，请核对后再入库」。

### 关于立创商城

- 开放平台端点 `https://ips.lcsc.com/rest/wmsc2agent/search/product` 真实存在，无 Key 调用返回 `{"code":424,"message":"Key Is Required"}`
- 配额 **1000 次/日、200 次/分**，单页最多 30 条 —— 所以必须做缓存，不能每次输入都查
- 网页端（so.szlcsc.com）有瑞数 WAF 挑战，**不可爬取**，只能走开放平台

拿到 Key 后，在应用「我的 → 立创商城开放平台」填入即可。`enrich/RemoteLcsc.kt` 里留好了挂载点。

## 构建

本机不需要装 Android Studio，GitHub Actions 直接出包。

```bash
# 推送到 main 自动构建
git push origin main

# 产物在 Actions 的 Artifacts 里，或打 tag 生成 Release
git tag v0.1.0 && git push origin v0.1.0
```

本地构建（需 JDK 17 + Android SDK 35）：

```bash
./gradlew :app:assembleDebug
# 输出 app/build/outputs/apk/debug/app-debug.apk
```

CI 用 `keytool` 生成临时签名文件 `ci-debug.keystore`，不走正式签名。要正式发布请自备 keystore 并配置 `signingConfigs`。

## 技术栈

- Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.11.1
- Jetpack Compose + Material 3（BOM 2024.10.01）
- Room 2.6.1 + KSP（`part` / `txn` / `param_def` 三张表）
- Ktor 2.3.12 CIO 内嵌服务 + 前台 Service
- minSdk 26 / targetSdk 34 / compileSdk 35

## 安装到手机

1. 下载 `app-debug.apk`
2. 手机允许「安装未知来源应用」
3. 安装后首次启动会灌入 8 条示例数据

局域网功能需要手机和电脑在同一网段；连不上优先查路由器的 **AP 隔离**。

## 数据说明

器件参数为参考值，关键设计请以原厂数据手册为准。库存数据只存在本机 Room 数据库，不上传任何服务器。
