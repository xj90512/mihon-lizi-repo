# 栗子漫画 · Tachimanga

面向 Tachimanga 5.2 测试的栗子漫画扩展。订阅直接安装原生 JVM JAR；另提供 Android APK。
用户已在 iPhone 的 Tachimanga 5.2 确认仓库添加与扩展安装成功；登录后的正文阅读仍待实测。
1.6.4 修复 1.6.3 在手机打开源时的 `No registered instance or factory ... android.content.Context`，新版仍需手机复测。

## 安装

在 Tachimanga 的扩展仓库中添加下面的 GitHub Raw 地址（已在手机验证添加及安装成功）：

```text
https://raw.githubusercontent.com/xj90512/mihon-lizi-repo/main/index.min.json
```

GitHub Pages 上的同内容索引在本次手机测试中提示「插件仓库不存在」，因此不作为推荐订阅入口；Pages 继续提供安装包下载。

打开中文扩展列表，安装「栗子漫画」。这是 **Tachimanga 的 JAR 订阅**，不是 Android 的 APK 订阅。
如果商店安装仍有问题，可以下载 [JAR 文件](https://xj90512.github.io/mihon-lizi-repo/apk/tachiyomi-zh.lizi-v1.6.4.jar)，
再在 Tachimanga「浏览 → 扩展 → ＋」选择文件导入。
Android 用户可单独下载 [APK](https://raw.githubusercontent.com/xj90512/mihon-lizi-repo/main/apk/tachiyomi-zh.lizi-v1.6.4.apk)。

默认下载通过本仓库的 GitHub Pages 静态文件服务，不调用 APK 在线转换或 GitHub Releases API。
jsDelivr 当前对本仓库的 JAR 返回 403，因此不作为安装包下载地址。

不要把 APK 简单改名为 JAR：本仓库 JAR 内是真正的 Java 8 `.class`，带有效栈映射帧、AndroidManifest.xml 与图标资源。
旧版 APK 的转换或初始化错误，与 GitHub 的 403/429 下载限流是不同环节；本版本不能承诺修复所有 `Internal Server Error`。

## 浏览和阅读

- 热门、最新、搜索、详情和章节列表使用公开接口，不需要账号令牌。
- 正文使用官方 `chapter/v3` 返回的图片数组，保持官方顺序；在源设置的 Authorization 中填写你自己的账号令牌。
- 支持裸 JWT、`Jwt:` 和 `Bearer` 前缀。令牌只发送到 HTTPS 章节接口，不发送到公开元数据接口或图片 CDN。
- 账号的阅读时间、权限与额度仍由站方决定；不会推导受限正文地址，也不会自动重试消耗额度的章节请求。
- 图片首选线路可在源设置选择；仅图片失败时有限次数换线，遇到 429 停止换线。

## 1.6.4 的修复

- 参考 CopyManga 的实现，通过宿主注册的 `Application` 读取设置，同时兼容只注册 `Context` 的宿主。
- 保留 `source_6794114338793425275` 设置命名空间、授权键和源 ID。
- 回归测试复现了旧版在 Application-only 宿主上的相同异常，并验证修复后两种宿主均可读取设置。

## 1.6.3 的变化

- 从源码生成 JVM JAR，同时保留 APK；不依赖在线 DEX 转换服务。
- Kotlin/JVM 目标为 Java 8，关闭优化和资源裁剪，不在扩展中打包宿主已有的 Kotlin/OkHttp 等运行库。
- 补齐按接口路径与当前 Unix 秒生成的签名，并保留原查询参数。
- 首选 `https://mh.lizimh.net`。仅公开元数据在网络错误、403 或 5xx 时尝试旧 HTTP API；不会携带账号授权，也不会对 401/429 换域重试。
- 根据 `order` 输出章节，保持真实章节 ID；图片保留官方数组顺序，不按文件名排序。
- 清理历史图片主机和 `$jp$` / `$jp$cf` 标记后使用图片 CDN；按当前公开配置采用 `media.imgo.buzz` 与 `t1.imge.store`，替换已失效的历史域名，拒绝 HTML 错误页。
- 固定真实 source ID `6794114338793425275`；修正原商店索引中占位的 `id: 1`。
- 业务错误和非 JSON 响应显示具体接口路径，不输出账号令牌或带签名的完整 URL。

APK 使用这个 fork 独立的签名。若 Android 上已经安装原作者 APK，需要先卸载旧扩展后安装本 fork；操作前保留阅读器备份。
Tachimanga 的仓库添加及商店安装已通过手机测试；本地文件导入仍待实测。

## 构建与验证

需要 JDK 25 或更高版本、Android SDK 36 与 Build Tools 36.0.0。

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest assembleRelease createReleaseExtensionJar
python3 scripts/verify_package.py --build-only
```

单元测试覆盖签名、查询参数、令牌前缀、章节与图片顺序、错误响应、换线、凭证隔离，以及章节不重试和 429 不放大。
2026-10-29 本地验证：14 项测试通过；Suwayomi Server 2.4.2366 成功安装 JAR、加载源与设置，并取得热门、最新、搜索、详情、章节目录及 JPEG 封面。
随后用户通过上述 Raw 订阅在 iPhone 上成功添加仓库并安装 1.6.3；登录后的正文阅读尚未验证。
1.6.4 共 16 项单元测试，新增 Application-only / Context-only 两种宿主的设置读取测试。

正式 APK 签名使用环境变量 `LIZI_KEYSTORE`、`LIZI_STORE_PASSWORD`；可选 `LIZI_KEY_ALIAS`（默认 `lizi`）与
`LIZI_KEY_PASSWORD`（默认与 store password 相同）。没有正式密钥时仅使用本地 debug 签名做构建验证，不应将该 APK 发布到正式商店。

更新商店（从构建产物中的清单读取版本、包名，读取 APK 的实际签名指纹）：

```sh
python3 scripts/publish.py --repo xj90512/mihon-lizi-repo
python3 scripts/verify_package.py
```

`checksums.json` 记录发布的 JAR/APK 哈希。CI 只构建和验证，不自动发布、不读取账号授权。
更新版本时在 `build.gradle.kts` 增加 `versionCode`，同步 `versionName`，重新构建并生成索引。

## 来源

- Fork：[xpc010307/mihon-lizi-repo](https://github.com/xpc010307/mihon-lizi-repo)，原索引、APK 与图标。
- Kotlin 源码基于 [xpc010307/mihon-extension-lizi](https://github.com/xpc010307/mihon-extension-lizi)，基线 `e24ac88`；保留原作者署名。
- 签名、历史图片路径与章节顺序的协议处理参考 [Walter498/Walter](https://github.com/Walter498/Walter)，核对基线 `d178a49`。
  本仓库独立实现 Kotlin 协议逻辑，没有移植 Venera 专用宿主代码、评论系统或正文探测。
- 设置读取的宿主兼容方式参考 [LittleSurvival/copymanga-copy20](https://github.com/LittleSurvival/copymanga-copy20) 的 `CopyManga` 实现。
- 扩展结构参考 [Tachimanga 官方制作指南](https://tachimanga.app/help/guides/extensions_guide.html)；JAR 包结构参照
  [Keiyoushi](https://github.com/keiyoushi/extensions-source) 与 [Uchiyomi extension](https://github.com/AngeloSha/uchiyomi-extension) 的 JVM 分发方式。

本仓库与 Tachimanga、Mihon、栗子漫画站方均无隶属关系。
