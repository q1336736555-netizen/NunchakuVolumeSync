# 网易云音乐 USB 独占磁贴

在安卓控制中心点一下「USB 独占」即可切换，长按可发起只读查询。安装包和源码对应同一版手机功能。[下载已签名 APK](apk/CloudUsbTile.apk)

- **短按**会打开网易云音乐，进入「设置 → 播放与下载 → 更多播放设置」，找到「独占usb」一行并点击一次。确认状态改变后返回网易云首页。
- **长按**会打开网易云读取开关，然后返回首页。开启时磁贴高亮，关闭时变灰。
- **拔出 DAC** 后磁贴显示「未连接 DAC」。重新插入时保持当前前台应用，新连接尚无读数时显示「待确认」。长按可刷新。在网易云设置中手动操作时，无障碍服务也会同步它读到的开关状态。

磁贴显示网易云开关的最近一次读数。当前版本使用 Android 14 或更高版本，开发设备为 vivo V2505A、Android 16、网易云音乐 9.6.35。手机上的安装包已核对，带 DAC 的开关、拔插及长按查询仍需实测。

## 用无线 ADB 接上 DAC 调试

以下命令在 Windows PowerShell 中运行，需要 Android SDK Platform-Tools，并将 `adb` 加入 PATH。电脑可用网线，手机连 Wi-Fi，两者须能通过局域网相互访问。

1. 在手机「开发者选项 → 无线调试」开启无线调试。第一次在这台电脑上配对时，点「使用配对码配对设备」。运行下面的命令，照手机弹窗输入地址，再按 ADB 提示输入六位码。已配对的电脑可以直接进行下一步。

   ```powershell
   $pairAddress = Read-Host '配对弹窗中的 IP:端口'
   adb pair $pairAddress
   ```

2. 回到手机「无线调试」详情页，读取该页的「IP 地址和端口」作为连接地址。它和刚才弹窗里的配对端口用途不同。

   ```powershell
   $phone = Read-Host '无线调试详情页的 IP:端口'
   adb connect $phone
   adb -s $phone shell getprop ro.product.model
   ```

3. 看到正确型号后，拔下电脑的 USB 线，把 DAC 接到手机。再运行一次型号命令，确认无线连接仍可用。若配对成功但连接超时，检查详情页端口是否已变，以及手机和电脑之间是否存在访客网络隔离或 VPN 路由。

4. 已装好磁贴的手机可直接开始测试。新手机可在本目录执行安装命令，然后在系统的无障碍设置中启用「网易云 USB 独占」，在控制中心的编辑页面添加「USB 独占」。若 vivo 首次询问是否允许磁贴打开网易云，可选「始终打开」。

   ```powershell
   adb -s $phone install -r .\apk\CloudUsbTile.apk
   ```

5. 用下面的命令跟踪磁贴日志。长按读取初始状态，然后短按一次，等出现结果后再短按一次恢复。拔出 DAC 后观察磁贴是否变灰，插回时观察是否保持当前应用，再长按核对状态。按 Ctrl+C 结束日志。

   ```powershell
   adb -s $phone logcat -T 1 -s CloudUsbTile:D
   ```

   若长按或短按没有响应，可查看无障碍服务是否已绑定。在这台 vivo 上还可检查系统实际收到的磁贴状态，`secondaryLabel` 是状态文字，`state=1` 为未激活，`state=2` 为高亮。

   ```powershell
   adb -s $phone shell dumpsys accessibility | Select-String 'Bound services|网易云 USB 独占'
   adb -s $phone shell dumpsys activity service com.android.systemui/.SystemUIService |
       Select-String 'State\[spec=custom\(cn.local.usbtile'
   ```

## 从源码构建

在 Windows 上安装 JDK 21 和 Android SDK。构建脚本使用 SDK 中的 `platforms/android-37.0/android.jar` 和 `build-tools/36.0.0`。在本目录执行：

```powershell
.\build.ps1 -Sdk $env:ANDROID_HOME -Jdk $env:JAVA_HOME
```

输出为 `build/CloudUsbTile.apk`，脚本会验证签名。首次构建时，如果当前账户没有 Android 调试签名，脚本会生成个人调试密钥。手机覆盖安装要求签名一致；在另一台电脑构建时，可私下迁移原电脑的 `%USERPROFILE%\.android\debug.keystore`。仅用无线 ADB 测试仓库提供的 APK 时，无需迁移密钥或重新构建。

| 文件 | 调试时看哪里 |
| --- | --- |
| [UsbTile.java](src/UsbTile.java) | 磁贴颜色和副标题、DAC 插拔、每次 USB 连接的读数 |
| [ToggleActivity.java](src/ToggleActivity.java) | 单击切换与长按查询的入口 |
| [Navigation.java](src/Navigation.java) | 识别网易云页面、操作开关、回读结果及返回首页 |
| [AndroidManifest.xml](AndroidManifest.xml) 和 [res](res) | 系统服务注册、长按入口、图标和无障碍服务配置 |
