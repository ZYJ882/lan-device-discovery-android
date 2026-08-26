# 局域网设备发现与按需型号识别

## 概述

本应用用于在**已获授权**的家庭、办公或测试局域网中发现主动公开网络证据的设备。用户点击“开始发现设备”后，应用仅在当前 IPv4 Wi‑Fi 或热点网络上合并邻居缓存、mDNS/DNS-SD 与 SSDP/UPnP 服务证据；默认发现阶段**不建立 TCP/UDP 端口连接，也不读取 UPnP XML 或 IPP 属性**。设备的型号读取是详情页中的独立、用户主动触发操作。

> 未显示设备不代表设备一定不在网络中。客户端隔离、防火墙、设备休眠、IPv6-only 网络、厂商未广播服务以及 Android/OEM 对邻居缓存的限制，都可能使普通应用没有可用证据。

Android 的网络服务发现基于 DNS-SD，解析服务可提供服务主机与端口；本应用据此处理用户设备主动广播的服务。[1] 活动网络、链路地址、路由和网络传输类型由 Android 网络状态接口提供，因此发现流量绑定到选定的 Wi‑Fi `Network`，而不是 VPN。[2]

## 发现与操作边界

| 阶段 | 机制 | 行为 | 明确不做的事 |
|---|---|---|---|
| 默认发现 | 邻居 / ARP 缓存 | 只读当前接口中可获得的 IP、MAC 与接口记录 | 不将缓存视为完整 DHCP 或热点客户端表 |
| 默认发现 | mDNS / DNS-SD | 发现公开服务、服务主机、端口与 TXT 字段 | 不基于服务端口猜测设备型号 |
| 默认发现 | SSDP / UPnP | 发送标准 `M-SEARCH`，记录 `ST`、`USN`、`LOCATION`、`SERVER` 等服务证据 | 不读取 `LOCATION` 指向的 XML；不把 `SERVER` 当设备名称或型号 |
| 详情页“识别型号” | UPnP | 只对已发现的 `SSDP LOCATION`，读取同一响应 IP 的公开设备描述 | 不跨主机、不跟随重定向、不读取超过 64 KiB 的内容 |
| 详情页“识别型号” | IPP | 仅对已发现的 `_ipp._tcp` 主机、端口和 `rp` 路径发送 `Get-Printer-Attributes` | 不猜测路径、不提交打印任务、不处理认证或重定向 |
| 详情页“识别型号” | mDNS | 使用已收到的 TXT 公开声明 | 不将实例名、端口或服务类别伪装成具体型号 |
| 详情页“识别型号” | ONVIF | 仅在已有 ONVIF / WS-Discovery 地址且用户输入凭据后发送 `GetDeviceInformation` | 不猜测端点、不保存或复用凭据、不尝试默认密码 |
| 详情页“扫描 14 个常见端口” | 单设备 TCP 连通性 | 仅检查已发现设备的固定端口列表 | 不扫描子网、端口范围或任意输入目标 |
| 无可扫描局域网时“检测本机端口” | 本机 TCP 连通性 | 仅检查界面已显示的本机 IPv4 固定端口列表 | 不扫描外部地址、不接受目标输入、不发送协议载荷 |

默认发现获得的 SSDP `LOCATION` 仅是后续可选、同一设备受限读取的地址线索。这样既保留了公开服务发现，也确保详情页前没有型号相关的主动 HTTP 或 IPP 请求。

## 用户流程

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | 连接目标 Wi‑Fi、以太网，或启用本机热点 | 顶部显示网络类型、本机 IPv4、网关与 CIDR |
| 2 | 点击“开始发现设备” | 合并邻居缓存、mDNS 与 SSDP 服务证据；不扫描端口、不读取设备描述 |
| 3 | 点击已发现设备 | 查看 IP、MAC（如系统可提供）、服务与发现来源 |
| 4 | 点击“识别型号” | 仅按已有协议证据发起对应只读查询，并展示证据等级 |
| 5 | 如提示 ONVIF 凭据 | 仅本次输入用户名和密码，执行受限的 ONVIF `GetDeviceInformation` |
| 6 | 如需连通性信息 | 手动点击“扫描 14 个常见端口”；该操作只针对当前设备 |
| 7 | 如需持续状态 | 开启前台在线监测；每 15 秒检查已知常见服务端口，可随时停止 |
| 8 | 没有可扫描的 Wi‑Fi 或热点网络时点击“开始发现设备” | 扫描入口始终保留；若仍无可扫描局域网，设备列表显示“本机设备”而不伪造外部设备 |
| 9 | 点击“本机设备” | 查看本机 IPv4、接口与地址范围；这些信息不会触发外部设备扫描 |
| 10 | 在“本机设备”详情中点击“检测本机 14 个常见端口” | 只检查当前显示的本机 IPv4；不会检查远程地址或端口范围 |
| 11 | 点击首页设置 | 手动同步本地 OUI 注册表；不会在扫描时联网查询 MAC |

## 型号证据等级

设备详情不会从 MAC-OUI、系统客户端名、开放端口、HTTP `Server` 头或 SSDP `SERVER` 推断型号。界面将结果分为以下等级。

| 展示结果 | 允许的来源 | 含义 |
|---|---|---|
| **已确认型号** | IPP `printer-make-and-model`；受限同 IP 的 UPnP XML `modelName` / `modelNumber`；用户授权 ONVIF `GetDeviceInformation` 的 `Model` | 协议响应中存在明确型号字段 |
| **公开声明型号** | mDNS TXT 中的 `model`、`modelName`、`md`、`am`、`ty`、`usb_MDL` 等公开字段 | 设备在广播元数据中自我声明，未作跨协议确认 |
| **仅识别设备类别** | UPnP `deviceType`、mDNS 服务类型、ONVIF 已授权但无 `Model` | 可判断如网络打印设备、媒体播放设备或网关等类别，不能确认具体型号 |
| **未能确认型号** | 没有可用公开字段，或只存在不可靠线索 | 诚实表示普通 Android 应用无法可靠获得型号 |

普通手机、未广播服务的 IoT 设备以及仅在系统热点客户端列表中可见的客户端，通常落入“未能确认型号”。这不是扫描遗漏的替代命名空间，而是 Android 应用在没有设备公开元数据或用户授权凭据时的真实边界。

## OUI 网卡厂商辅助信息

如设备的 ARP/邻居记录提供完整 MAC，详情页可本地显示“网卡厂商（OUI）”。OUI 数据来自 IEEE Registration Authority 发布的 MA-L（24 位）、MA-M（28 位）和 MA-S（36 位）公共 CSV；匹配按最长前缀优先。[3] 设置页中由用户手动触发下载，应用保留同步时间、来源与本地记录数；任一下载失败时保留旧数据库。

> OUI 是 **MAC 前缀的注册网卡厂商**，不是设备厂商，更不是具体型号。对于本地管理或随机 MAC（首字节的 U/L 位为 1），应用明确显示“不进行 OUI 匹配”。MAC 与 IP 不会上传到 IEEE 或任何第三方；扫描期间不会在线查询 OUI。

IEEE 公共注册表页面提示该下载存在频率限制，因此设置页不提供后台同步或扫描自动更新。[3]

## 热点、VPN 与权限边界

热点模式下，Android 的默认网络可能仍为蜂窝网络，且部分机型在热点打开时仍同时保留 Wi‑Fi 上游网络。应用会枚举本机活动 IPv4 接口，优先识别名称符合 `softap`、`ap*`、`tether`、`rndis`、`usb` 或蓝牙共享模式的下游接口；一旦识别到共享下游接口，即优先把它作为发现边界，避免误将热点流量发往上游 Wi‑Fi。SSDP 套接字在热点模式显式绑定该本机 IPv4；mDNS 继续使用系统 NSD 服务，因此不同 Android/OEM 的热点多播可见性仍可能不同。[2]

普通应用不能访问系统完整 Soft AP 客户端控制表或 DHCP 租约；热点邻居缓存仅代表当前或近期通信记录，绝不等同于系统设置中显示的完整关联客户端列表。[4] 因此，应用只合并同子网可读邻居缓存、mDNS 与 SSDP 响应，不会以 `/24` TCP 扫掠、虚构设备或端口响应替代真实客户端证据。

当没有可用于发现的 Wi‑Fi 或热点局域网时，“开始发现设备”入口仍始终保留。应用会从当前活动非回环 IPv4 接口生成设备列表中的“本机设备”，并在详情中显示本机地址、接口和 CIDR。该展示只用于诊断本机状态，**不会**把蜂窝、链路本地或其他接口变成扫描目标。用户只能在该本机设备详情中主动检查其固定 14 个端口；这是一项本机连通性检查，不会对任何外部设备建立连接。

检测到 VPN 时，应用选择实际 Wi‑Fi `Network` 绑定可控的多播、HTTP、IPP、ONVIF 与单设备端口扫描流量，以减少中间层响应造成的误报。当前工程 `minSdk` 为 26、`targetSdk` 为 35。Android 官方说明，连接本地地址、发送/接收 UDP 单播、多播或广播以及使用 `NsdManager` 都属于本地网络访问；目标升级至 API 37 前应按官方要求声明并在运行时请求 `ACCESS_LOCAL_NETWORK`。[5]

## 单设备端口扫描与在线监测

对已发现设备的端口扫描只能从设备详情页启动。固定端口为 `21`、`22`、`23`、`53`、`80`、`443`、`445`、`554`、`631`、`1883`、`3389`、`8080`、`8443` 与 `9100`，每端口只进行短时 TCP 连接，不发送协议载荷或认证请求。无可扫描局域网时，“本机设备”详情中的端口检测只复用这同一清单，并且目标只能是该设备显示的本机 IPv4。在线监测只在应用前台对一台已发现设备运行；没有响应时显示“未确认”，因为服务关闭、防火墙或网络隔离都可能造成无响应。

## 参考资料

[1] [Android Developers：Use network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)

[2] [Android Developers：Read network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)

[3] [IEEE Registration Authority：Public MA-L、MA-M、MA-S registries](https://regauth.standards.ieee.org/standards-ra-web/pub/view.html)

[4] [Android Open Source Project：Wi-Fi hotspot (Soft AP)](https://source.android.com/docs/core/connect/wifi-softap)

[5] [Android Developers：Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)

[6] [RFC 8011：Internet Printing Protocol/1.1 Model and Semantics](https://datatracker.ietf.org/doc/html/rfc8011)

[7] [ONVIF Core Specification：GetDeviceInformation](https://www.onvif.org/specs/core/ONVIF-Core-Specification.pdf)
