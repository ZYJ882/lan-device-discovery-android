# OUI 网卡厂商数据库

## 目的与边界

本应用将 OUI 用作设备详情中的**网卡厂商辅助信息**。它只能说明一个全球管理 MAC 前缀在 IEEE 注册表中登记给哪个组织，不能说明整台设备由谁制造，也不能识别具体型号。设备型号只来自详情页用户主动触发后获得的 UPnP、IPP、mDNS 或用户授权 ONVIF 协议证据。

> “网卡厂商（OUI）”不是“设备厂商”，更不是“设备型号”。设备可能使用第三方网卡、代工模块、桥接接口或随机 MAC，因此即使前缀匹配也不能推导具体硬件。

## 数据来源与手动同步

设置页的“同步 OUI 数据库”从 IEEE Registration Authority 的公共 CSV 获取三类注册表。[1]

| 注册表 | 前缀长度 | 应用处理方式 |
|---|---:|---|
| MA-L | 24 位（6 个十六进制字符） | 下载并保存为本地前缀—组织名称记录 |
| MA-M | 28 位（7 个十六进制字符） | 下载并保存为本地前缀—组织名称记录 |
| MA-S | 36 位（9 个十六进制字符） | 下载并保存为本地前缀—组织名称记录 |

同步只能由用户在首页右上角的设置页手动触发。应用依次下载三份 CSV，在全部下载和解析成功后再以新文件原子替换旧文件；任意 HTTP 错误、下载中断、超时或无有效记录时，旧数据库保持不变。设置页显示来源、上次成功同步时间和当前本地记录数。IEEE 的注册表页面提示下载存在频率限制，因此本应用不实现后台定时同步，也不会随一次设备扫描自动下载。[1]

## 本地匹配与隐私

当且仅当 Android 提供的 ARP/邻居缓存包含完整 MAC 地址时，应用才尝试本地匹配。所有匹配在应用私有存储中的 TSV 数据库完成，最长前缀优先：36 位 MA-S、28 位 MA-M、24 位 MA-L。应用不把 MAC、IP、设备名称、端口或扫描结果发送给 IEEE、GitHub 或任何其他第三方。

| 输入情况 | 界面行为 |
|---|---|
| 未获得完整 MAC | 不显示 OUI 结论 |
| 首字节的 U/L 位为 1 | 显示“随机/本地管理 MAC，不进行 OUI 匹配” |
| 已同步数据库且最长前缀命中 | 显示“网卡厂商（OUI）”与匹配位数 |
| 已同步数据库但未命中 | 显示本地数据库未匹配，不作厂商猜测 |
| 尚未同步 | 提示先在设置中手动同步，不联网查询 |

Android 的 MAC 随机化会使本地管理地址不适合作为硬件厂商依据，特别是在 Wi‑Fi 隐私 MAC 场景中。[2] 此外，普通 Android 应用读取 `/proc/net/arp` 或邻居缓存的能力受 Android 版本、SELinux 与 OEM 实现限制；缺少 MAC 是预期的平台限制，应用不会伪造或通过主动探测补齐。[3]

## 安全实现要点

同步连接使用当前实际 Wi‑Fi `Network`（若系统提供），并设置 HTTP 连接与读取超时。下载不会自动跟随应用外的操作，也不会执行下载数据中的代码；CSV 仅被解析为十六进制前缀和注册组织名称。同步期间不修改扫描流程，默认发现仍然只收集 IP 与公开服务证据。

## 参考资料

[1] [IEEE Registration Authority：Public MA-L、MA-M、MA-S registries](https://regauth.standards.ieee.org/standards-ra-web/pub/view.html)

[2] [Android Open Source Project：MAC randomization behavior](https://source.android.com/docs/core/connect/wifi-mac-randomization-behavior)

[3] [Google Issue Tracker：access to /proc/net/arp to resolve MAC address of an IP address](https://issuetracker.google.com/issues/128554635)
