# 局域网设备发现 v2.3.1

发布日期：2026-08-26

## 修复内容

| 问题 | 修复结果 |
|---|---|
| 个人热点未显示为可发现网络 | 热点候选保留常见 Soft AP、USB 与蓝牙共享接口识别，并为未被系统网络占用的私有 IPv4 `wlan` / `wifi` 或非蜂窝、非隧道接口新增保守回退。热点仍只使用邻居缓存、mDNS 与 SSDP/UPnP 公开证据。 |
| 同一移动数据展示两张卡片 | 同一运营商下的 IPv4、IPv6、IMS 或数据承载网络合并为一张移动网络卡片；移动网络仍只展示状态，不会作为局域网发现目标。 |
| OUI 同步长时间没有明确结果 | OUI 下载不再使用当前选择的局域网发现网络，改为优先使用系统当前具备互联网能力的网络；设置页显示 MA-L、MA-M、MA-S 分阶段进度，单次同步总时限为 120 秒。 |
| OUI 同步失败难以判断 | 失败信息现在会显示 HTTP 状态、官方限流提示或超时原因，并以提示色展示；任何失败或超时均保留已有本地数据库。 |

## 平台与数据边界

Android API 35 及以下没有可作为必需路径的公开热点下游接口回调。若 OEM 未向普通应用暴露热点下游接口及 IPv4，应用无法读取系统完整热点客户端表，只能显示真实限制。当前回退规则不会把 `rmnet`、`ccmni`、`pdp`、`tun`、`ppp` 或 `vti` 等移动和隧道接口当作热点。

IEEE 官方注册表的 MA-L、MA-M、MA-S CSV 下载存在频率限制；请仅在需要时手动同步。同步不上传 MAC、IP 或设备信息，也不会在发现设备时联网查询 OUI。

## APK

| 文件 | 包名 | 版本 | SHA-256 |
|---|---|---|---|
| `lan-device-discovery-v2.3.1-debug.apk` | `com.zyj.lanobserver` | `2.3.1`（versionCode 15） | `b494b80b1b3b64e4bbdf741e344c3339ba45fc91daaec94a9042adb7e52589db` |

该构建使用 Debug 签名，仅适用于受控测试环境。完整源码和长期产品说明见 [`README.md`](../../README.md)，多网络实现细节见 [`docs/MULTI_NETWORK_IMPLEMENTATION_NOTES.md`](../../docs/MULTI_NETWORK_IMPLEMENTATION_NOTES.md)。
