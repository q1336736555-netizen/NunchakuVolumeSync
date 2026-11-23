# NunchakuVolumeSync · Windows 音量联动

已启用设备绑定后，在 Windows 11 的声音输出列表中选择 **扬声器 (iBasso DC-Nunchaku)**，音量联动会自动启动，右下角出现青绿色图标。切到其他默认输出或拔出 DAC 后，联动进程退出，USB 句柄释放，图标消失。再次将已绑定的 DAC 选为默认输出后，联动会重新启动。监听器跟踪系统默认多媒体播放设备；单独修改某个应用的输出或默认通讯设备不会触发切换。

鼠标移到图标上可查看机身 VOL。右键选择 **退出** 可停止本次联动，切走再切回或双击 EXE 可恢复。当前用户登录后会自动运行后台设备监听器。选用其他输出时，任务管理器中仍可看到带 `--watch` 的监听进程，此时它等待 Windows 通知，不打开 DAC。程序按 USB 设备容器 ID 绑定到这台 Nunchaku。

程序只绑定与 Nunchaku HID 同属一个 PnP 容器的播放端点。启动时先读取机身 VOL，再将 Windows 滑块对齐到该档位。正常模式采用百分数对硬件整数档位的映射，范围 0–100。Windows 音量键的步长由系统决定，旋钮可以逐档同步。静音使用 Windows 端点本身的静音标志。

| 运行方式 | 用途 |
| --- | --- |
| `NunchakuVolumeSync.exe` | 开始本次登录的设备监听，选中已绑定的输出时运行托盘联动 |
| `NunchakuVolumeSync.exe --enable-auto` | 首次绑定时连接目标 Nunchaku。启用当前用户登录启动，并立即监听，无需管理员权限 |
| `NunchakuVolumeSync.exe --disable-auto` | 关闭自动监听与联动，移除当前用户的登录启动项，保留绑定记录 |
| `NunchakuVolumeSync.exe --status` | 查询当前系统百分比、dB、静音、驱动能力及硬件 VOL，然后退出 |
| `NunchakuVolumeSync.exe --low-test` | 起始机身 VOL 须在 0–10。Windows 目标超过 10 时把滑块调回当前硬件值 |

发布目录应保持在固定位置。移动程序后，从新路径重新运行 `--enable-auto`。登录启动项位于 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 的 `NunchakuVolumeSync` 值。设备切换记录保存在 EXE 旁的 `logs/watch-*.log`。

Windows 音量变化后立即处理，常规档位变更直接发送 SET。USB 忙时保留最新通知，每次 SET 仍核对反馈及独立 GET。收到旋钮通知后，程序查询当前硬件档位，再更新端点。程序给自己的端点写入附带专用事件 GUID，以免写回环路。日志存于 EXE 旁的 `logs`，带 `monitor` 的行是持续监听句柄收到的副本，可能与请求句柄收到的回包相同。

日志的 `eventToIssueMs` 记录从 Windows 通知到开始处理 SET 请求的时间，`eventToFeedbackMs` 记录从通知到收到设备反馈的时间。耳机口实际响度变化的时间需要另外测量。启动前如果 Windows 百分比远低于机身 VOL，建议暂停播放并先用旋钮调低机身档位，再启动同步，以免启动对齐时端点增益明显上升。

## 2026-11-23 真机记录

| 项目 | 实测结果 |
| --- | --- |
| 设备身份 | HID `VID_2FC6&PID_F0B9&MI_02` 与播放端点属同一容器。测试时 Nunchaku 为默认多媒体播放设备 |
| 启动 | Windows 原为 2%，机身 VOL 4。启动后 Windows 变为 4%，启动阶段只发送了硬件 GET |
| 滑块及键盘 | 用户观察到系统滑块与 OLED 动态对应，本机按键按 2 个百分点变化。日志中可见 6% 和 4% 分别触发 SET，设备反馈及随后 GET 均与目标一致 |
| 机身旋钮 | 收到包含 VOL 5 的主动报告，GET 确认为 5，写入端点后 Windows 读回为 5% |
| 静音与解除 | 在 Windows 5%、机身 VOL 5 时切换端点静音，分别读到 `mute=True` 与 `mute=False`。两次硬件 GET 均为 5，未触发硬件 SET |
| 退出 | 低档测试已停止，最终核验为 Windows 5%、未静音、机身 VOL 5 |

原始测试证据包括[启动前状态](evidence/sync-20261123-161205-100-36404.log)和[联动会话](evidence/sync-20261123-161217-450-26672.log)。本次验证覆盖低档位及两种控制方向，高音量档位未实机扫测。

### 默认输出切换验证

用户通过 Windows 输出选择器切走后，原联动进程以退出码 0 结束。切回 Nunchaku 后，新联动进程重新查询硬件 VOL 58 并进入同步。停用命令也已实测，监听器、联动进程及登录启动项均退出或移除，随后重新启用并恢复同步。[切换日志](evidence/watch-20261123-163841-37616.log)

## 增益与输出

本机端点报告 `QueryHardwareSupport = 0x3`（Volume 和 Mute）。Windows 从 4% 到 6% 时，端点读数从 −44.16 dB 到 −39.49 dB，同时私有 HID 路径改变机身 VOL。这两条控制路径都在工作。微软说明，驱动提供的硬件端点音量可以影响共享和独占播放。dB 读数反映端点设定，实际耳机口增益、音质及两级衰减是否叠加仍需固定测试信号和输出测量。[Microsoft 端点音量接口](https://learn.microsoft.com/en-us/windows/win32/api/endpointvolume/nn-endpointvolume-iaudioendpointvolume)

## 源码与构建

使用 .NET 8 SDK，从项目根目录执行以下命令。发布结果为自包含 Windows x64 程序，使用 NAudio.Wasapi 2.2.1 访问 Core Audio。源码含联动程序、共用 HID 层及上一阶段的命令行 POC。

```powershell
dotnet publish src/NunchakuVolumeSync -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -o dist/sync
```
