# 多网络主页实现依据

本次主页使用 Android `ConnectivityManager` 的网络枚举和回调能力。`Network` 代表一次具体连接；连接失效后不应复用该对象。`LinkProperties` 提供地址、路由、接口名和 DNS；`NetworkCapabilities` 提供传输类型与网络能力。[1]

应用通过 `ConnectivityManager.allNetworks` 获取即时快照，并注册不限定传输类型的 `NetworkCallback`。回调包含网络可用、丢失、能力变化和链路属性变化，因此 Wi‑Fi、移动网络、以太网、VPN 等状态发生变化后可刷新主页卡片。Android 官方建议在回调中依赖 `onCapabilitiesChanged` 与 `onLinkPropertiesChanged` 获得完整属性，而不是在 `onAvailable` 中立即同步查询，以避免竞态。[1]

网络卡片展示与发现资格必须分开。所有已存在网络都可展示；只有存在 IPv4 局域网边界且可被应用作为发现目标的 Wi‑Fi、以太网或本机热点下游接口会显示独立扫描入口。移动网络与 VPN 只展示状态，不会作为局域网发现目标。发现时继续把现有发现引擎绑定到用户选定的 `Network`，而非系统默认网络。

## 移动数据承载合并

某些设备会将同一运营商的 IPv4、IPv6、IMS 或数据承载暴露为多个 `Network`。主页按运营商聚合这些蜂窝状态，并优先合并可得的 IPv4、IPv6、网关和 DNS，避免把同一移动数据错误显示为两张卡片。移动网络不作为局域网发现目标，即使其地址位于私有运营商网段。

## 热点下游接口识别

热点下游接口在 Android 16（API 35）及以下不一定有可供普通应用使用的公开 `Network` 句柄。应用因此枚举活动本机 IPv4 接口，优先识别 `softap`、`ap`、`tether`、`rndis`、`usb`、`bt-pan` 与 `bnep` 等常见共享接口名。

部分 OEM 使用非标准接口名。当前实现会对**未被已知系统网络占用**、且地址处于 RFC 1918 私有 IPv4 范围的 `wlan` / `wifi` 或其他非蜂窝、非 VPN、非隧道接口执行保守回退识别。此规则不会把 `rmnet`、`ccmni`、`pdp`、`tun`、`ppp` 或 `vti` 等移动和隧道接口误识别为热点。若 OEM 根本不向普通应用暴露热点下游接口及其 IPv4，应用只能诚实显示限制，无法替代系统热点客户端列表。

Android API 36 新增的 `TetheringEventCallback.onTetheredInterfacesChanged` 可以公开报告共享接口变化，但当前应用面向 API 35 编译，不能将该 API 作为必需路径；未来提升编译 SDK 后可将其作为优先证据。[2]

## OUI 下载网络选择

OUI 数据库同步与局域网发现是不同用途。局域网发现必须绑定用户选择的本地网络边界；OUI 下载则优先使用系统当前具备 `NET_CAPABILITY_INTERNET` 的网络，避免错误绑定到没有互联网出口的热点下游网络。同步过程依次下载 IEEE MA-L、MA-M、MA-S 官方 CSV，显示分阶段进度，单次总时限为 120 秒；失败或超时保留既有本地数据库，并显示 HTTP 状态或超时原因。

IEEE 明确说明公开注册表下载存在频率限制，用户应避免频繁手动同步。应用不上传 MAC、IP 或设备信息，也不会在设备发现过程中查询 OUI。[3]

## 参考资料

[1] [Android Developers：Read network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)

[2] [Android Developers：TetheringManager.TetheringEventCallback](https://developer.android.com/reference/android/net/TetheringManager.TetheringEventCallback)

[3] [IEEE Registration Authority：Public MAC assignment registries](https://regauth.standards.ieee.org/)
