# 局域网设备发现

## 概述

本版本将应用入口调整为**局域网设备发现工作台**。应用在用户主动点击“开始发现设备”后，仅针对当前连接的 IPv4 局域网收集设备自行公开的服务信息，并在设备列表和详情页显示可用的基础元数据。扫描结果仅保存在内存中，不会上传、同步或写入账号资料。

> 本功能用于识别自己有权管理的家庭、办公或测试网络中的设备。它不用于绕过访问控制、登录设备、读取文件、执行远程命令或漏洞探测。

Android 的网络服务发现（NSD）基于 DNS-SD，可识别局域网中公开服务的设备；解析服务后可获得主机地址与端口。[1] Android 的网络状态接口可提供活动网络、链路地址、路由和网络传输类型，因此应用使用它来确定当前扫描边界。[2]

## 发现方式与结果范围

| 机制 | 实现内容 | 可显示的信息 | 边界 |
|---|---|---|---|
| mDNS / DNS-SD | 查找 HTTP、HTTPS、打印、AirPlay、Google Cast、SSH、SMB 等公开服务类型 | 服务实例、IP、端口、TXT 元数据、型号或厂商字段 | 仅发现主动广播相应 DNS-SD 服务的设备 |
| UPnP / SSDP | 发送标准 `M-SEARCH` 请求并接收短时间响应 | `SERVER`、`ST`、`USN`、`LOCATION` 等公开响应头 | 仅发现启用 UPnP 且允许多播响应的设备 |
| 常见服务连通性 | 对当前子网内地址进行低频 TCP 连通性检查 | IP、响应端口及基于服务的设备类别 | 仅检查 22、80、443、445、631、9100；不发送应用层请求 |
| 本机网络概览 | 读取当前活动网络的链路属性 | 本机 IPv4、网关、接口、实际 CIDR 与扫描 CIDR | 不读取 Wi‑Fi SSID、密码或定位数据 |

为避免在大型组织网络中产生过多连接，若当前网络比 `/24` 更大，应用只探测**本机所在的 `/24` 子网**。列表中的“未发现”不表示设备离线；客户端隔离、访客网络、防火墙、休眠、IPv6-only 网络和未公开服务都可能使设备不响应。

## 用户操作

| 步骤 | 操作 | 预期结果 |
|---|---|---|
| 1 | 将手机连接到待管理的 Wi‑Fi 或以太网 | 顶部卡片显示网络传输类型、本机 IPv4、网关与扫描范围 |
| 2 | 点击“开始发现设备” | 应用并行执行 mDNS、UPnP 与常见服务连通性检查 |
| 3 | 在“发现的设备”中按名称、IP、服务或设备类型筛选 | 列表动态显示已响应设备 |
| 4 | 点击任一设备卡片 | 查看 IP、主机名、发现方式、公开服务、响应端口及设备公开元数据 |
| 5 | 点击“停止扫描” | 立即停止后续扫描，并保留当前已发现的列表 |

## 权限与 Android 兼容性

当前工程的 `minSdk` 为 26，`targetSdk` 为 35。`INTERNET` 用于本地套接字通信；`ACCESS_NETWORK_STATE` 读取活动网络；Wi‑Fi 多播锁相关权限用于更可靠地接收 mDNS/SSDP 响应。Android 官方文档说明，连接本地地址、发送或接收 UDP 单播/多播/广播以及使用 `NsdManager` 都属于本地网络访问。[3]

| Android 目标版本 | 当前代码行为 | 维护要求 |
|---|---|---|
| API 26–35 | 使用现有 `INTERNET` 与网络状态权限，用户点击后开始扫描 | 在真实 Wi‑Fi、访客网络、热点与以太网环境中测试 |
| API 36 | 本地网络保护仍为开发者可选择测试的阶段 | 可按照官方指南启用兼容性开关进行回归测试 [3] |
| API 37 及以上 | 本地网络访问默认受运行时权限保护 | 升级 `targetSdk` 前，必须声明并在扫描前请求 `ACCESS_LOCAL_NETWORK`；若改为系统挑选单个 mDNS 设备，可采用系统服务选择器以减少广泛扫描权限需求 [3] |

## 构建与安装

项目需要 Android SDK 35 和 Java 17。以下命令生成 Debug APK：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

构建产物为 `app/build/outputs/apk/debug/app-debug.apk`。该 APK 使用 Debug 签名，仅适用于受控测试环境。发布前应使用独立 release keystore，并在真实网络中完成权限、隐私提示、设备兼容性和网络隔离测试。

## 参考资料

[1] [Android Developers：Use network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)

[2] [Android Developers：Read network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)

[3] [Android Developers：Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
