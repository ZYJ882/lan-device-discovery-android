# 具体型号识别增强：实现设计

## 当前工程基线

当前发现引擎已能从 mDNS TXT 中读取部分 `fn`、`model`、`manufacturer`、`ty` 字段，并可从同一 SSDP 响应地址的 UPnP 描述 XML 提取 `friendlyName`、`manufacturer`、`modelName` 与 `modelNumber`。这些来源均为设备主动公开的元数据。

## 本次增强范围

1. 增加 **mDNS TXT 字段规范化**：支持常见的 `md`、`am`、`mfg`、`usb_MFG`、`usb_MDL`、`product` 等公开键，并将结果注明为 mDNS 自我声明。
2. 对已经通过 `_ipp._tcp` 确认的打印服务，按 TXT `rp` 路径发起一次**只读、无认证、无任务提交**的 IPP `Get-Printer-Attributes` 请求。仅请求 `printer-make-and-model`、`printer-name`、`printer-info`、`printer-uuid`、`printer-location` 与 `printer-device-id`。
3. 增加统一“身份证据”字段：mDNS 结果标示为“mDNS TXT 公开声明”，UPnP 结果标示为“UPnP 描述公开声明”，IPP 结果标示为“IPP 标准只读属性”。
4. 禁止将端口开放、Banner 或推测的服务类别显示为“具体型号”。端口结果只保留为低置信度服务特征。

## 请求边界

IPP 查询只在用户主动开始发现后、且 mDNS 已确认 `_ipp._tcp` 服务时执行。请求使用发现得到的同一主机与端口，限制路径为 TXT `rp` 的相对路径，设置短连接/读取超时并限制响应读取大小。响应若要求认证或不符合 IPP 格式，将不继续尝试，也不记录任何凭据。
