# NunchakuVolumeSync

在 Windows 11 x64 上，用任务栏滑块和键盘音量键调节 iBasso DC-Nunchaku 的机身音量。机身旋钮也能反向更新 Windows 音量。程序按 USB 设备容器绑定，选中这台 DAC 时启动托盘联动，切换到其他默认播放设备时退出联动。

## 在新电脑上使用

1. 解压完整项目包，将 `dist/sync` 放在固定且可写的位置。
2. 连接 DC-Nunchaku，在 Windows 中将它选为默认播放设备。
3. 暂停播放并将机身音量调低。程序启动时会将 Windows 音量对齐到机身档位。
4. 在 `dist/sync` 中打开 PowerShell，执行 `.\NunchakuVolumeSync.exe --enable-auto`。右下角图标出现后即可调节音量。

停用自动监听及登录启动可执行 `.\NunchakuVolumeSync.exe --disable-auto`。Windows x64 发布程序已包含 .NET 运行时。

## 项目内容

| 路径 | 用途 |
| --- | --- |
| `src/NunchakuVolumeSync` | 托盘程序、Core Audio 双向同步及设备监听 |
| `src/Nunchaku.Usb` | HID 枚举、通信、已验证的音量查询与设置 |
| `src/Nunchaku.Poc` | 读取机身 VOL、被动监听及低档位写入测试 |
| `dist/sync`、`dist/win-x64` | 托盘程序与诊断工具的 Windows x64 发布文件 |
| `docs` | APK 分析、协议证据、实机测试及构建说明 |

完整包保留原始目录结构。[使用及构建说明](docs/windows-sync.md) · [Windows HID 实测](docs/windows-poc.md) · [APK 协议分析](docs/apk-analysis.md)
