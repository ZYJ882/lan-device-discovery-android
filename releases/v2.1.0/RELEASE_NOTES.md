# 局域网设备发现 v2.1.0

发布日期：2026-08-25

## 本次更新

v2.1.0 在不改变“首页先发现 IP 与公开服务证据、设备详情再手动扫描端口”的流程前提下，新增了详情页手动型号识别与首页 OUI 数据库设置。

| 项目 | 说明 |
|---|---|
| 严格按需型号识别 | 首页默认发现只读取邻居缓存、mDNS/DNS-SD 与 SSDP 服务证据；不再自动读取 UPnP XML 或发起 IPP 属性查询 |
| 协议特定识别 | 详情页“识别型号”仅依据已发现的 UPnP、IPP、mDNS 或 ONVIF / WS-Discovery 线索执行受限、只读查询 |
| 三档结论 | 明确展示“已确认型号”“公开声明型号”“仅识别设备类别”；没有可用公开元数据时显示“未能确认型号” |
| ONVIF 凭据边界 | 仅当已有 ONVIF / WS-Discovery 地址且用户输入本次凭据后请求 `GetDeviceInformation`；不保存、不复用、不猜测凭据 |
| OUI 手动同步 | 首页右上角设置支持从 IEEE MA-L、MA-M、MA-S 公共 CSV 手动同步本地数据库；最长前缀优先，失败保留旧库 |
| OUI 隐私与准确性 | MAC 匹配只在本机进行；随机/本地管理 MAC 不匹配。OUI 仅是网卡厂商辅助信息，绝不作为设备厂商或具体型号 |

## 保持不变的行为

首页不会进行子网 TCP/UDP 端口扫描，也不会因为开放端口、HTTP `Server`、SSDP `SERVER`、系统客户端名或 MAC-OUI 推断型号。端口检查仍只可由用户在已发现设备详情中针对该设备主动启动，固定检查 14 个常见 TCP 服务端口。

## APK

| 文件 | 包名 | 版本 | SHA-256 |
|---|---|---|---|
| `lan-device-discovery-v2.1.0-debug.apk` | `com.zyj.lanobserver` | `2.1.0`（versionCode 10） | `9580a8c20b3631f04d79534b0d47f637eefbf006a23858259f2c24d1133d09d5` |

该构建使用 Debug 签名，仅适用于受控测试。安装前请确认手机允许来自受信任来源的测试安装包；正式生产分发应改用独立 release keystore。

详细行为说明见 [`docs/LAN_DISCOVERY.md`](../../docs/LAN_DISCOVERY.md) 与 [`docs/OUI_DATABASE.md`](../../docs/OUI_DATABASE.md)。
