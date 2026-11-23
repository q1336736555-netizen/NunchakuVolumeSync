using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;
using Nunchaku.Usb;

internal static class DeviceWatcher
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string PreferenceKey = @"Software\NunchakuVolumeSync";
    private const string Name = "NunchakuVolumeSync";
    private const string WatchStop = @"Local\NunchakuVolumeSync.StopWatcher";

    internal static Guid Binding()
    {
        using var prefs = Registry.CurrentUser.CreateSubKey(PreferenceKey);
        if (Guid.TryParse(prefs.GetValue("Container") as string, out var saved)) return saved;
        var bound = Endpoint.HidContainer(HidDevice.Find()) ?? throw new IOException("请先连接 DC-Nunchaku，再启用设备绑定。");
        prefs.SetValue("Container", bound.ToString());
        return bound;
    }

    internal static void Enable()
    {
        Binding();
        using var run = Registry.CurrentUser.CreateSubKey(RunKey);
        run.SetValue(Name, $"\"{Environment.ProcessPath}\" --watch");
        using var watcher = Start("--watch");
    }

    internal static void Disable()
    {
        using var run = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
        run?.DeleteValue(Name, throwOnMissingValue: false);
        if (EventWaitHandle.TryOpenExisting(WatchStop, out var signal))
            using (signal) signal.Set();
    }

    private static Process Start(params string[] args)
    {
        var info = new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = false };
        foreach (var arg in args) info.ArgumentList.Add(arg);
        return Process.Start(info) ?? throw new IOException("无法启动设备联动。");
    }

    public static void Run(Guid bound)
    {
        using var resume = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\NunchakuVolumeSync.Resume");
        using var stop = new EventWaitHandle(false, EventResetMode.ManualReset, WatchStop, out bool first);
        if (!first) { resume.Set(); return; }
        using var changed = new AutoResetEvent(false);
        using var enumerator = new MMDeviceEnumerator();
        var notifications = new Notifications(changed);
        Marshal.ThrowExceptionForHR(enumerator.RegisterEndpointNotificationCallback(notifications));
        var logs = Directory.CreateDirectory(Path.Combine(AppContext.BaseDirectory, "logs"));
        using var file = new StreamWriter(Path.Combine(logs.FullName, $"watch-{DateTime.Now:yyyyMMdd-HHmmss}-{Environment.ProcessId}.log")) { AutoFlush = true };
        void Log(string text) => file.WriteLine($"{DateTimeOffset.Now:O} {text}");
        Log($"watching container={bound}");
        Process? child = null;
        EventWaitHandle? childStop = null;
        string? lastRoute = null;
        bool manuallyClosed = false;
        long retryAfter = 0;
        void CloseSession()
        {
            if (child == null) return;
            childStop!.Set();
            child.WaitForExit();
            Log($"sync closed pid={child.Id} exit={child.ExitCode}");
            child.Dispose();
            child = null;
            childStop.Dispose();
            childStop = null;
        }
        try
        {
            do
            {
                string? route = null;
                try { route = Endpoint.Selected(enumerator, bound); }
                catch (COMException ex) { Log($"endpoint unavailable {ex.HResult:X8}"); }
                if (route != lastRoute)
                {
                    Log($"default={(route ?? "other or disconnected")}");
                    CloseSession();
                    lastRoute = route;
                    manuallyClosed = false;
                    retryAfter = 0;
                }
                if (child?.HasExited == true)
                {
                    manuallyClosed = child.ExitCode == 0;
                    CloseSession();
                    retryAfter = Environment.TickCount64 + 3000;
                }
                if (resume.WaitOne(0)) { manuallyClosed = false; retryAfter = 0; }
                if (route != null && !manuallyClosed && child == null && Environment.TickCount64 >= retryAfter)
                {
                    string id = Guid.NewGuid().ToString("N");
                    try
                    {
                        if (HidDevice.Paths().Count(path => Endpoint.HidContainer(path) == bound) == 1)
                        {
                            childStop = new EventWaitHandle(false, EventResetMode.ManualReset, SessionStop(id));
                            child = Start("--bound", bound.ToString(), id);
                            Log($"sync started pid={child.Id} endpoint={route}");
                        }
                    }
                    catch (Exception ex) when (ex is IOException or System.ComponentModel.Win32Exception)
                    {
                        childStop?.Dispose();
                        childStop = null;
                        Log($"start deferred: {ex.Message}");
                    }
                }
                // Endpoint changes wake immediately; periodic reconciliation also covers late HID arrival.
            } while (WaitHandle.WaitAny([stop, changed], 5000) != 0);
        }
        finally
        {
            enumerator.UnregisterEndpointNotificationCallback(notifications);
            CloseSession();
            Log("watcher stopped");
        }
    }

    internal static string SessionStop(string id) => @"Local\NunchakuVolumeSync.Session." + id;

    private sealed class Notifications(AutoResetEvent changed) : IMMNotificationClient
    {
        public void OnDefaultDeviceChanged(DataFlow flow, Role role, string id)
        {
            if (flow == DataFlow.Render && role == Role.Multimedia) changed.Set();
        }
        public void OnDeviceAdded(string id) => changed.Set();
        public void OnDeviceRemoved(string id) => changed.Set();
        public void OnDeviceStateChanged(string id, DeviceState state) => changed.Set();
        public void OnPropertyValueChanged(string id, PropertyKey key) { }
    }
}
