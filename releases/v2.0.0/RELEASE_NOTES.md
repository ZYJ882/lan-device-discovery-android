# 局域网设备发现 2.0.0

## 安装包

| 项目 | 内容 |
|---|---|
| 文件 | `lan-device-discovery-v2.0.0-debug.apk` |
| applicationId | `com.zyj.lanobserver` |
| versionName | `2.0.0` |
| versionCode | `8` |
| minSdk | `26` |
| targetSdk | `35` |
| SHA-256 | `2ed0b8b838cbfb75d235a082c616f328160e86dc3640b5249dbf832bf6ba07f1` |

## 本版内容

本版本重构了局域网设备发现底层，不改变现有界面风格。扫描从当前实际 Wi‑Fi `Network`、LinkProperties、IPv4/CIDR 和网关开始，并将可控制的 UDP、TCP 和 UPnP HTTP 请求绑定到该网络，避免 VPN 或默认网络路由造成结果偏差。

发现源包括本机邻居缓存、mDNS/DNS-SD、SSDP/UPnP、UPnP device description XML、IPP 只读属性和既有固定服务探测。每条设备记录保留 IP、MAC（若可得）、hostname、协议字段、开放服务和发现来源，并使用 IP、MAC、mDNS 服务实例、SSDP USN、UPnP UDN 与序列号进行统一去重。

SSDP `SERVER` 字段仅作为详情和诊断信息。列表名称优先使用 UPnP `friendlyName`、mDNS hostname、公开厂商/型号和设备类型，无法确认时显示“未知设备”。

## 安装

```bash
adb install -r lan-device-discovery-v2.0.0-debug.apk
```

首次复测建议使用 `adb logcat -s LanDiscovery:I '*:S'` 查看当前 Wi‑Fi Network、多播锁、各发现源响应和最终去重统计；详见 [`docs/DISCOVERY_DEBUGGING.md`](../../docs/DISCOVERY_DEBUGGING.md)。
