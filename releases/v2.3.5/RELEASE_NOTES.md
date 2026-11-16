# 局域网设备发现 v2.3.5

## 本版重点

- 补全 Android 工程交付内容：包含顶层 Gradle 配置、Gradle Wrapper、Manifest、资源目录、许可证和完整 Kotlin 源码。
- 保持严格的无端口扫描边界：首页只使用 ARP/邻居缓存、mDNS/DNS-SD、SSDP/UPnP 等公开发现证据；未恢复端口扫描或在线监控页面。
- 将上一版源码包遗漏的工程文件纳入正式仓库与发布源码包。
- 移除不应进入正式交付物的身份识别研究草稿。
- 保持 Material 3、多网络卡片、热点识别、详情页按需型号识别和 OUI 本地同步功能。

## 构建信息

| 项目 | 值 |
|---|---|
| 包名 | `com.zyj.lanobserver` |
| 版本 | `2.3.5` |
| versionCode | `19` |
| 构建类型 | Debug，适用于受控测试环境 |
| 编译环境 | Android SDK 35、Java 17 |

APK 与源码包的 SHA-256 校验值由 GitHub Actions 在发布时生成，并随 `SHA256SUMS.txt` 附件提供。
