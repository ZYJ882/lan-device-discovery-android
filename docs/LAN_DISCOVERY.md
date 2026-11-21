# 局域网设备发现与按需型号识别

## 概述

本应用用于在**已获授权**的家庭、办公或测试局域网中发现主动公开网络证据的设备。主页分别展示实际存在的 Wi‑Fi、个人热点、移动网络、以太网、VPN、蓝牙或其他网络；用户从具备本地 IPv4 边界的 Wi‑Fi、以太网或热点卡片点击“扫描此网络”后，应用仅在**该网络**上合并邻居缓存、mDNS/DNS-SD 与 SSDP/UPnP 服务证据。

> 默认发现不会发起 TCP 或 UDP 端口连通性检查，不会尝试登录、读取远程文件、执行远程命令或进行漏洞探测。

未显示设备不代表设备一定不在网络中。客户端隔离、防火墙、设备休眠、IPv6-only 网络、厂商未广播服务以及 Android/OEM 对邻居缓存的限制，都可能使普通应用没有可用证据。

## 多网络状态与独立扫描

Android 设备可以同时维护多个 `Network`。主页通过 `ConnectivityManager.allNetworks` 获取即时状态，并通过不限制传输类型的 `NetworkCallback` 监听网络可用、丢失、能力变化和链路属性变化。因此 Wi‑Fi 切换、热点开关、移动数据变化和 VPN 变化都会刷新网络卡片。[1]

| 网络类别 | 主页展示 | 可扫描性 | 扫描绑定 |
|---|---|---|---|
| Wi‑Fi | SSID（系统允许时）、IPv4/IPv6、网关、子网、DNS 与状态 | 有本地 IPv4 时可扫描 | 卡片对应的 `Network`、`LinkProperties` 与 Wi‑Fi 绑定套接字 |
| 个人热点 / 网络共享 | 本机下游接口 IP、子网与状态 | 可扫描 | 热点下游接口快照；热点没有公开 `Network` 句柄时，SSDP 绑定本机热点 IPv4 |
| 以太网 | IPv4/IPv6、网关、子网、DNS 与状态 | 有本地 IPv4 时可扫描 | 卡片对应的以太网 `Network` 与 `LinkProperties` |
| 移动网络 | 可获得的 2G/3G/4G/LTE/5G、运营商、IP 与状态 | 不扫描 | 仅状态展示，避免把运营商网络错误当作局域网 |
| VPN | 状态与接口信息 | 不扫描 | 仅状态展示；不作为局域网发现目标 |
| 蓝牙 / 其他 | 系统可见的状态与可获得链路字段 | 默认不扫描 | 仅状态展示 |

网络详情仅展示系统实际提供的字段。SSID 和运营商名称可能受版本、权限、OEM 与运营商策略影响；热点名称、完整关联客户端表、DHCP 租约和稳定 Wi‑Fi MAC 对普通第三方应用通常不可可靠取得，因此应用会直接隐藏缺失字段而不显示大量“未知”。[1] [2]

> “显示网络”不等于“扫描网络”。扫描目标绝不把 Wi‑Fi、热点、以太网、移动网络或 VPN 合并为系统默认网络；每次扫描明确使用用户点击卡片所对应的网络快照。

## 发现与操作边界

| 阶段 | 机制 | 行为 | 明确不做的事 |
|---|---|---|---|
| 默认发现 | 邻居 / ARP 缓存 | 只读当前接口中可获得的 IP、MAC 与接口记录 | 不将缓存视为完整 DHCP 或热点客户端表 |
| 默认发现 | mDNS / DNS-SD | 发现公开服务、服务主机与 TXT 字段 | 不基于服务类别、实例名或端口推测具体型号 |
| 默认发现 | SSDP / UPnP | 发送标准 `M-SEARCH`，记录 `ST`、`USN`、`LOCATION`、`SERVER` 等服务证据 | 不在主页读取 `LOCATION` 指向的 XML；不把 `SERVER` 当设备名称或型号 |
| 详情页“识别型号” | UPnP | 只对已发现的 `SSDP LOCATION`，读取同一响应 IP 的公开设备描述 | 不跨主机、不跟随重定向、不读取超过 64 KiB 的内容 |
| 详情页“识别型号” | IPP | 仅对已发现的 `_ipp._tcp` 主机、端口和 `rp` 路径发送 `Get-Printer-Attributes` | 不猜测路径、不提交打印任务、不处理认证或重定向 |
| 详情页“识别型号” | mDNS | 使用已收到的 TXT 公开声明 | 不将实例名、服务类别或端口伪装成具体型号 |
| 详情页“识别型号” | ONVIF | 仅在已有 ONVIF / WS-Discovery 地址且用户输入凭据后发送 `GetDeviceInformation` | 不猜测端点、不保存或复用凭据、不尝试默认密码 |

默认发现获得的 SSDP `LOCATION` 仅是后续可选、同一设备受限读取的地址线索。这样既保留公开服务发现，也确保详情页前没有型号相关的主动 HTTP 或 IPP 请求。

## 用户流程

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | 打开首页“网络状态” | 分别查看当前实际存在的 Wi‑Fi、个人热点、移动网络、以太网、VPN 或其他网络卡片 |
| 2 | 点击一张网络卡片的“网络详情” | 查看该网络可获得的 IPv4、IPv6、网关、子网掩码、CIDR、DNS、接口、互联网状态与扫描资格 |
| 3 | 在 Wi‑Fi、以太网或热点卡片点击“扫描此网络” | 只在该卡片对应的独立网络边界内合并邻居缓存、mDNS 与 SSDP 服务证据 |
| 4 | 点击已发现设备 | 查看 IP、MAC（如系统可提供）、公开服务与发现来源 |
| 5 | 点击“识别型号” | 仅按已有协议证据发起对应只读查询，并展示证据等级 |
| 6 | 如提示 ONVIF 凭据 | 仅本次输入用户名和密码，执行受限的 ONVIF `GetDeviceInformation` |
| 7 | 没有可扫描的 Wi‑Fi、以太网或热点网络 | 移动网络 / VPN 仍会显示状态；设备列表只显示本机设备，不伪造外部设备 |
| 8 | 点击首页设置 | 手动同步本地 OUI 注册表；不会在扫描时联网查询 MAC |

## 型号证据等级

设备详情不会从 MAC-OUI、系统客户端名、公开服务端口、HTTP `Server` 头或 SSDP `SERVER` 推断型号。界面将结果分为以下等级。

| 展示结果 | 允许的来源 | 含义 |
|---|---|---|
| **已确认型号** | IPP `printer-make-and-model`；受限同 IP 的 UPnP XML `modelName` / `modelNumber`；用户授权 ONVIF `GetDeviceInformation` 的 `Model` | 协议响应中存在明确型号字段 |
| **公开声明型号** | mDNS TXT 中的 `model`、`modelName`、`md`、`am`、`ty`、`usb_MDL` 等公开字段 | 设备在广播元数据中自我声明，未作跨协议确认 |
| **仅识别设备类别** | UPnP `deviceType`、mDNS 服务类型、ONVIF 已授权但无 `Model` | 可判断网络打印设备、媒体播放设备或网关等类别，不能确认具体型号 |
| **未能确认型号** | 没有可用公开字段，或只存在不可靠线索 | 诚实表示普通 Android 应用无法可靠获得型号 |

普通手机、未广播服务的 IoT 设备以及仅在系统热点客户端列表中可见的客户端，通常落入“未能确认型号”。这不是扫描遗漏的替代命名空间，而是 Android 应用在没有设备公开元数据或用户授权凭据时的真实边界。

## OUI 网卡厂商辅助信息

如设备的 ARP/邻居记录提供完整 MAC，详情页可本地显示“网卡厂商（OUI）”。OUI 数据来自 IEEE Registration Authority 发布的 MA-L（24 位）、MA-M（28 位）和 MA-S（36 位）公共 CSV；匹配按最长前缀优先。[3] 设置页中由用户手动触发下载，应用保留同步时间、来源与本地记录数；任一下载失败时保留旧数据库。

> OUI 是 **MAC 前缀的注册网卡厂商**，不是设备厂商，更不是具体型号。对于本地管理或随机 MAC（首字节的 U/L 位为 1），应用明确显示“不进行 OUI 匹配”。MAC 与 IP 不会上传到 IEEE 或任何第三方；扫描期间不会在线查询 OUI。

## 热点、VPN 与权限边界

热点模式下，Android 的默认网络可能仍为蜂窝网络，且部分机型在热点打开时仍同时保留 Wi‑Fi 上游网络。应用会枚举本机活动 IPv4 接口，优先识别名称符合 `softap`、`ap*`、`tether`、`rndis`、`usb` 或蓝牙共享模式的下游接口；一旦识别到共享下游接口，即优先把它作为发现边界，避免误将热点流量发往上游 Wi‑Fi。SSDP 套接字在热点模式显式绑定该本机 IPv4；mDNS 继续使用系统 NSD 服务，因此不同 Android/OEM 的热点多播可见性仍可能不同。[1]

普通应用不能访问系统完整 Soft AP 客户端控制表或 DHCP 租约；热点邻居缓存仅代表当前或近期通信记录，绝不等同于系统设置中显示的完整关联客户端列表。[2] 因此，应用只合并同子网可读邻居缓存、mDNS 与 SSDP 响应，不会以虚构设备替代真实客户端证据。

VPN 会作为独立状态卡片显示，并标注“VPN 网络通常不用于局域网设备发现”。当用户点击 Wi‑Fi、以太网或热点的扫描入口时，应用继续使用该卡片的实际网络快照绑定可控的多播、HTTP、IPP 与 ONVIF 流量，以减少 VPN 或中间层响应造成的误报。当前工程 `minSdk` 为 26、`targetSdk` 为 35。Android 官方说明，连接本地地址、发送/接收 UDP 单播、多播或广播以及使用 `NsdManager` 都属于本地网络访问；目标升级至 API 37 前应按官方要求声明并在运行时请求 `ACCESS_LOCAL_NETWORK`。[4]

## 参考资料

[1] [Android Developers：Read network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)

[2] [Android Open Source Project：Wi‑Fi hotspot (Soft AP)](https://source.android.com/docs/core/connect/wifi-softap)

[3] [IEEE Registration Authority：Public MA-L、MA-M、MA-S registries](https://regauth.standards.ieee.org/standards-ra-web/pub/view.html)

[4] [Android Developers：Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
