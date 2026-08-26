# 设备发现架构

## 设计原则

应用只收集能够直接证明设备 IP 或公开服务存在的只读证据。发现模块不建立 TCP 端口连通性检查；设备详情只提供公开协议元数据的按需识别与本地 OUI 辅助信息。

## 多网络边界

主页列出系统实际存在的 Wi‑Fi、个人热点、移动网络、以太网、VPN、蓝牙和其他网络。只有用户明确点击的 Wi‑Fi、以太网或热点网络会进入发现引擎；移动网络和 VPN 只用于状态展示。

| 网络场景 | 边界来源 | 发现流量 |
|---|---|---|
| Wi‑Fi / 以太网 | 对应的 `Network`、`LinkProperties`、IPv4 地址与 CIDR | mDNS 使用系统 NSD；SSDP 与后续受限读取绑定对应 `Network` |
| 个人热点 | Soft AP / tether 下游接口与 IPv4 地址 | SSDP 绑定热点本机 IPv4；读取同子网邻居缓存；mDNS 依赖系统 NSD 的可见性 |
| 移动网络 / VPN | 系统网络状态 | 不作为局域网发现目标 |

## 发现证据

| 来源 | 收集内容 | 作用 |
|---|---|---|
| 邻居 / ARP 缓存 | IP、MAC、接口 | 仅作为系统实际可读的邻居证据，不代表完整 DHCP 或热点客户端表 |
| mDNS / DNS-SD | 服务类型、主机、公开 TXT 字段 | 发现公开服务，并为后续公开型号声明提供证据 |
| SSDP / UPnP | `ST`、`USN`、`LOCATION`、`SERVER` | 发现 UPnP 服务并为详情页受限设备描述读取保存线索 |

`SERVER`、`ST`、`USN`、服务类别和 OUI 都不会直接作为设备名称或具体型号。设备名称优先使用 UPnP `friendlyName`、mDNS 主机名或协议公开厂商/型号字段；没有可靠公开名称时显示“未知设备”。

## 详情页型号识别

设备详情的“识别型号”只会依据已有 UPnP、IPP、mDNS 或 ONVIF / WS-Discovery 线索发起协议特定只读查询。ONVIF 必须由用户输入本次凭据；凭据不保存。结论固定为“已确认型号”“公开声明型号”“仅识别设备类别”或“未能确认型号”。

## 去重与诊断

`DeviceRegistry` 以 IP、MAC、UPnP UDN / USN 与 mDNS 服务线索合并同一设备。`DiscoveryDiagnostics` 使用 `LanDiscovery` 日志标签记录选定网络、接口、IPv4、CIDR、网关、VPN、多播锁、ARP、mDNS、SSDP 的原始观察与最终去重数量，便于解释设备可见性差异。
