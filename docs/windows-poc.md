# Windows HID 音量 POC 实测

2026-11-23，在用户当前连接的 DC-Nunchaku 上，通过 Windows 自带的 HidUsb 完成了主动查询、旋钮通知接收和一次低档位设置。测试过程中只发出一次改变档位的命令，目标为 4。末次独立查询和用户观察到的 OLED 均为 4。

| 验证项 | 观察 | 证据 |
| --- | --- | --- |
| 初始硬件档位 | GET 返回 49，用户确认旋钮调整前 OLED 为 49 | [首次 GET 日志](../dist/win-x64/logs/hid-20261123-160107-776-30972.log) |
| 旋钮反向通知 | 最终转到 48 时，监听器收到主动通知；用户确认 OLED 为 48。稍后的独立 GET 也返回 48 | [被动监听](../dist/win-x64/logs/hid-20261123-160119-141-38692.log) · [第二次 GET](../dist/win-x64/logs/hid-20261123-160219-545-32492.log) |
| 首次 SET 前置核对 | 用户按测试流程准备并将 OLED 调至 5，EXE 在写入前重新查询，返回 5 | [5→4 完整会话](../dist/win-x64/logs/hid-20261123-160404-177-34740.log) |
| 首次 SET | 单次发送目标 4，写入完成 9 字节，设置反馈为 4，随后独立 GET 也返回 4。用户在写入后确认 OLED 显示 4 | 同一会话，16:04:04，UTC+8 |

本次读取到的 Windows HID 报告如下，首字节是 Windows 的零号报告占位。

```text
旋钮到 48  IN   00 00 12 ED 00 30 30 00 00
读取 VOL  OUT  00 42 11 EE 00 00 00 00 00
读取到 5  IN   00 42 11 EE 00 05 05 00 00
设置到 4  OUT  00 01 10 EF 04 04 00 00 00
设置反馈  IN   00 01 10 EF 04 04 00 00 00
独立查询  OUT  00 42 11 EE 00 00 00 00 00
读回为 4  IN   00 42 11 EE 00 04 04 00 00
```

**验证边界。** 日志为 Windows 用户态读写的完整 HID 报告。本轮在暂停播放的条件下核对控制链，耳机口的模拟输出电平和实际响度尚未单独测量。当前程序供命令行验证，任务栏滑块与键盘联动属于下一阶段。接入 Core Audio 后还需验证共享模式的软件增益是否会叠加。

## 使用与构建

交付文件为 `dist/win-x64/Nunchaku.Poc.exe`，是 .NET 8 Windows x64 自包含程序。双击默认查询一次。用 PowerShell 运行可查看结果；每次有效调用均在 EXE 同目录的 `logs` 下保存时间、设备路径和 HID 操作。该目录需要写权限。

```powershell
.\Nunchaku.Poc.exe get
.\Nunchaku.Poc.exe listen 30
```

`listen` 只监听输入，单位为秒。低档位测试命令 `down-one N` 要求用户先核对机身 OLED 的当前档位 N，程序随后自行 GET 校验。仅接受 2–10 档作为起点，匹配后发送一次目标为 N−1 的 SET，再独立查询；超时不重发 SET。运行测试前暂停播放并摘下耳机。日常读取只需使用 `get`。

在安装了 .NET 8 SDK 的机器上，从项目根目录执行以下命令可重新生成相同形式的 EXE。

```powershell
dotnet publish src/Nunchaku.Poc -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -o dist/win-x64
```
