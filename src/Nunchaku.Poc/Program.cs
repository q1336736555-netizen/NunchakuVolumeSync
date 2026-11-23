using System.ComponentModel;
using System.Runtime.Versioning;
using Nunchaku.Usb;
using static Nunchaku.Usb.HidReports;

[assembly: SupportedOSPlatform("windows")]

internal static class Program
{

    public static async Task<int> Main(string[] args)
    {
        if (args.Length == 0) args = ["get"];
        bool listen = args is ["listen", _];
        int expected = 0;
        bool down = args is ["down-one", _] && int.TryParse(args[1], out expected) && expected is >= 2 and <= 10;
        if (!(args is ["get"] || down || (listen && int.TryParse(args[1], out int seconds) && seconds is >= 1 and <= 300)))
        {
            Console.Error.WriteLine("Nunchaku.Poc.exe get | listen <seconds: 1..300> | down-one <current OLED VOL: 2..10>");
            return 2;
        }
        string dir = Path.Combine(AppContext.BaseDirectory, "logs");
        Directory.CreateDirectory(dir);
        string logPath = Path.Combine(dir, $"hid-{DateTime.Now:yyyyMMdd-HHmmss-fff}-{Environment.ProcessId}.log");
        using var file = new StreamWriter(logPath) { AutoFlush = true };
        void Log(string message)
        {
            lock (file)
            {
                string line = $"{DateTimeOffset.Now:O} {message}";
                file.WriteLine(line);
                Console.WriteLine(line);
            }
        }
        try
        {
            Log($"command={string.Join(' ', args)} log={logPath}");
            string path = HidDevice.Find();
            Log($"device={path}");
            using var inputHandle = HidDevice.Open(path, FileAccess.Read);
            Log(HidDevice.CheckCaps(inputHandle));
            using var input = new FileStream(inputHandle, FileAccess.Read, 1, isAsync: true);
            if (listen)
            {
                int reports = 0;
                using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(int.Parse(args[1])));
                try
                {
                    while (true)
                    {
                        var frame = await Read(input, Log, deadline.Token);
                        reports++;
                        if (frame[1] == 0 && frame[2] == 0x12 && frame[5] <= 100)
                            Log($"volume-notification VOL={frame[5]}");
                    }
                }
                catch (OperationCanceledException) when (deadline.IsCancellationRequested)
                {
                    Log($"listen finished reports={reports}");
                }
            }
            else
            {
                // Allow passive listeners, but serialize requests from POC instances in this session.
                using var lease = new EventWaitHandle(false, EventResetMode.ManualReset, @"Local\Nunchaku.Poc.MI02.Request", out bool acquired);
                if (!acquired) throw new IOException("Another POC request is active.");
                using var output = HidDevice.Open(path, FileAccess.Write);
                int volume = await Exchange(input, output, Query, Log) ?? throw new TimeoutException("No GET response.");
                Log($"query response VOL={volume}");
                if (down)
                {
                    if (volume != expected)
                        throw new IOException($"OLED argument {expected} differs from device {volume}. Stopping before SET.");
                    byte target = (byte)(volume - 1);
                    byte[] set = SetVolume(target);
                    int? feedback = await Exchange(input, output, set, Log);
                    Log(feedback.HasValue ? $"SET feedback VOL={feedback} target={target}" : "No SET feedback. Checking with GET.");
                    int actual = await Exchange(input, output, Query, Log) ?? throw new TimeoutException("No independent GET response.");
                    Log($"independent GET VOL={actual} target={target}");
                    if (actual != target || feedback.HasValue && feedback != target) throw new IOException("SET feedback or readback mismatch.");
                }
            }
            return 0;
        }
        catch (Exception e)
        {
            Log($"ERROR {e.GetType().Name} hresult=0x{e.HResult:X8} {e.Message}" +
                (e is Win32Exception w ? $" win32={w.NativeErrorCode}" : ""));
            return 1;
        }
    }

}
