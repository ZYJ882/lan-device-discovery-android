# 局域网设备发现

## 概述

本版本将应用入口调整为**局域网设备发现工作台**。应用在用户主动点击“开始发现设备”后，仅针对当前连接的 IPv4 局域网收集设备自行公开的服务信息，并在设备列表和详情页显示可用的基础元数据。扫描结果仅保存在内存中，不会上传、同步或写入账号资料。

> 本功能用于识别自己有权管理的家庭、办公或测试网络中的设备。它不用于绕过访问控制、登录设备、读取文件、执行远程命令或漏洞探测。

Android 的网络服务发现（NSD）基于 DNS-SD，可识别局域网中公开服务的设备；解析服务后可获得主机地址与端口。[1] Android 的网络状态接口可提供活动网络、链路地址、路由和网络传输类型，因此应用使用它来确定当前扫描边界。[2]

## 发现方式与结果范围

| 机制 | 实现内容 | 可显示的信息 | 边界 |
|---|---|---|---|
| mDNS / DNS-SD | 查找 HTTP、HTTPS、打印、AirPlay、Google Cast、SSH、SMB 等公开服务类型 | 服务实例、IP、端口、TXT 元数据、公开型号或厂商字段 | 仅发现主动广播相应 DNS-SD 服务的设备 |
| UPnP / SSDP | 发送标准 `M-SEARCH` 请求并接收短时间响应；可读取同一响应地址的公开描述文档 | `SERVER`、`ST`、`USN`、`LOCATION`、friendlyName、manufacturer、modelName、modelNumber | 仅处理设备自己公开的 HTTP 描述文档；拒绝跨主机、重定向和超限内容 |
| IPP 只读属性 | 仅对已经通过 `_ipp._tcp` 发现的服务发起 `Get-Printer-Attributes` | `printer-make-and-model`、打印机名称、说明、UUID、位置和设备标识 | 不提交打印任务、不处理认证、不扫描任意端点；服务不公开时不查询 |
| 邻居 / ARP 缓存 | 只读当前网络接口可获得的邻居记录 | IP、MAC（若平台可提供）、接口与本地邻居证据 | 受 Android/OEM 限制；不能代表完整 DHCP 或热点关联客户端表 |
| 本机网络概览 | 读取当前活动网络的链路属性 | 本机 IPv4、网关、接口、实际 CIDR 与扫描 CIDR | 不读取 Wi‑Fi SSID、密码或定位数据 |
| 移动热点子网 | 优先识别本机私有 IPv4 热点网关接口，再使用 mDNS、SSDP 与已确认服务的只读型号查询 | 热点网关 IP、热点子网、响应设备的公开服务与型号 | 普通应用不读取系统 DHCP 客户端表；仅显示实际响应的热点设备 |

默认发现不对 IPv4 子网中的地址建立端口连接，因此不会把地址段大小当作设备数量。列表中的“未发现”不表示设备离线；客户端隔离、访客网络、防火墙、休眠、IPv6-only 网络和未公开服务都可能使设备不产生可用证据。

## 用户操作

| 步骤 | 操作 | 预期结果 |
|---|---|---|
| 1 | 将手机连接到待管理的 Wi‑Fi 或以太网 | 顶部卡片显示网络传输类型、本机 IPv4、网关与扫描范围 |
| 2 | 点击“开始发现设备” | 应用并行读取可用邻居缓存并执行 mDNS、UPnP/SSDP 公开服务发现；不扫描端口 |
| 3 | 在“发现的设备”中按名称、IP、服务或设备类型筛选 | 列表动态显示已响应设备 |
| 4 | 点击任一设备卡片 | 查看 IP、主机名、发现方式、公开服务、响应端口及设备公开元数据 |
| 5 | 点击“停止扫描” | 立即停止后续扫描，并保留当前已发现的列表 |
| 6 | 打开已发现设备的详情并点击“扫描 14 个常见端口” | 仅对该设备的已发现 IPv4 地址建立短时 TCP 连接，并列出响应端口 |
| 7 | 点击“开始在线监测” | 应用前台每 15 秒检查一次该设备的已知常见服务端口；可随时停止 |
| 8 | 开启本机移动热点后返回应用 | 顶部网络卡片显示“移动热点”，扫描结果分开报告邻居缓存观测与公开服务设备；两者都不等同于系统完整客户端列表 |

## 移动热点设备识别

当手机开启移动热点时，系统的默认网络通常仍为蜂窝数据，热点下游接口未必是应用的默认网络。本应用会枚举本机可见的私有 IPv4 接口，结合接口名称和当前蜂窝上游状态选择热点网关候选，并将扫描范围限定为该网关所属的 `/24` 子网。Android 的网络状态接口可提供网络、链路地址、路由和接口信息；应用不依赖固定的 `192.168.43.0/24` 地址段。[2]

Android 的热点系统可管理关联客户端，但完整的 Soft AP 客户端控制能力在一些设备和 API 上属于系统受限能力。因此，应用不尝试读取系统 DHCP 租约、客户端 MAC 地址或连接管理列表，而是通过设备自行公开的 mDNS、UPnP/SSDP 响应发现热点中的设备。[4] 在部分 OEM/Android 版本中，普通应用仍可只读访问系统邻居缓存 `/proc/net/arp`；本应用会在热点模式下尝试读取该缓存，并把同一热点子网中带完整链路地址的条目显示为“热点邻居缓存观测”。MAC 在界面中会掩码，且该缓存只表示当前或近期有通信的邻居，**不能等同于系统设置的完整关联客户端表**。若系统限制读取或客户端未进入缓存，应用明确报告不可用而不猜测。

默认发现阶段不再对任何子网地址执行 TCP `connect()`；它仅合并邻居缓存、mDNS 和 SSDP/UPnP 等能够直接提供设备 IP 的证据。端口连通性检查仅在用户进入已发现设备详情后手动执行。某台已经接入热点的设备如果未公开服务、未产生可读邻居缓存、被客户端隔离、处于休眠或受防火墙限制，可能不会显示；这不等于它没有连接热点。

## 设备名称、厂商与型号识别

应用只显示设备**主动公开**的身份信息。mDNS TXT 记录会规范化 `model`、`modelName`、`md`、`am`、`ty`、`usb_MDL`、`manufacturer`、`mfg` 与 `usb_MFG` 等常见字段；这些字段在界面中会标为“mDNS TXT 公开声明”。SSDP 的 `LOCATION` 若指向同一响应地址的 HTTP 描述文档，应用会在短超时和 64 KiB 大小限制内解析 `friendlyName`、`manufacturer`、`modelName`、`modelNumber`、`modelDescription` 与 `deviceType`，并标为“UPnP 描述公开声明”。[1] [4]

对于已经以 `_ipp._tcp` 广播确认的打印机服务，应用会向 mDNS 已解析的同一主机、端口和 `rp` 资源路径发送一次 IPP `Get-Printer-Attributes` 只读请求，读取 `printer-make-and-model` 等标准属性。查询不提交任务、不跟随重定向、不尝试认证，连接和读取均有短超时与 64 KiB 响应上限。结果标为“IPP 标准只读属性”。[7]

界面只把有 mDNS、UPnP 或 IPP 证据的字段称为“公开型号”；没有公开字段时显示“未发现设备公开的型号字段”。端口开放、服务名称、Banner 或通用设备类别只作为服务特征，绝不伪造为具体型号。

ARP 与 MAC-OUI 不是普通 Android APK 的可靠基础。Android 10 后，对 `/proc/net` 中 ARP 信息的访问受到平台限制，且 Android 10 及以上设备默认可使用随机 MAC 地址；随机 MAC 的厂商前缀不能反映真实硬件制造商。[5] [6] 因此本应用不收集 MAC 地址，也不以 OUI 数据库生成“厂商/型号”结论。

## 单设备端口扫描与在线监测

端口扫描只能从**当前发现列表中的单台设备详情**启动。实现使用固定的 14 个 TCP 服务端口：`21`、`22`、`23`、`53`、`80`、`443`、`445`、`554`、`631`、`1883`、`3389`、`8080`、`8443` 与 `9100`。每次扫描并发上限为 4 个连接，单端口连接超时为 420 毫秒。该功能不会扫描端口范围、不会接收自定义 IP 或主机名输入、不会发送协议载荷，也不会尝试认证。

在线监测在应用进程前台中运行，并且同一时刻只监测一台设备。系统每 15 秒按设备已发现端口和少量已知常见服务端口进行短时 TCP 连接检查。任一端口可建立连接时显示“在线”；未发现可连接端口时显示“未确认”，而不是“离线”。这是因为设备可能关闭了服务端口、处于防火墙或客户端隔离策略之后，或由于网络暂时拥塞而未响应。关闭详情页不会停止监测，用户须点击“停止在线监测”或重新开始全网发现；应用退出时监测自动停止。

> 端口扫描与在线监测只应在用户拥有或获得明确授权的本地网络中使用。结果仅反映 TCP 连通性，不应被视为设备安全评估或设备存在性的最终结论。

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

[4] [Android Open Source Project：Wi-Fi hotspot (Soft AP)](https://source.android.com/docs/core/connect/wifi-softap)

[5] [Google Issue Tracker：access to /proc/net/arp to resolve mac address of an IP address](https://issuetracker.google.com/issues/128554635)

[6] [Android Open Source Project：MAC randomization behavior](https://source.android.com/docs/core/connect/wifi-mac-randomization-behavior)

[7] [RFC 8011：Internet Printing Protocol/1.1 Model and Semantics](https://datatracker.ietf.org/doc/html/rfc8011)
