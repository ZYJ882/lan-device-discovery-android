# 热点设备识别与型号判定调研

## 截图误报结论

截图显示热点网关为 `192.168.182.183`，应用却把 254 个地址中的 254 个标为响应设备，并为大量地址套用 HTTP、HTTPS、SSH 与 SMB 标签。这不是实际设备数量，而是 TCP `connect()` 在 VPN 或网络中间层环境中被代理/截获后返回成功，导致扫描器把“套接字建立”错误等同于“目标设备对应服务真实开放”。因此，热点模式不能再以裸 TCP 建连作为设备存在的唯一证据，也不能仅凭 `631` 或 `9100` 端口就归类为网络打印设备。

## 用户提出的识别路径

| 路径 | 普通 Android 应用的可行性 | 价值与限制 |
|---|---|---|
| 1. ARP + MAC-OUI | 不可靠且不应作为产品核心 | Android 10 起 `/proc/net` 访问受到限制，公开替代 API 不提供 IP-MAC 映射；同时 Android/现代设备常使用随机 MAC，OUI 不能可靠反映制造商。[1] [2] |
| 2. mDNS / NSD、UPnP / SSDP | 推荐，作为高置信度主路径 | 可取得服务实例名、TXT 元数据、SSDP `SERVER`、`ST`、`LOCATION`，并可进一步读取同一设备公开的 UPnP 描述文档以获得 friendlyName、manufacturer、modelName、modelNumber。[3] [4] |
| 3. 端口指纹 / Banner | 可用，但必须是低置信度补充 | 不能将 TCP 握手成功当成开放服务；应只在有经过网络路径约束的真实应用层响应时保留 HTTP 头、SSH Banner、TLS 证书主题等已公开信息。普通 Android 上无法可靠保证热点下游套接字从热点接口发出，因此 VPN 环境下该路径应禁用或降级。 |
| 4. 路由器 DHCP 信息 | 对第三方普通应用不可行 | 对本机热点，系统 Soft AP 能维护已连接客户端及 IP/MAC 信息，但完整客户端管理回调或关联信息在不同设备上属于系统设置应用等受限能力，普通应用不能依赖。[3] |

## 推荐产品策略

1. **热点模式禁用裸 TCP 子网扫掠作为“发现设备”依据**，从根源上避免 VPN/透明代理制造的虚假设备。
2. 仍显示由 mDNS 和 SSDP 收到的设备；SSDP 仅在收到实际 UDP 响应时建立记录。
3. 新增“已验证设备”状态，只有获得 mDNS/SSDP/真实应用层 Banner 的设备才能显示为已发现；其余只显示扫描状态而不计入设备数量。
4. 将设备型号字段分为“公开型号”“服务特征”和“未识别”，避免把打印机、NAS、手机等仅按端口猜测成确定型号。
5. 若需要像系统设置页一样准确列出**全部已接入热点的设备名称、MAC、IP**，需要 OEM/系统签名应用、设备管理能力或厂商私有接口；不应承诺普通 APK 可以稳定实现。

## 参考资料

[1] [Google Issue Tracker: access to /proc/net/arp to resolve mac address of an IP address](https://issuetracker.google.com/issues/128554635)

[2] [Android Open Source Project: MAC randomization behavior](https://source.android.com/docs/core/connect/wifi-mac-randomization-behavior)

[3] [Android Open Source Project: Wi-Fi hotspot (Soft AP)](https://source.android.com/docs/core/connect/wifi-softap)

[4] [Android Developers: Use network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)
