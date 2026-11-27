using System.Threading.Channels;
using System.Diagnostics;
using NAudio.CoreAudioApi;
using Nunchaku.Usb;

internal static class Sync
{
    private enum Origin { Windows, Knob }
    private readonly record struct Change(Origin Source, long Received);
    private static int Percent(float value) => Math.Clamp((int)Math.Round(value * 100, MidpointRounding.AwayFromZero), 0, 100);

    internal static async Task Run(string hid, AudioEndpointVolume audio, Func<byte[], CancellationToken, Task<int>> request,
        Action<string> log, bool lowTest, CancellationToken stop, Action<int>? showVolume, Action checkRoute)
    {
        var events = Channel.CreateBounded<Change>(new BoundedChannelOptions(1)
        {
            FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true
        });
        var context = Guid.NewGuid();
        audio.NotificationGuid = context;
        void OnWindows(AudioVolumeNotificationData data)
        {
            if (data.EventContext != context) events.Writer.TryWrite(new(Origin.Windows, Stopwatch.GetTimestamp()));
        }
        using var running = CancellationTokenSource.CreateLinkedTokenSource(stop);
        using var handle = HidDevice.Open(hid, FileAccess.Read);
        using var input = new FileStream(handle, FileAccess.Read, 1, true);
        async Task Listen()
        {
            try
            {
                while (!running.IsCancellationRequested)
                {
                    var frame = await HidReports.Read(input, s => log("monitor " + s), running.Token);
                    if (frame[1] == 0 && frame[2] == 0x12 && frame[5] <= 100)
                        events.Writer.TryWrite(new(Origin.Knob, Stopwatch.GetTimestamp()));
                }
            }
            catch (OperationCanceledException) when (running.IsCancellationRequested) { }
            catch (Exception ex) { events.Writer.TryComplete(ex); }
        }
        void Display(int hardware)
        {
            checkRoute();
            if (Percent(audio.MasterVolumeLevelScalar) != hardware)
                audio.MasterVolumeLevelScalar = hardware / 100f;
            log($"hardware->Windows VOL={hardware} Windows={Percent(audio.MasterVolumeLevelScalar)} dB={audio.MasterVolumeLevel:F2} mute={audio.Mute}");
            showVolume?.Invoke(hardware);
        }
        async Task<int> ReadHardware(CancellationToken token)
        {
            for (int attempt = 1; ; attempt++)
            {
                try { return await request(HidReports.Query, token); }
                catch (TimeoutException) when (!lowTest)
                {
                    log($"Volume query timed out (attempt {attempt}). Retrying on the current endpoint.");
                    await Task.Delay(100, token);
                }
            }
        }
        async Task<int?> ReadUnlessSuperseded()
        {
            if (lowTest) return await ReadHardware(stop);
            if (events.Reader.TryPeek(out _)) return null;
            using var pending = CancellationTokenSource.CreateLinkedTokenSource(stop);
            var change = events.Reader.WaitToReadAsync(pending.Token).AsTask();
            var read = ReadHardware(pending.Token);
            try
            {
                if (await Task.WhenAny(read, change) == change)
                    pending.Cancel();
                return await read;
            }
            catch (OperationCanceledException) when (!stop.IsCancellationRequested && pending.IsCancellationRequested)
            {
                return null;
            }
            finally { pending.Cancel(); }
        }
        var listener = Listen();
        audio.OnVolumeNotification += OnWindows;
        try
        {
            int initial = await ReadHardware(stop);
            int? hardware = initial;
            if (lowTest && hardware > 10) throw new IOException("Low-volume test requires hardware VOL <= 10.");
            if (!events.Reader.TryPeek(out _)) Display(initial);
            log($"READY limit={(lowTest ? 10 : 100)}");
            while (await events.Reader.WaitToReadAsync(stop))
            {
                // The first event runs immediately. While USB is busy the channel retains the latest event.
                if (!events.Reader.TryRead(out var change)) continue;
                if (change.Source == Origin.Knob)
                {
                    hardware = await ReadUnlessSuperseded();
                    // A newer user action wins if it arrived during the round trip.
                    if (hardware is int level && !events.Reader.TryPeek(out _)) Display(level);
                    continue;
                }
                int target = Percent(audio.MasterVolumeLevelScalar);
                if (lowTest && target > 10)
                {
                    hardware = await ReadHardware(stop);
                    if (!events.Reader.TryPeek(out _)) Display(hardware.Value);
                    continue;
                }
                if (target == hardware)
                {
                    // A mute-only or duplicate event usually needs no write. A concurrent knob change
                    // might have overwritten the previous state before its notification was processed.
                    hardware = await ReadUnlessSuperseded();
                    if (hardware is null) continue;
                    log($"Windows event level={target} mute={audio.Mute} hardware={hardware}");
                    if (target == hardware) continue;
                }
                if (events.Reader.TryPeek(out _)) continue;
                for (int attempt = 1; ; attempt++)
                {
                    if (events.Reader.TryPeek(out _) || Percent(audio.MasterVolumeLevelScalar) != target) break;
                    int? feedback = null;
                    var issuing = Stopwatch.GetTimestamp();
                    try { feedback = await request(HidReports.SetVolume(target), stop); }
                    catch (TimeoutException) { log("SET timed out. Reading hardware volume before retrying."); }
                    double toFeedback = Stopwatch.GetElapsedTime(change.Received).TotalMilliseconds;
                    hardware = await ReadUnlessSuperseded();
                    if (hardware is null) break;
                    double toIssue = Stopwatch.GetElapsedTime(change.Received, issuing).TotalMilliseconds;
                    log($"Windows->hardware target={target} feedback={feedback?.ToString() ?? "missing"} readback={hardware} eventToIssueMs={toIssue:F2} eventToFeedbackMs={toFeedback:F2}");
                    if (hardware == target)
                    {
                        showVolume?.Invoke(hardware.Value);
                        break;
                    }
                    if (events.Reader.TryPeek(out _) || Percent(audio.MasterVolumeLevelScalar) != target) break;
                    if (feedback.HasValue || attempt >= 3)
                    {
                        Display(hardware.Value);
                        throw new IOException("Independent readback differs from target.");
                    }
                }
            }
        }
        finally
        {
            audio.OnVolumeNotification -= OnWindows;
            running.Cancel();
            await listener;
        }
    }
}
