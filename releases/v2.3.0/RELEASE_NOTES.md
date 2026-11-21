# 局域网设备发现 v2.3.0

发布日期：2026-08-26

## 功能移除

根据产品范围调整，v2.3.0 已从应用中彻底删除所有端口连通性检查相关功能。删除范围包括设备详情入口、本机设备入口、前台在线监测、ViewModel 状态与协程任务、网络探测实现、回调接线，以及当前 README 和正式架构文档中的对应说明。

| 保留能力 | 当前行为 |
|---|---|
| 多网络状态 | 分别显示当前 Wi‑Fi、个人热点、移动网络、以太网、VPN、蓝牙与其他系统实际网络状态。 |
| 按网络发现 | 用户从可扫描的 Wi‑Fi、以太网或热点卡片发起发现；结果仅来自邻居缓存、mDNS/DNS-SD 与 SSDP/UPnP 的公开证据。 |
| 型号识别 | 详情页仍可按已有 UPnP、IPP、mDNS 或 ONVIF 证据进行受限的只读协议查询。 |
| OUI 辅助 | 仍可在设置中手动同步 IEEE OUI 数据库，并仅在本机对可得 MAC 作网卡注册厂商辅助匹配。 |

默认发现不会尝试登录、读取远程文件、执行远程命令或进行漏洞探测。历史 Release 的旧发行说明和 APK 会保留为既有版本记录；当前主分支源码与当前正式文档不再包含已移除功能。

## APK

| 文件 | 包名 | 版本 | SHA-256 |
|---|---|---|---|
| `lan-device-discovery-v2.3.0-debug.apk` | `com.zyj.lanobserver` | `2.3.0`（versionCode 14） | `11ea4dac1267f44f005196bf35a35c8d75a45b971de64f4fb4e5824ccf3f324f` |

该构建使用 Debug 签名，仅适用于受控测试环境。完整源码与当前架构说明见 `README.md`、[`docs/LAN_DISCOVERY.md`](../../docs/LAN_DISCOVERY.md) 和 [`docs/DISCOVERY_ARCHITECTURE.md`](../../docs/DISCOVERY_ARCHITECTURE.md)。
