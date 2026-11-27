# NunchakuVolumeSync · 第一阶段 APK 分析

分析日期为 2026-11-23。目标设备为 iBasso DC-Nunchaku，用户提供的 UAC 页面显示版本 1.71.15。本文的字节序列均为十六进制，偏移从 0 起算。

**结论。** UAC 2.2.3 明确为 Nunchaku 实例化 `com.ibasso.volume.controller.Dc09DeviceController`。该控制器通过 HID 接口发送 8 字节私有消息。设置主音量一次发送一个帧。初次读取音量依赖初始化查询的异步回包，界面 getter 取的是上次保存的数值。当前 Windows 设备已暴露与此格式匹配的 HID 集合，可以从现有驱动进入 POC。

## 1. 输入与证据范围

| 项目 | 核对结果 |
| --- | --- |
| APK | 从用户 Downloads 复制到项目 `assets/base.apk`，两者 SHA-256 相同 |
| APK 指纹 | 13,511,265 字节，SHA-256 `BC78AE850079CB68B28A3205A85037AB492F21F0359A3160ACA865505A5D2959` |
| AndroidManifest | 包名 `com.ibasso.volume`，versionName `2.2.3`，versionCode `125`，minSdk 24，targetSdk 36 |
| DEX | `classes.dex` 9,234,828 字节，`classes2.dex` 1,460,204 字节，均为 DEX 037。下述控制链位于第一份 DEX |
| USB 声明 | `android.hardware.usb.host`、连接及断开广播；设备过滤器资源 `0x7F140000` 解至 `res/FZ.xml` |
| Native Library | 此 APK 的 ZIP 条目中没有 `.so`，逐条检查文件头也未见 ELF。目标音量路径在 DEX 中可追至 Android USB Host API |

**用户实测。** 用户观察到 Android UAC 内调节音量时，机身 OLED 和耳机输出随之变化。截图独立可见产品名、版本 1.71.15、音量 47、声道平衡 L=R 和同轴输出关闭。47 只代表截图当时的显示值。4.4 mm、Tube、HP 和手机型号来自用户提供的环境记录。

**本机实测。** 本次读取了 Windows PnP 属性和 HID 能力，结果见第 5 节。设备输入、输出报告的收发次数均为零。本次没有产生音量读数、USB 抓包、OLED 观察或耳机输出测量。

**APK 静态确认。** 以 Androguard 4.1.4 解析二进制 Manifest、资源及 DEX，核对条件跳转、虚方法覆盖、数组写入、任务分派和收包解析。可按类名、方法签名及方法内字节偏移复核。[DEX 指令摘录](dex-evidence.txt)

## 2. 设备识别与音量写入

1. `UacManager.enumerationUsb()` 遍历 `UsbManager.getDeviceList()`，检查 VID 是否为 `262A`、`2FC6` 或 `0661`，再走 USB 授权分支。资源过滤器也列出这三个 VID。此处可记录 PID，但型号分派另由产品名决定。
2. `onDcDevicePermissionRequired()` 打开设备，取经 `trim()` 的 USB 产品名。在方法内 `0x028C` 与 `iBasso DC-Nunchaku` 忽略大小写比较，命中后在 `0x02A2` 创建 `Dc09DeviceController` 并保存。它继承 `UsbDeviceController`，自行覆盖 `getVolume()`、`setVolume(int)` 和 `initDevice()`。
3. `Dc09Fragment.onProgressChanged()` 在用户触摸滑块期间取进度并调用 `UacManager.setVolume(int)`。加减按钮走布尔重载，依据缓存增减一档，并限制在 0 至 `getMaxVolume() = 100`。该机型走 100 ms 延迟合并，随后 `UacManager$1.handleMessage()` 通过控制器虚方法发送最后的值。
4. `Dc09DeviceController.setVolume(int)` 在 `0x002C–0x0068` 将参数窄化为一个 byte，组装 8 字节后调用 `Task(byte[])`。该构造器取第 0 字节作为 `bSeqID`，把 `isCustomCommand` 设为 2。控制器的 setter 本身不做范围检查。
5. `TaskManager.postTask → TaskManager$1.run → doWork → access$000 → sendCustomData2`。最终调用 `UsbDeviceConnection.bulkTransfer(OUT端点, 原数组, 原数组长度, 200 ms)`。发送函数有针对负返回值的有限重试，任务线程另有等待和重发逻辑。Windows 实现须分别核对写入长度和设备返回的档位。

## 3. 读取、响应与机身上报

| 操作 | 方向 | APK 确认的 8 字节内容或解析条件 |
| --- | --- | --- |
| 查询当前音量 | 主机 → 设备 | `42 11 EE 00 00 00 00 00`。来自 `initDevice()` 的第二个内联数组，载荷位于方法内 `0x009C` |
| 设置绝对档位 V | 主机 → 设备 | `01 10 EF VV VV 00 00 00`。两处 VV 都是同一参数 V 的低 8 位，界面范围为十进制 0–100 |
| 查询响应 | 设备 → 主机 | 8 字节，首字节 `42`，应用取 `data[4]` 为 VOL。其余响应字段的固定值尚待抓包 |
| 设置反馈 | 设备 → 主机 | 应用可解析首字节 `01` 的 8 字节反馈，同样取 `data[4]`。每次写入是否必有此反馈尚待验证 |
| 主动音量上报 | 设备 → 主机 | 8 字节，`data[0] == 00` 且 `data[1] == 12`，取 `data[4]`。其余字节及固件的上报时机尚待验证 |

**主动查询的依据。** `initDevice()` 先调用两次 `getFpgaVersion()`，再依次排入首字节为 `59、42、60、61` 的四个任务。其中 `42` 请求走与设置相同的 custom-2 OUT 路径，回包的 `42` 分支进入 `parseVolume`。其余任务各有用途，音量 POC 只需验证单独的音量查询是否可用。APK 证明了初始化时的队列顺序，尚未验证设备是否要求这个顺序。

**本地读取的依据。** `Dc09DeviceController.getVolume()` 直接调用 `SpStorage.getDc09Volume()`，读取 SharedPreferences 的 `key_dc09_volume`，缺省值为 30。缓存命中、界面显示、刚发出的目标值均不足以证明本次硬件读取成功。

**收包路径。** `MonitorThread.run()` 寻找 class/subclass/protocol 为 `03/00/00` 的接口及其 IN 端点，调用 `claimInterface`，用 `UsbRequest.initialize / queue / requestWait` 持续接收，按实际接收长度回调。`TaskManager.onRequestDone()` 将 8 字节帧交给 `processOldDevice()`。它可按首字节匹配当前任务并通知等待线程，也能在没有待处理查询时分发 `00 12` 上报。事件由 `UacServices.onResponseEvent → UacManager.parseCallBackValue` 处理。Dc09 的专用回调优先处理版本响应 `58`，音量进入共享解析器。解析器取第 4 字节后，经 `parseVolume → Dc09DeviceController.parseNativeAndSaveVolume → SpStorage.setDc09Volume` 更新缓存，再发 `Dc06VolumeEvent`。Nunchaku 页面订阅该事件并更新滑块。

**十条字符串线索的归属。** 这些符号定义在 `com.ibasso.volume.IConstants`。部分名字也出现在 `idToString(byte)` 的日志映射里。下表的关联由实际组帧和解析分支确认，常量经编译内联后未必保留字段读取指令。

| 符号 | 十六进制 | 在 Nunchaku 路径中的证据 |
| --- | --- | --- |
| `ID_GET_VOLUME_LEFT_1 / LEFT_2 / RIGHT_1 / RIGHT_2`（共用 `ID_GET_VOLUME_` 前缀） | `41 / 42 / 45 / 46` | 共享解析器接受这四个序号。Dc09 初始化只构造 `42` 音量查询，没有四次分声道读取 |
| `ID_SET_VOLUME_LEFT_1 / LEFT_2 / RIGHT_1 / RIGHT_2`（共用 `ID_SET_VOLUME_` 前缀） | `01 / 02 / 03 / 04` | Dc09 的 setter 仅构造首字节为 `01` 的单个任务，在偏移 3、4 重复写入 V。不能据此确定两个位置各自的声道语义 |
| `ID_IBASSO_NATIVE_REPORT_VOLUME` | `12` | 对应 8 字节主动通知的第 1 字节，且第 0 字节必须为零 |
| `ID_NATIVE_REPORT_VOLUME` | `53` | 共享任务管理器另有长帧分支，会根据帧内标记生成序号为 `53` 的应用事件，并携带左右值。本机 HID 能力与 Dc09 的 8 字节路径相符，未取得 Nunchaku 使用该长帧分支的证据 |

## 4. USB 传输方式与待验证字段

发送线程选择 `03/00/00` 接口，在其端点中保存 OUT 方向的端点。尽管 `sendCustomData2` 的日志文字包含 `controlTransfer`，`0x002A` 实际调用的是 `bulkTransfer`。APK 另有真正调用 `controlTransfer` 的方法，包括通用基类音量方法和另一种自定义任务发送器。Dc09 的音量路径走第 2 节所列分派。

Android 方法名给出调用 API。USB 端点的实际传输类型取决于描述符的 `bmAttributes`。这段 APK 代码按接口和方向选择端点，没有硬编码端点地址，也没有按传输类型筛选。Windows POC 应按 HID 集合使用报告 API。实际端点地址、总线传输类型、响应中未解析的字节、反馈顺序和延迟仍待核对。

**代码推断。** 两个 OUT 模板的第 2 字节分别是第 1 字节的按位取反，这是这两帧可观察到的关系。暂不把它推广为生成其他命令的规则。APK 也没有给出独立声道写法、静音命令、每档 dB 曲线，或旋钮在所有模式下均会主动上报的保证。

## 5. Windows 本机只读核验

在已连接的 Windows 主机上，使用 `Get-PnpDevice -PresentOnly`、PnP 服务属性、HID 集合枚举及零读写权限的元数据句柄执行查询。通过 `HidD_GetPreparsedData`、`HidP_GetCaps` 和 `HidP_GetValueCaps` 得到下表。这里的 report 长度是 Windows API 缓冲区长度。

| 层级 | 本次实测 |
| --- | --- |
| USB 父设备 | `VID_2FC6&PID_F0B9`，服务 `usbccgp` |
| 音频接口与播放端点 | `MI_00`，服务 `usbaudio2`，播放端点名为“扬声器 (iBasso DC-Nunchaku)” |
| HID 接口 | `MI_02`，服务 `HidUsb`，兼容 ID 指示 `Class_03&SubClass_00&Prot_00` |
| HID 顶层集合 | Usage Page `000C`，Usage `0001` |
| 报告能力 | Input 9 字节，Output 9 字节，Feature 0 字节。输入和输出各有一个 value cap，Report ID 均为 0，ReportSize 均为 8 bit，ReportCount 均为 8 |

**POC 缓冲区映射推断。** 现有描述符支持“Windows 第 0 字节为零号报告占位，后续 8 字节为 APK 载荷”的映射。即 Windows 输入缓冲区偏移 5 对应 APK 的 `data[4]`。元数据句柄能打开的事实仅验证集合可枚举。带读写权限的句柄、固件应答和总线上的实际数据仍需下一阶段验证。[Microsoft HID 报告缓冲区](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/initializing-hid-reports)

## 6. Windows POC 实施与验收

1. **识别及被动接收。** 建立 .NET 8 控制台 POC。用 SetupAPI/HID 按实测 VID/PID、复合设备容器、接口及集合能力定位 Nunchaku；将播放端点与同一物理设备关联。保留现有 HidUsb 和 usbaudio2 驱动。用 `ReadFile` 接收输入报告；在 OLED 已调低时手动转动一档，核对是否出现 `00 12` 上报及正确的档位。每次连接重新枚举。[Microsoft HID 输入](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/obtaining-hid-reports)
2. **验证主动读取。** 先启动接收，再经 `WriteFile` 只发一次已确认的查询模板。GET 在业务上只读，但 USB 方向仍然是 OUT。记录原始请求、完整响应和 OLED；以报告长度、序号、范围及 OLED 数字共同判定。在手动调节前后各读一次。超时或不匹配时保留原始记录并停止本轮；可对照 Android 日志中的 `MonitorThread: native###data[]` 或实际 USB 抓包。[Microsoft HID 输出](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/sending-hid-reports)
3. **低档位 SET。** 只有 GET 与 OLED 一致后才进行首次 SET。先用机身旋钮设定已知低档位，摘下耳机并暂停播放。选非零低档位向下调一档，逐项记录 OUT 结果、可能的反馈、独立 GET 回读和 OLED。实际输出另以固定低电平测试信号，在相同输出模式、增益及负载下比较前后输出。USB 写入成功、读回正确、OLED 变化、输出变化分别记录。
4. **接入 Core Audio。** 对目标播放端点读取 `QueryHardwareSupport`，记录音量和静音能力。订阅端点音量通知，合并滑块事件，按 0–100 档映射；使用事件上下文 GUID、待确认目标及设备实际读回抑制双向反馈。启动或重连以硬件读回作为起点，避免将 Windows 上次的 100% 下发给耳放。只有旋钮主动报告通过实测才启用事件式反向同步；否则评估已验证 GET 的低频轮询。[Microsoft 端点音量接口](https://learn.microsoft.com/en-us/windows/win32/api/endpointvolume/nn-endpointvolume-iaudioendpointvolume)
5. **验证数字衰减和静音。** 如果共享模式下 Windows 端点仍施加软件增益，仅将系统百分比镜像到硬件会形成双重衰减。分别测试共享与独占播放，固定硬件档位比较系统端点变化对输出的影响，再决定系统滑块能达到的控制效果。静音标志单独处理，验证解除静音及恢复档位；不得假定硬件 0 档等价于独立静音。该项通过后再验收任务栏滑块和键盘音量键。[Microsoft 端点硬件能力](https://learn.microsoft.com/en-us/windows/win32/api/endpointvolume/nf-endpointvolume-iaudioendpointvolume-queryhardwaresupport)

下一阶段每次传输应记录时间戳、会话和设备标识、方向、报告 ID、完整十六进制字节、实际长度、返回值、错误、超时及重试。把用户操作、OLED 数字和输出测量与同一时间线对应。当前交付为 APK 分析及只读枚举结果，真实硬件控制、托盘程序及 EXE 属于后续阶段。
