# 移动热点客户端可见性与回退识别策略

## 截图差异结论

系统设置页显示的 `SKR-A0-heishayouxish` 及其 MAC 地址来自系统热点服务维护的关联客户端状态；应用页面显示“通过实际公开服务识别到 0 台客户端”，说明该客户端没有广播本应用当前查找的 mDNS/DNS-SD 服务，也没有响应 UPnP/SSDP。两种页面显示的是不同证据层级：前者是系统 Wi-Fi/热点控制面，后者是公开的应用层网络服务。

## Android 权限边界

AOSP 的 Soft AP 文档说明 `SoftApCallback.onConnectedClientsChanged` 可以提供已连接客户端及 MAC，`TetheringEventCallback` 可提供 IP 信息；但同一文档明确指出部分热点 API 是系统 API，并受权限限制，仅系统设置应用可访问。因此，普通第三方 APK 不能将系统设置中的完整热点客户端清单作为稳定能力依赖。[1]

Android 的公开 `TetheringManager` 文档提供热点状态、上游等回调，并不向普通应用公开通用的已连接客户端名单。[2]

## 应用回退策略

1. 继续以 mDNS、SSDP、UPnP 与已确认 IPP 服务作为“公开服务设备”的高置信度来源。
2. 在本机热点模式下，**只读尝试**读取系统邻居缓存 `/proc/net/arp`。若 OEM/Android 版本仍允许访问，并且缓存中存在热点子网中带完整 MAC 的条目，则显示为“本机邻居缓存观测到的热点设备”。
3. 此回退不发送 ARP 包、不使用 MAC-OUI 推断厂商、不承诺获得完整清单；若系统限制读取或邻居缓存没有条目，界面将明确说明“系统已连接设备列表仅系统设置可见”。
4. 设备总数按证据来源拆分：`已验证的热点邻居` 与 `公开服务设备`，避免将 0 个公开服务设备误解为 0 个连接客户端。

## 参考资料

[1] [Android Open Source Project: Wi-Fi hotspot (Soft AP)](https://source.android.com/docs/core/connect/wifi-softap)

[2] [Android Developers: TetheringManager](https://developer.android.com/reference/android/net/TetheringManager)
