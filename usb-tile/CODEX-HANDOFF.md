# 给 Codex 的交接：网易云 USB 独占磁贴实机调试

请在家里的 Windows 电脑上拉取项目，用无线 ADB 连接我的 vivo，配合我插拔 iBasso DC-Nunchaku，验证磁贴的查询、切换、颜色和返回首页。请直接完成电脑端操作并观察手机结果。需要我在手机上打开无线调试、输入配对码、解锁、确认系统授权或插拔 DAC 时，告诉我当下要做的那一步。

## 项目与已知状态

- [GitHub 仓库](https://github.com/q1336736555-netizen/DAC-Projects) · [磁贴项目目录](https://github.com/q1336736555-netizen/DAC-Projects/tree/main/usb-tile) · [构建与调试说明](https://github.com/q1336736555-netizen/DAC-Projects/blob/main/usb-tile/README.md) · [已签名 APK](https://github.com/q1336736555-netizen/DAC-Projects/raw/refs/heads/main/usb-tile/apk/CloudUsbTile.apk)
- 上次调试的手机是 vivo V2505A，Android 16，网易云音乐 9.6.35。磁贴包名为 `cn.local.usbtile`。手机已安装仓库提供的版本，也已开启无障碍服务，可以直接测试。只有手机缺少安装包时才需要安装。
- 上次用有线 ADB 验证了磁贴能找到「独占usb」开关、从设置逐级返回网易云首页，以及无 DAC 时磁贴显示灰色。带 DAC 的完整开关、状态查询和拔插流程留到本次验证。
- 用户希望插入 DAC 时继续使用当前应用。预期行为是**短按切换、长按只查询、插入设备时不主动跳转**。

## 在新电脑上拉取代码

在 PowerShell 检查 `git --version` 和 `adb version`。缺少工具时可从官方下载。ADB 位于解压后的 `platform-tools` 目录，加入 PATH 或使用 `adb.exe` 完整路径即可。[Git for Windows](https://git-scm.com/downloads/win) · [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)

在准备存放项目的位置运行：

```powershell
git clone https://github.com/q1336736555-netizen/DAC-Projects.git
cd DAC-Projects\usb-tile
```

如果家里已有该仓库，先检查工作区，再在已有检出中运行 `git pull --ff-only`，进入 `usb-tile` 目录。源码目录、APK 和调试说明都在这里。

## 配对并验证无线连接

电脑可以接网线，手机连家里的 Wi-Fi，两者须能通过局域网互访。先用 `adb devices -l` 检查已有连接。若新电脑还没与手机配对，让我在「设置 → 开发者选项 → 无线调试」打开「使用配对码配对设备」。使用手机当场显示的地址和六位码。[Android 官方无线调试说明](https://developer.android.com/tools/adb#wireless-android11-command-line)

```powershell
$pairAddress = Read-Host '配对弹窗中的 IP:端口'
adb pair $pairAddress
```

看到 `Successfully paired` 后，回到**无线调试主页面**读取「IP 地址和端口」，这是连接端口。已配对的电脑可以从这里开始。

```powershell
$phone = Read-Host '无线调试主页面的 IP:端口'
adb connect $phone
adb devices -l
adb -s $phone shell getprop ro.product.model
adb -s $phone shell pm path cn.local.usbtile
```

型号应为 `V2505A`，最后一条应返回 APK 路径。若电脑还插着 USB 线，此时拔线，再用同一条型号命令确认无线通道仍在线，随后将手机 USB 口留给 DAC。若配对成功但连接超时，先核对主页面端口，再检查访客 Wi-Fi 隔离和 VPN 的局域网路由。

## 接 DAC 并记录结果

在单独的 PowerShell 窗口记录日志，测试结束按 Ctrl+C。新窗口需要重新设置 `$phone`。在 Codex 的独立命令调用中，也应传入已经确认的无线设备地址。日志保存在这台电脑的临时目录。

```powershell
$phone = Read-Host '已验证的无线连接 IP:端口'
$log = Join-Path $env:TEMP ("cloud-usb-tile-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))
Write-Output $log
adb -s $phone logcat -T 1 -s CloudUsbTile:D | Tee-Object -FilePath $log
```

| 顺序 | 操作 | 核对结果 |
| --- | --- | --- |
| 1 | 保持其他应用在前台，请我插入 DAC，等系统识别后下拉控制中心 | 磁贴不会主动打开网易云。新连接尚无读数时显示「待确认」 |
| 2 | 长按「USB 独占」磁贴，记下初始状态 | 自动读取开关并返回网易云首页。开启时高亮，关闭时变灰 |
| 3 | 短按一次，等待完成 | 开关变为相反状态，结果提示和磁贴颜色一致，成功后回到网易云首页。若首次出现 USB 设备授权，由我确认 |
| 4 | 再短按一次，等待完成 | 回到第 2 步记录的状态，网易云回到首页 |
| 5 | 请我拔出 DAC，再在其他应用前台时插回 | 拔出后变灰并显示「未连接 DAC」。插回后保持当前应用，长按可重新读取实际状态 |

要核对 vivo 控制中心实际收到的状态，可在展开控制中心后运行：

```powershell
adb -s $phone shell dumpsys activity service com.android.systemui/.SystemUIService |
    Select-String 'State\[spec=custom\(cn.local.usbtile'
adb -s $phone shell dumpsys accessibility | Select-String 'Bound services|网易云 USB 独占'
```

`secondaryLabel` 是磁贴状态文字，`state=2` 表示高亮，`state=1` 表示灰色。判断结果时要一起看，例如「待确认」和「已关闭」都可能是灰色。如果磁贴提示开启失败，同时检查网易云和系统的 USB 授权提示。测试磁贴期间优先用普通 ADB 和日志；这台 vivo 上，之前长时间运行的 UI 自动化会话曾暂时影响无障碍服务绑定。

结束时请给我一份简短结果：哪些步骤通过、哪一步能复现问题、实际提示或日志是什么，以及日志文件保存在哪。如果需要改代码，页面识别和返回流程在 [Navigation.java](https://github.com/q1336736555-netizen/DAC-Projects/blob/main/usb-tile/src/Navigation.java)，磁贴颜色和 DAC 连接判断在 [UsbTile.java](https://github.com/q1336736555-netizen/DAC-Projects/blob/main/usb-tile/src/UsbTile.java)。新电脑自行构建的 APK 要覆盖手机现有版本，需要与原包相同的签名；使用现有安装包做以上测试无需签名密钥。
