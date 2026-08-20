# 热点识别实现说明

Android 的 Soft AP（移动热点）能力支持已关联客户端信息，但部分完整控制接口仅提供给系统设置等受限应用；普通应用不应依赖这些受限接口来枚举 MAC 或直接读取 DHCP 租约。[1]

本应用采用兼容普通 Android 应用的发现路径：在用户主动扫描时，读取本机可见的私有 IPv4 接口地址，识别可能的热点网关接口，并仅在该接口所属的 IPv4 `/24` 子网内执行既有的 mDNS、UPnP 与少量常见 TCP 服务连通性检查。扫描不依赖固定的 `192.168.43.0/24` 网段，因此可兼容厂商使用不同热点地址段的情形。

Android 的 `LinkProperties` 可提供本地 IP、路由和接口名；同时设备可能保有多个网络，默认网络并不一定代表热点下游接口。因此实现会将活动网络快照与私有 IPv4 网络接口枚举合并，优先使用具有私有网关地址且非移动数据、非 VPN、非回环的接口作为热点候选。[2]

热点模式的设备结果仍来自设备响应的公开网络信号。由于客户端隔离、防火墙、设备休眠、不开启公开服务或厂商限制，已接入热点的设备可能不出现在应用列表；未发现并不等于未连接。应用不进行登录、ARP 表读取、MAC 地址追踪、任意端口范围扫描或漏洞探测。

## 参考资料

[1] [Android Open Source Project: Wi-Fi hotspot (Soft AP)](https://source.android.com/docs/core/connect/wifi-softap)

[2] [Android Developers: Read network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)
