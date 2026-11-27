using NAudio.CoreAudioApi;
using Nunchaku.Usb;
using System.Runtime.InteropServices;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 0 || args is ["--watch"] or ["--enable-auto"] or ["--disable-auto"])
        {
            try
            {
                if (args is ["--disable-auto"]) DeviceWatcher.Disable();
                else if (args is ["--enable-auto"]) DeviceWatcher.Enable();
                else DeviceWatcher.Run(DeviceWatcher.Binding());
                return 0;
            }
            catch (Exception ex)
            {
                if (args.Length == 0) MessageBox.Show(ex.Message, "NunchakuVolumeSync");
                else Console.Error.WriteLine(ex.Message);
                return 1;
            }
        }
        if (args is ["--bound", var binding, var session] && Guid.TryParse(binding, out var bound) && Guid.TryParseExact(session, "N", out _))
        {
            if (!EventWaitHandle.TryOpenExisting(DeviceWatcher.SessionStop(session), out var sessionStop)) return 1;
            using var stopSignal = sessionStop;
            using var instance = new EventWaitHandle(false, EventResetMode.ManualReset, @"Local\NunchakuVolumeSync.Tray", out bool first);
            if (!first) return 1;
            ApplicationConfiguration.Initialize();
            using var tray = new VolumeTray(bound, stopSignal);
            Application.Run(tray);
            return tray.Result;
        }
        // WinExe does not create a console. Keep diagnostic commands usable from a parent shell or pipe.
        var stdout = GetStdHandle(-11);
        if (stdout == 0 || stdout == -1) AttachConsole(uint.MaxValue);
        if (!(args is [] or ["--status"] or ["--low-test"]))
        {
            Console.Error.WriteLine("NunchakuVolumeSync.exe [--status | --low-test]");
            return 2;
        }
        using var stop = new CancellationTokenSource();
        ConsoleCancelEventHandler cancel = (_, e) => { e.Cancel = true; stop.Cancel(); };
        Console.CancelKeyPress += cancel;
        try { Run(args, stop.Token, null).GetAwaiter().GetResult(); return 0; }
        catch (Exception ex) { Console.Error.WriteLine(ex.Message); return 1; }
        finally { Console.CancelKeyPress -= cancel; }
    }

    [DllImport("kernel32")] private static extern bool AttachConsole(uint processId);
    [DllImport("kernel32")] private static extern nint GetStdHandle(int handle);

    internal static async Task Run(string[] args, CancellationToken stop, Action<int>? showVolume, Guid? bound = null)
    {
        var dir = Path.Combine(AppContext.BaseDirectory, "logs");
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, $"sync-{DateTime.Now:yyyyMMdd-HHmmss-fff}-{Environment.ProcessId}.log");
        using var file = new StreamWriter(path) { AutoFlush = true };
        void Log(string message)
        {
            lock (file)
            {
                var line = $"{DateTimeOffset.Now:O} {message}";
                file.WriteLine(line);
                if (args.Length != 0) Console.WriteLine(line);
            }
        }
        try
        {
            Log($"mode={args.FirstOrDefault() ?? "sync"} log={path}");
            using var lease = new EventWaitHandle(false, EventResetMode.ManualReset, @"Local\Nunchaku.Poc.MI02.Request", out bool acquired);
            if (!acquired) throw new IOException("Another volume controller is running.");
            string hid = bound.HasValue ? HidDevice.Paths().Single(path => Endpoint.HidContainer(path) == bound) : HidDevice.Find();
            using var enumerator = new MMDeviceEnumerator();
            using var device = bound.HasValue
                ? enumerator.GetDevice(Endpoint.Selected(enumerator, bound.Value) ?? throw new IOException("默认播放设备已切换。"))
                : Endpoint.Find(enumerator, hid, Log);
            string endpointId = device.ID;
            void CheckRoute()
            {
                stop.ThrowIfCancellationRequested();
                if (bound.HasValue && Endpoint.DefaultId(enumerator) != endpointId)
                    throw new IOException("默认播放设备已切换。");
            }
            var audio = device.AudioEndpointVolume;
            Log($"Windows level={audio.MasterVolumeLevelScalar:P1} dB={audio.MasterVolumeLevel:F2} mute={audio.Mute} hardwareSupport=0x{(int)audio.HardwareSupport:X} ({audio.HardwareSupport})");
            using var output = HidDevice.Open(hid, FileAccess.Write);
            Log($"hid={hid} {HidDevice.CheckCaps(output)}");
            async Task<int> Request(byte[] report, CancellationToken token)
            {
                CheckRoute();
                // A separate read handle starts with its own queue; the knob listener stays armed during requests.
                using var handle = HidDevice.Open(hid, FileAccess.Read);
                using var input = new FileStream(handle, FileAccess.Read, 1, true);
                // SET normally acknowledges in under 30 ms. A missing ACK is resolved by readback.
                int timeoutMs = !bound.HasValue ? 3000 : report[1] == 1 ? 100 : 350;
                return await HidReports.Exchange(input, output, report, Log, token, timeoutMs) ?? throw new TimeoutException("HID response timeout.");
            }
            if (args is ["--status"])
                Log($"hardware VOL={await Request(HidReports.Query, stop)}");
            else
                await Sync.Run(hid, audio, Request, Log, args is ["--low-test"], stop, showVolume, CheckRoute);
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested) { Log("Stopped."); }
        catch (Exception ex) { Log($"ERROR {ex.GetType().Name} 0x{ex.HResult:X8} {ex.Message}"); throw; }
    }
}
