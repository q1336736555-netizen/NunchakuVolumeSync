# 藏映 MV · 2026-11-27 交接

## 晚上从这里接手

目标是在 Android 上直接打开 B 站收藏夹连续看 MV，音频优先 Hi-Res，并能通过 libusbdac 独占外置 DAC。当前任务是定位媒体 HTTP 403。手机测试已按用户要求暂停。

1. 在家中电脑执行 `adb devices -l`，确认手机已授权新主机。在手机上保留现有 App 和登录状态。若要边调试边接台机，可在同一局域网配对无线 ADB，把 USB 口留给 DAC。
2. NeriPlayer 官方 arm64 版 `693842c8.11262018` 已成功安装在 V2505A（Android 16）。当时进入过 B 站登录页，随后回到首次使用引导。登录成功、同曲播放和出声均待确认。用手机完成必要的引导，在 B 站来源打开「音乐」收藏夹，或搜索下文完整标题。核对同一投稿、同一分 P。两款 App 的对照应紧邻进行，使用同一台手机、同一网络。回家后网络可能变化，应重新测两款 App。
3. 记录 NeriPlayer 的音质、进度是否持续推进、系统音频轨是否启动及其输出设备，让用户确认可闻声音。如果它能播，记录可复现的请求差异，在藏映 MV 用同一音轨一次只改一个因素。先核对 Cookie 处理与 A/B 两组实际是否不同。只记录 Cookie 是否存在，不导出登录凭据或带签名的媒体 URL。音频恢复后单独验证视频首帧与进度。
4. 最终验收走藏映 MV 正常界面。连接 DAC 并选择独占输出，在「音乐」收藏夹打开目标 MV，记录相邻下一条的标题。可拖到片尾前几秒，再让它自然播完。确认同一播放器自动进入预期下一条，有画面、进度持续推进且 DAC 持续出声。区分自然播完和因错误跳过。没有 DAC 时可完成网络对照，独占输出保留为待验项。

## 已有证据

| 项目 | 已观察到的结果 |
| --- | --- |
| 测试样本 | 「音乐」上次枚举到 52 条可播放类型的条目，目标是当时的第 2 条《【Hi-Res】醉乡民谣原声录音》。收藏夹顺序可变，按标题重新定位。 |
| 403 出现位置 | 已登录账户能列收藏夹，播放信息接口能返回音、视频地址。真机对媒体地址发起读取，在首帧前收到 HTTP 403。已测 AVC、FLAC，包含首段 Range、服务端备用地址及单文件测试。一次测得剩余有效期约 7200 秒，HTTP 403，媒体读取 0 字节。 |
| Cookie 对照 | 对账户接口移除 Cookie 时未登录，保留时已登录。同一条媒体地址的两次请求均无域匹配的 Cookie，音频和视频均返回 403。这组结果不能确定 Cookie 是否是根因。 |
| 安装版本差异 | 新版 CookieProbe 源码已加入备用地址的 Cookie 匹配计数，已编译。上次更新测试 APK 时手机拒绝安装，因此该新增计数尚无实测结果。 |
| 自动切歌 | MainActivity 收到 STATE_ENDED 后会加载下一条。此前的 403 阻断了实际片尾验收。PlaybackProbe 用静音的独立 ExoPlayer 和离屏画面测试两条媒体，不能证明正式界面或物理 DAC 已通过。 |
| USB | 正式项目有 UsbRenderers 与独占输出分支。上次媒体探针报告 USB 音频设备数为 0。要验证台机，需接 DAC 并在 App 内核对输出模式。 |

## 代码入口

均在 `app/src/main/java/cn/local/mv/`。Bili.kt 负责账户、收藏夹和播放地址，PlaybackHttp.kt 负责逐域请求 Cookie，MvSource.kt 负责 Media3 分轨与备用地址，MainActivity.kt 负责播放、片尾切换及输出选择，Usb.kt 负责独占音频链路。测试在 `app/src/androidTest/java/cn/local/mv/test/`。

NeriPlayer 参考 [对应安装版本源码](https://github.com/cwuom/NeriPlayer/tree/693842c8d79e52215f0dd53d2f17fa01b66d8971)。源码内搜索 BiliClient.kt、BiliPlaybackModels.kt、BiliStreamCredentialInterceptor.kt、ConditionalHttpDataSourceFactory.kt。两边使用原生 Media3。此前发现音源请求的格式参数、User-Agent、备用地址优先级、媒体域 Cookie 策略有差异。已对照这四个文件，安装版本与前次研究版本之间无文件差异。NeriPlayer 成功播放某音轨也只能建立该音轨的基线。

## 构建与复测

项目自带 Gradle wrapper。准备 JDK 21、Android SDK Platform 37 和 platform-tools，在本目录执行下面的 PowerShell 命令。首次在新机器构建需要下载 Gradle 及 Google、Maven Central、JitPack 依赖。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

迁移包内的 apks 目录保存本次机器已有的主 APK 和最新测试 APK。如需安装测试探针，优先使用同批签名的包。新电脑自行构建时默认 debug 签名可能不同；若遇签名不一致，先保留手机现有安装与登录数据。需要更换签名时先和用户确认。手机拒绝安装后等用户再次允许。

在手机安装匹配的测试包后，可运行以下诊断。正式界面的片尾和 DAC 验证仍按上文第 4 步执行。

```powershell
adb shell am instrument -w -e folder 音乐 -e match 醉乡民谣原声录音 -e cookieCompare true cn.local.mv.test/cn.local.mv.test.PlaybackProbe
```

## 设计定稿

整体用 B 站粉。横屏全屏控制参考 B 站，音频详情从右侧展开，偏黑半透明底板沿整条左边缘短距离淡出。Hi-Res、Dolby 都是单击直接开关。Hi-Res 用五根音柱，Dolby 双 D 的律动与字标分开。迁移包内 design 目录保留选集、清晰度与倍速、详情、两种音质开关的参考动图。当前排查阶段保留已定稿的 UI。
