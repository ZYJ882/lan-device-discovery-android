# 局域网设备发现（Android）

> 一个使用 **Kotlin、Jetpack Compose 和 Material 3** 构建的原生 Android 应用，用于在受授权的本地网络内发现主动响应的设备，并在用户进入详情后按需读取公开的身份信息。

应用包名为 `com.zyj.lanobserver`，最低支持 Android 8.0（API 26），当前以 Android SDK 35 为编译和目标版本。用户主动扫描后，设备列表只保留在内存中；应用不要求账号，也不会上传扫描结果、IP 或 MAC 地址。

## 2.1.2 主要功能

| 功能 | 说明 |
|---|---|
| **IP 与公开服务优先发现** | 首页只合并 ARP/邻居缓存、mDNS/DNS-SD 和 SSDP/UPnP 公开服务证据；不会对子网进行端口扫描，也不会在首页读取设备 XML 或 IPP 属性 |
| **详情页手动端口扫描** | 仅对已发现的单台设备检查固定 14 个常见 TCP 服务端口；不支持端口范围、任意目标或认证请求 |
| **详情页“识别型号”** | 仅依据已有 UPnP、IPP、mDNS 或 WS-Discovery 证据发起对应的只读查询；不把 OUI、端口、HTTP `Server` 或 SSDP `SERVER` 作为型号 |
| **三档识别结论** | 显示“已确认型号”“公开声明型号”“仅识别设备类别”；没有可靠公开字段时明确显示“未能确认型号” |
| **ONVIF 授权查询** | 已发现 ONVIF / WS-Discovery 地址时，先要求用户输入本次使用的凭据，再发送只读 `GetDeviceInformation`；凭据不保存、不复用 |
| **OUI 本地厂商辅助** | 首页右上角设置可手动同步 IEEE MA-L、MA-M、MA-S 注册表。最长前缀匹配仅在本机运行；随机/本地管理 MAC 不匹配，OUI 不代表设备厂商或型号 |
| **前台在线监测** | 仅在应用前台每 15 秒检查一台已发现设备的已知常见服务端口，可主动停止 |
| **热点下游局域网优先** | 热点打开时优先识别 Soft AP / 网络共享下游 IPv4 接口，即使手机同时保留 Wi‑Fi 上游网络也不会误把发现流量优先发向上游；热点中仍只采用可读邻居缓存与公开服务证据 |
| **无局域网本机设备** | 没有可扫描的 Wi‑Fi 或热点网络时，本机 IPv4、接口与 CIDR 会作为设备列表中的“本机设备”显示；这不会把蜂窝或链路本地地址变成外部扫描目标 |
| **本机固定端口检测** | 用户进入“本机设备”详情后，才可手动检查当前显示的本机 IPv4 的同一 14 个固定常见端口；不检查任何外部地址、端口范围或用户输入目标 |
| **VPN 边界** | 可控网络请求绑定实际 Wi‑Fi `Network`，避免 VPN 或代理引发误报 |

## 使用边界

本应用面向**自己拥有或明确获授权管理**的家庭、办公和测试局域网。它不执行默认凭据尝试、不读取远程文件、不运行远程命令、不绕过访问控制，也不提供漏洞探测。普通手机、未广播服务的 IoT 设备以及仅在系统热点客户端表中可见的客户端，普通 Android 应用无法可靠识别具体型号；未显示设备不代表设备未连接。

> IEEE OUI 仅描述 MAC 前缀的**注册网卡厂商**。它不能确认设备厂商，更不能确认设备型号。完整 MAC 不会上传；扫描期间不会在线查询 OUI。

详细的发现流、型号证据等级、ONVIF 凭据边界、OUI 同步和 Android 权限说明见 [`docs/LAN_DISCOVERY.md`](docs/LAN_DISCOVERY.md) 与 [`docs/OUI_DATABASE.md`](docs/OUI_DATABASE.md)。

## 构建与安装

环境要求为 **Android SDK 35、Java 17、Android 8.0（API 26）或更高版本**。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，采用调试签名，只适合受控测试环境。正式发布前应使用独立 release keystore，并在真实 Wi‑Fi、访客网络、热点和以太网环境中完成权限、网络隔离和兼容性测试。

## 工程结构

```text
lan-device-discovery-android/
├── app/src/main/java/com/zyj/lanobserver/
│   ├── LanDiscoveryEngine.kt        # 热点下游优先的 IP/公开服务发现引擎
│   ├── DeviceModelResolver.kt        # 详情页按需 UPnP、IPP、mDNS、ONVIF 识别
│   ├── OuiDatabase.kt                # 本地 IEEE OUI 同步与最长前缀匹配
│   ├── DeviceMonitoringEngine.kt     # 单设备与本机固定端口检测、在线检查
│   ├── LanDiscoveryViewModel.kt      # 扫描、识别与同步状态
│   └── LanDiscoveryScreen.kt         # 首页、设置和详情界面
├── docs/
│   ├── LAN_DISCOVERY.md              # 发现与型号识别边界
│   └── OUI_DATABASE.md               # IEEE OUI 数据库与隐私说明
└── releases/                          # 已发布 Debug APK 与发行说明
```

## 许可证

本仓库代码遵循 [MIT License](LICENSE)。
