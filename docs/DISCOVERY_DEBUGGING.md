# 局域网设备发现诊断与验证

## 适用版本

本说明对应当前多发现源版本。扫描不把 IPv4 子网中“可检查地址”的数量当成设备数量，也不会建立 TCP 或 UDP 端口连通性检查；它只记录 ARP/邻居、mDNS 和 SSDP/UPnP 的原始证据，再输出统一去重后的设备列表。

## 获取扫描日志

扫描期间，应用通过 Android Logcat 输出 `LanDiscovery` 标签。连接测试设备后可执行：

```bash
adb logcat -c
adb logcat -s LanDiscovery:I '*:S'
```

随后在应用中开始一次扫描。日志用于诊断，不包含密码、认证信息或远程内容；ARP 可用时会记录原始 MAC 用于本地去重，因此请不要在公开渠道提交完整日志。

## 每次扫描必须检查的日志

| 日志事件 | 用途 | 正常判断 |
|---|---|---|
| `scan.start` | 当前 Wi‑Fi Network、接口、IPv4、CIDR、网关、VPN、热点状态 | `network` 非空、接口与系统 Wi‑Fi 对应、IPv4 位于预期网段 |
| `multicast.lock` | Wi‑Fi 多播锁状态 | `acquired=true`；否则 mDNS/SSDP 响应可能被过滤 |
| `arp.cache` | 邻居缓存可读性和条目数量 | 缓存可读且有通信设备时应有条目；零条目不证明没有设备 |
| `source.response name=mDNS` | 每个 mDNS 服务类型发现的服务实例 | 仅代表服务广播，不是所有客户端 |
| `source.response name=SSDP` | SSDP 响应、USN、ST | 至少有响应时应随后见到 `upnpXml` 或明确 XML 请求失败原因 |
| `source.failure` | 发现启动、解析、网络或权限失败 | 用于区分权限、网络绑定和设备无响应 |
| `device.publish` | 去重后的设备状态 | 核对 IP、MAC、mDNS、SSDP USN/SERVER、UPnP 字段和来源 |
| `scan.finish` | 原始证据数、去重设备数和总耗时 | 对比不同网络或其他工具时的主要依据 |

## 验证步骤

先关闭或记录 VPN 状态，连接普通 Wi‑Fi 后扫描。确认 `scan.start` 中接口、IPv4、网关和 `network` 正确；若 default network 为蜂窝或 VPN，但本机连接 Wi‑Fi，仍必须选择 Wi‑Fi `Network`。然后确认 `multicast.lock acquired=true`，观察 mDNS/SSDP 的 `source.response` 计数，最后比较 `scan.finish` 的 `rawObservations` 与 `deduplicatedDevices`。

若某专业扫描工具显示更多 IP 而本应用的 ARP、mDNS 和 SSDP 均没有对应证据，这通常表示对方使用了额外的协议、拥有系统权限、读取了路由器/DHCP 数据，或采用了会造成误报的存活探测。本应用不会把这类未经验证的地址显示为设备。此时应提供完整 `LanDiscovery` 日志，以确认缺失发生在网络选择、多播接收、邻居缓存、服务广播或去重哪一层。

## 命名验证

SSDP `SERVER` 字段应只出现在设备详情和日志中。列表标题必须来自下列优先级：UPnP `friendlyName`、mDNS hostname、DHCP hostname（若平台可得）、厂商加型号、厂商加设备类型，最后为“未知设备”。例如当 XML 仅成功解析到 `manufacturer=TP-Link`、`modelName=TL-WA93` 时，列表应显示 `TP-Link TL-WA93`；当 XML 失败而 SSDP `SERVER` 为 `vxWorks/5.5 UPnP/1.0 ...` 时，列表仍应显示“未知设备”。

## Android 权限与兼容性

当前工程 target SDK 为 35，`INTERNET` 仍隐式允许本地网络 TCP/UDP；`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE` 和 `CHANGE_WIFI_MULTICAST_STATE` 支持网络选择与多播锁。Android 17（target SDK 37）起，本地 TCP、UDP、多播、mDNS、SSDP 和 `NsdManager` 会受 `ACCESS_LOCAL_NETWORK` 运行时权限约束；届时必须声明并请求该权限，或采用系统 NSD 选择器。[1]

[1] [Android Developers: Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
