# 局域网设备发现重构：审计基线

## 已确认的问题

当前发现引擎存在以下结构性问题，足以解释“同一 Wi‑Fi 下比专业扫描工具发现更少设备”和 SSDP 名称不友好的现象。

| 区域 | 当前行为 | 影响 |
|---|---|---|
| 当前网络选择 | 以 `activeNetwork` 为主，只有热点才扫描网卡候选 | VPN 成为默认网络或网络并存时，可能没有明确选择实际 Wi‑Fi `Network`、接口、IPv4 与路由 |
| 网络绑定 | UDP `DatagramSocket`、TCP `Socket`、UPnP XML 的 HTTP 连接均未绑定指定 Wi‑Fi `Network` | 发现流量可经默认网络/VPN 路由，导致响应缺失、错误网络或透明代理干扰 |
| mDNS | 使用 `NsdManager` 串行扫描 8 个服务类型，每类仅 900 ms | 总扫描耗时分散，服务解析回调可能在停扫后尚未返回；无法给出可靠的“收到/解析/失败”诊断统计 |
| SSDP | 发送标准 M-SEARCH，但未绑定 Wi‑Fi、套接字未在明确网络上监听 | 可能未从预期的 Wi‑Fi 接收 SSDP 响应 |
| SSDP 名称 | `friendlyName → modelName → server → ST` | 当 XML 无法访问或解析失败时，会把 `SERVER` 协议栈字符串显示为设备名 |
| IP 探测 | 只检查 6 个固定 TCP 端口，且扫描数被直接呈现 | 这是补充发现信号，不是“完整设备数”；大量手机和 IoT 不开放这些端口 |
| 邻居信息 | 只在热点模式读取 `/proc/net/arp` | 普通 Wi‑Fi 下缺少可读邻居/ARP 发现源，也没有读取诊断 |
| 去重 | 仅以第一个 IP 或临时 ID 合并 | 同设备的 IPv4、UPnP USN、mDNS 实例或 UUID 在地址缺失、变化和跨协议情况下无法可靠统一 |
| 诊断 | 没有结构化日志/每源计数 | 无法依据实际响应判断为何少发现，只能猜测 |

## 必须保留的边界

本次重构不增加扫描端口或修改页面视觉结构。设备存在性应来自邻居缓存、mDNS、SSDP/UPnP 或受控的现有服务探测；TCP 连接只能作为低置信度服务证据。`SERVER`、`ST`、开放端口和推测分类不得作为用户友好设备名称。

## 重构验收诊断

每次扫描必须在 Logcat 输出：网络句柄和 Wi‑Fi 接口、本地 IPv4、CIDR、网关、VPN 状态、多播锁获得状态；ARP/邻居、mDNS、SSDP、IP 探测的原始发现数量；以及最终统一设备数量。每个设备还要保留 IP、可得 MAC、hostname、mDNS 服务、SSDP USN/SERVER、UPnP XML 字段、服务端口和全部来源证据。

## Android 网络与权限调研依据

Android `Network` API 明确支持对未连接的 `DatagramSocket` 和 `Socket` 调用 `bindSocket()`，并支持以 `Network.openConnection(URL)` 让 HTTP 请求固定经该网络发送。因此，SSDP UDP、补充 TCP 探测和 UPnP 描述 XML HTTP 请求必须绑定到已选定的 Wi‑Fi `Network`，不能依赖默认网络或 `activeNetwork` 的隐式路由。[1]

Android 17（target SDK 37）开始，对本地 IP 的 TCP、UDP 单播/多播/广播通信和 `NsdManager` 等服务发现实施本地网络权限限制。当前 target SDK 35 仍由 `INTERNET` 隐式授予本地访问，但重构应预留 `ACCESS_LOCAL_NETWORK` 的声明和运行时请求分支，以便升级 target SDK 后不让 mDNS、SSDP 和 TCP 发现静默失败。[2]

[1] [Android Developers: Network](https://developer.android.com/reference/android/net/Network)

[2] [Android Developers: Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
