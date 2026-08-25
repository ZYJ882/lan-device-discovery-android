# 多发现源局域网设备发现架构

## 设计目标

扫描的目标不是把 `/24` 中的地址数量包装为设备数量，而是在明确选定的 Wi‑Fi `Network` 上收集多个相互独立的设备证据，并将这些证据合并为可解释的设备记录。每个设备都必须保留来源和原始协议字段，便于在 Logcat 中解释它为什么出现、如何命名以及是否可能重复。

## 网络上下文

`WifiNetworkContext` 由 `ConnectivityManager.getAllNetworks()` 选择具备 `TRANSPORT_WIFI`、有效 IPv4 `LinkProperties` 和本地路由的实际 Wi‑Fi 网络。它保留 `Network`、接口名、本机 IPv4、网关、实际 CIDR、扫描 CIDR 和 VPN 是否存在。所有可控制的网络操作都使用此 `Network`：

| 操作 | 绑定方式 |
|---|---|
| SSDP UDP | 新建未连接 `DatagramSocket` 后调用 `wifiNetwork.bindSocket(socket)` |
| TCP 服务探测 | 新建未连接 `Socket` 后调用 `wifiNetwork.bindSocket(socket)` |
| UPnP 描述 XML | `wifiNetwork.openConnection(url)` |
| IP 地址解析 | 对本地发现的响应地址直接使用；不通过默认网络 DNS 解析 |
| Android NSD/mDNS | 使用系统 `NsdManager` 和 Wi-Fi `MulticastLock`；记录请求、找到、解析、失败计数。当前公开 NSD API 没有通用的指定 `Network` 参数，因此不通过 VPN 默认网络重绑进程，只保留真实的系统 DNS-SD 发现证据。 |

## 发现源

| 来源 | 证据等级 | 设备键 | 可获得字段 |
|---|---:|---|---|
| 本机 Wi‑Fi 邻居缓存 | 中 | IPv4、MAC | IPv4、MAC、接口 |
| mDNS / DNS-SD | 高 | IPv4、服务实例、服务类型、hostname | IP、hostname、服务、TXT、厂商、型号 |
| SSDP / UPnP | 高 | IPv4、USN/UDN、LOCATION | USN、SERVER、ST、CACHE-CONTROL、XML 设备信息 |
| UPnP 描述 XML | 很高 | UDN、serialNumber、IPv4 | friendlyName、厂商、型号、设备类型、序列号 |
| 既有常见服务探测 | 低 | IPv4 | 已响应端口和服务特征 |

## 统一设备模型与去重

每个来源首先产生 `DeviceObservation`，其中的身份别名包括 IPv4、MAC、mDNS service identity、UPnP USN、UPnP UDN 和 serialNumber。`DeviceRegistry` 维护这些别名到稳定内部设备 ID 的索引；任何一个别名命中时合并记录，多别名命中时合并所有关联记录。IPv4 永远不是唯一的长期身份，但它是本次扫描的首选连接键。

冲突合并时，用户可见名称必须按证据优先级选择，而不是按到达顺序选择：

1. UPnP `friendlyName`；
2. mDNS hostname；
3. DHCP hostname（仅系统可用时）；
4. `manufacturer + modelName`；
5. UPnP `manufacturer + modelName`；
6. 厂商 + deviceType；
7. `未知设备`。

`SERVER`、`ST`、`USN`、端口扫描结果和服务推测只能进入详情/日志，不能作为名称。无 XML 身份信息的 SSDP 响应默认名称是“未知设备”，设备类型可从 `ST` 作为辅助提示。

## 扫描诊断

`DiscoveryDiagnostics` 在每次扫描中记录以下内容并使用 `Log.i("LanDiscovery")` 输出：Wi‑Fi Network、接口、IPv4、CIDR、网关、VPN、多播锁状态；每个来源的请求、原始响应、解析成功和失败数量；IP 服务响应数量；原始观察数和最终去重设备数。每条设备日志输出 IP、可得 MAC、hostname、mDNS 身份、SSDP USN/SERVER、UPnP 字段、端口和全部来源。

应用页面仅沿用既有来源标签和详情字段；本次重构不更改视觉布局。
