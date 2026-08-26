# 局域网设备发现（Android）

> 使用 **Kotlin、Jetpack Compose 与 Material 3** 构建的原生 Android 局域网设备发现工具。它面向自己拥有或明确获授权管理的家庭、办公和测试网络，帮助查看网络状态、发现公开设备证据，并在设备详情中读取公开协议身份信息。

应用包名为 `com.zyj.lanobserver`，最低支持 Android 8.0（API 26），以 Android SDK 35 编译和定位。应用不要求账号；设备列表仅保留在本次运行的内存中，扫描结果、IP 和 MAC 不会上传。

## 软件能力

| 能力 | 说明 |
|---|---|
| **多网络状态** | 分别展示系统实际存在的 Wi‑Fi、个人热点、移动网络、以太网、VPN、蓝牙与其他网络，并在网络状态变化后刷新。 |
| **按网络发现** | 用户从具备本地 IPv4 边界的 Wi‑Fi、以太网或热点卡片启动发现；发现边界与所选网络绑定，不混合多个网段。 |
| **IP 与公开服务证据** | 合并 ARP / 邻居缓存、mDNS / DNS-SD 与 SSDP / UPnP 公开服务证据，生成可解释、可去重的设备列表。 |
| **热点下游优先** | 热点开启时优先识别 Soft AP / 网络共享下游 IPv4 接口，避免误将发现流量优先发向 Wi‑Fi 上游或默认移动网络。 |
| **网络详情** | 查看系统可提供的 IPv4、IPv6、网关、子网掩码、CIDR、DNS、接口、互联网状态与发现资格；不可获得字段自动隐藏。 |
| **按需型号识别** | 设备详情仅依据已有 UPnP、IPP、mDNS 或 ONVIF / WS-Discovery 证据，执行受限的只读协议查询。 |
| **可信度分级** | 型号结果明确显示为“已确认型号”“公开声明型号”“仅识别设备类别”或“未能确认型号”。 |
| **OUI 本地辅助** | 设置中可手动同步 IEEE MA-L、MA-M、MA-S 注册表；最长前缀匹配仅在本机完成，用于网卡注册厂商辅助说明。 |

## 使用边界

应用只在用户主动选择的局域网中读取公开发现证据。它不尝试默认凭据、不绕过访问控制、不读取远程文件、不执行远程命令，也不提供漏洞探测。

普通手机、未广播服务的 IoT 设备，以及仅出现在系统热点客户端表中的设备，普通 Android 应用可能无法获得足够公开证据；未显示设备不代表设备一定未连接。系统限制、客户端隔离、防火墙、设备休眠和 OEM 网络策略都可能影响可见性。

> IEEE OUI 仅说明 MAC 前缀的**注册网卡厂商**，不能确认设备厂商或具体型号。对于随机或本地管理 MAC，应用不进行 OUI 匹配；完整 MAC 不会上传，也不会在发现过程中在线查询。

## 使用方式

| 步骤 | 操作 |
|---|---|
| 1 | 在首页查看当前网络状态，并选择具备本地 IPv4 的 Wi‑Fi、以太网或个人热点网络。 |
| 2 | 点击“扫描此网络”，查看由邻居缓存、mDNS 和 SSDP / UPnP 公开证据合并得到的设备。 |
| 3 | 点击设备，查看 IP、公开服务、来源、可获得的 MAC 与协议公开字段。 |
| 4 | 如需型号信息，点击“识别型号”；若设备要求 ONVIF 凭据，仅在本次操作中输入。 |
| 5 | 如需网卡注册厂商辅助信息，在右上角设置中手动同步本地 OUI 数据库。 |

## 文档与版本记录

稳定的发现架构、型号识别边界、OUI 数据库隐私规则和多网络实现说明位于以下文档：

- [`docs/LAN_DISCOVERY.md`](docs/LAN_DISCOVERY.md)：发现流程、网络边界、协议身份识别与权限说明。
- [`docs/DISCOVERY_ARCHITECTURE.md`](docs/DISCOVERY_ARCHITECTURE.md)：发现来源、去重、热点与诊断架构。
- [`docs/OUI_DATABASE.md`](docs/OUI_DATABASE.md)：IEEE OUI 数据库、匹配规则和隐私边界。
- [`docs/MULTI_NETWORK_IMPLEMENTATION_NOTES.md`](docs/MULTI_NETWORK_IMPLEMENTATION_NOTES.md)：多网络状态和按网络发现绑定实现。

每个版本的新增、修改、移除项、安装包校验值与已知边界，统一记录在仓库的 [`GitHub Releases`](https://github.com/ZYJ882/lan-device-discovery-android/releases) 和对应的 `releases/vX.Y.Z/RELEASE_NOTES.md` 中；README 不按版本维护功能清单。

## 构建与安装

环境要求为 **Android SDK 35、Java 17、Android 8.0（API 26）或更高版本**。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，采用调试签名，只适合受控测试环境。发布版本会同时在仓库 `releases/` 目录和 [GitHub Releases](https://github.com/ZYJ882/lan-device-discovery-android/releases) 提供源码对应的安装包与发行说明。

## 工程结构

```text
lan-device-discovery-android/
├── app/src/main/java/com/zyj/lanobserver/
│   ├── LanDiscoveryEngine.kt        # 多网络状态与 IP / 公开服务发现引擎
│   ├── DeviceModelResolver.kt       # 详情页按需 UPnP、IPP、mDNS、ONVIF 识别
│   ├── OuiDatabase.kt               # 本地 IEEE OUI 同步与最长前缀匹配
│   ├── LanDiscoveryViewModel.kt     # 网络、发现、识别与同步状态
│   └── LanDiscoveryScreen.kt        # 首页、设置、网络详情和设备详情界面
├── docs/                            # 长期架构与使用边界说明
└── releases/                        # 各版本 APK 与发行说明
```

## 许可证

本仓库代码遵循 [MIT License](LICENSE)。
