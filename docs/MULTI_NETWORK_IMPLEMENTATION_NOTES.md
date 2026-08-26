# 多网络主页实现依据

本次主页改造使用 Android `ConnectivityManager` 的网络枚举和回调能力。`Network` 代表一次具体连接；连接失效后不应复用该对象。`LinkProperties` 提供地址、路由、接口名和 DNS；`NetworkCapabilities` 提供传输类型与网络能力。[1]

应用通过 `ConnectivityManager.allNetworks` 获取即时快照，并注册不限定传输类型的 `NetworkCallback`。回调包含网络可用、丢失、能力变化和链路属性变化，因此 Wi‑Fi、移动网络、以太网、VPN 等状态发生变化后可刷新主页卡片。Android 官方建议在回调中依赖 `onCapabilitiesChanged` 与 `onLinkPropertiesChanged` 获得完整属性，而不是在 `onAvailable` 中立即同步查询，以避免竞态。[1]

网络卡片展示与扫描资格必须分开。所有已存在网络都可展示；只有存在 IPv4 局域网边界且可被应用作为发现目标的 Wi‑Fi、以太网或本机热点下游接口会显示独立扫描入口。移动网络与 VPN 只展示状态，不会作为局域网发现目标。扫描时继续把现有发现引擎绑定到用户选定的 `Network`，而非系统默认网络。

## 参考资料

[1] [Android Developers：Read network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)
