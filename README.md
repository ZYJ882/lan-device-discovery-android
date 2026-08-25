# 局域网设备发现（Android）

> 一个使用 **Kotlin、Jetpack Compose 和 Material 3** 构建的原生 Android 应用，用于在受授权的本地网络内识别主动响应的设备，并展示其公开的基础信息。

应用包名为 `com.zyj.lanobserver`，最低支持 Android 8.0（API 26），当前以 Android SDK 35 为编译和目标版本。用户主动开始扫描后，应用只在当前活动 IPv4 网络内进行发现；扫描结果仅存在于内存中，不上传、不同步，也不需要账号或服务器。

## 已实现功能

| 功能 | 说明 |
|---|---|
| 当前网络概览 | 显示网络传输类型、本机 IPv4、默认网关、实际 CIDR 和安全限制后的扫描范围 |
| mDNS / DNS-SD 发现 | 识别公开广播的 HTTP、HTTPS、打印、AirPlay、Google Cast、SSH 和 SMB 服务 |
| UPnP / SSDP 发现 | 读取设备主动回复的标准服务类型、服务器标识与描述地址，并解析同一设备公开的友好名称、厂商和型号 |
| IPP 打印机型号查询 | 只对 `_ipp._tcp` 已确认服务发送一次无认证、只读的标准属性查询，读取 `printer-make-and-model` |
| 常见服务连通性检查 | 仅在非热点、非 VPN 的普通局域网内检查少量常见 TCP 服务端口；结果只作为低置信度服务特征 |
| 设备列表与筛选 | 依名称、IP、公开服务、设备类型或厂商字段筛选已发现设备 |
| 设备详情 | 查看 IP、主机名、发现方式、服务、响应端口及设备自行公开的元数据 |
| 扫描控制 | 支持主动开始与停止扫描；停止后保留已经发现的结果 |
| 单设备端口扫描 | 仅对用户从发现列表选择的设备，检查 14 个固定常见 TCP 服务端口；不支持端口范围、任意目标或认证请求 |
| 前台在线监测 | 仅在应用前台中每 15 秒检查一次单个指定设备的已知常见服务端口，支持主动停止 |
| 移动热点识别 | 本机开启热点时，优先识别热点网关接口；在系统允许时读取同子网邻居缓存，并与 mDNS/UPnP 公开服务设备分开显示 |
| 公开型号识别 | 规范化 mDNS TXT 声明、读取同一 UPnP 设备公开描述，并查询已确认 IPP 打印机的标准型号属性；未公开时显示未识别 |

## 使用边界

本应用面向**自己拥有或明确获授权管理**的家庭、办公和测试局域网。它不执行身份验证尝试、不读取远程文件、不运行远程命令、不绕过访问控制，也不提供漏洞探测能力。端口扫描固定为 14 个常见 TCP 服务端口，且只能从已发现设备详情中启动；在线监测仅在应用前台中运行，不属于后台常驻服务。开启移动热点或检测到 VPN 时，应用会禁用 TCP 子网扫掠，避免代理或中间层造成虚假设备。热点模式下，应用在系统允许时只读同子网邻居缓存；该结果仅代表当前或近期通信邻居，不是系统设置的完整关联客户端列表。普通应用无法可靠读取系统 DHCP 客户端表，且 Android 的 MAC 随机化会降低 OUI 厂商识别的可靠性，因此设备型号仅来自设备主动公开的 mDNS/UPnP 元数据或已确认 IPP 服务的标准只读属性。网络隔离、设备休眠、防火墙或设备未公开服务时，设备可能不会显示；未显示不表示设备未连接。

为控制对大型网络的影响，应用会将比 `/24` 更大的 IPv4 网络限制为本机所在的 `/24` 子网进行常见服务连通性检查。关于扫描机制、Android 本地网络权限演进和维护建议，见 [`docs/LAN_DISCOVERY.md`](docs/LAN_DISCOVERY.md)。

## 构建与安装

环境要求为 **Android SDK 35、Java 17、Android 8.0（API 26）或更高版本**。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，采用调试签名，只适合受控测试环境。正式发布前请使用独立的 release keystore，并在真实 Wi‑Fi、访客网络、热点和以太网环境中完成权限、网络隔离和兼容性测试。

## 工程结构

```text
lan-device-discovery-android/
├── app/src/main/java/com/zyj/lanobserver/
│   ├── MainActivity.kt              # 应用入口与 Material 主题
│   ├── LanDiscoveryEngine.kt        # mDNS、SSDP 和常见服务发现引擎
│   ├── DeviceMonitoringEngine.kt    # 单设备端口扫描和在线检查引擎
│   ├── DeviceIdentityResolver.kt    # UPnP 公开厂商与型号解析器
│   ├── MdnsIdentityNormalizer.kt    # mDNS TXT 公开身份字段规范化
│   ├── IppIdentityResolver.kt       # 已确认 IPP 服务的只读型号查询
│   ├── LanDiscoveryViewModel.kt     # 扫描状态、网络回调与设备聚合
│   └── LanDiscoveryScreen.kt        # Compose 列表、筛选和详情界面
├── docs/
│   ├── LAN_DISCOVERY.md              # 发现边界、权限与兼容性说明
│   ├── HOTSPOT_IMPLEMENTATION_NOTES.md # 热点识别实现与平台边界说明
│   ├── HOTSPOT_CLIENT_VISIBILITY.md # 热点客户端可见性与缓存回退说明
│   ├── DEVICE_IDENTITY_RESEARCH.md  # ARP、OUI、mDNS、UPnP 与 DHCP 方案调研
│   └── MODEL_ENHANCEMENT_IMPLEMENTATION.md # 公开型号识别实现范围
└── app/build/outputs/apk/debug/      # Debug 构建产物
```

## 许可证

本仓库代码遵循 [MIT License](LICENSE)。
