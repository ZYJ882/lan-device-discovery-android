# 局域网设备发现 2.0.1

## 安装包

| 项目 | 内容 |
|---|---|
| 文件 | `lan-device-discovery-v2.0.1-debug.apk` |
| applicationId | `com.zyj.lanobserver` |
| versionName | `2.0.1` |
| versionCode | `9` |
| minSdk | `26` |
| targetSdk | `35` |
| SHA-256 | `768fcd9281a3f78a7823fac0b82c6bc36e51c1ab3b0c5dde889d5199ebed612a` |

## 行为调整

主页“扫描设备”现在只负责识别设备 IP 和公开协议证据，不再对 IPv4 子网内任意地址建立 TCP 或 UDP 端口连接。默认发现来源为本机可读的 ARP/邻居缓存、mDNS/DNS-SD 与 SSDP/UPnP；端口号仅来自设备主动公开的服务元数据，不代表应用主动扫描。

用户打开一台已经发现的设备详情后，可点击“扫描端口”。该操作只会对当前选中设备的已发现 IPv4 地址检查固定的 14 个常见 TCP 服务端口，使用当前 Wi‑Fi `Network` 绑定套接字，不接受任意 IP、CIDR 或端口范围输入。

## 安装

```bash
adb install -r lan-device-discovery-v2.0.1-debug.apk
```

详细流程见 [`docs/IP_FIRST_DISCOVERY.md`](../../docs/IP_FIRST_DISCOVERY.md)。
