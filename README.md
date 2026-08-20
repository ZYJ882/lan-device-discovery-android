# 局域网设备发现（Android）

> 一个使用 **Kotlin、Jetpack Compose 和 Material 3** 构建的原生 Android 应用，用于在受授权的本地网络内识别主动响应的设备，并展示其公开的基础信息。

应用包名为 `com.zyj.lanobserver`，最低支持 Android 8.0（API 26），当前以 Android SDK 35 为编译和目标版本。用户主动开始扫描后，应用只在当前活动 IPv4 网络内进行发现；扫描结果仅存在于内存中，不上传、不同步，也不需要账号或服务器。

## 已实现功能

| 功能 | 说明 |
|---|---|
| 当前网络概览 | 显示网络传输类型、本机 IPv4、默认网关、实际 CIDR 和安全限制后的扫描范围 |
| mDNS / DNS-SD 发现 | 识别公开广播的 HTTP、HTTPS、打印、AirPlay、Google Cast、SSH 和 SMB 服务 |
| UPnP / SSDP 发现 | 读取设备主动回复的标准服务类型、服务器标识与描述地址等公开元数据 |
| 常见服务连通性检查 | 仅检查 22、80、443、445、631、9100 等少量常见 TCP 服务端口，不发送登录或应用层请求 |
| 设备列表与筛选 | 依名称、IP、公开服务、设备类型或厂商字段筛选已发现设备 |
| 设备详情 | 查看 IP、主机名、发现方式、服务、响应端口及设备自行公开的元数据 |
| 扫描控制 | 支持主动开始与停止扫描；停止后保留已经发现的结果 |

## 使用边界

本应用面向**自己拥有或明确获授权管理**的家庭、办公和测试局域网。它不执行身份验证尝试、不读取远程文件、不运行远程命令、不绕过访问控制，也不提供漏洞探测能力。网络隔离、设备休眠、防火墙或设备未公开服务时，设备可能不会显示；未显示不表示设备不存在。

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
│   ├── LanDiscoveryViewModel.kt     # 扫描状态、网络回调与设备聚合
│   └── LanDiscoveryScreen.kt        # Compose 列表、筛选和详情界面
├── docs/
│   └── LAN_DISCOVERY.md              # 发现边界、权限与兼容性说明
└── app/build/outputs/apk/debug/      # Debug 构建产物
```

## 许可证

本仓库代码遵循 [MIT License](LICENSE)。
