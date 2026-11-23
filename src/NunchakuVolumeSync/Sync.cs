using System.Threading.Channels;
using System.Diagnostics;
using NAudio.CoreAudioApi;
using Nunchaku.Usb;

internal static class Sync
{
    private enum Origin { Windows, Knob }
    private readonly record struct Change(Origin Source, long Received);
    private static int Percent(float value) => Math.Clamp((int)Math.Round(value * 100, MidpointRounding.AwayFromZero), 0, 100);

    internal static async Task Run(string hid, AudioEndpointVolume audio, Func<byte[], Task<int>> request,
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
        var listener = Listen();
        audio.OnVolumeNotification += OnWindows;
        try
        {
            int hardware = await request(HidReports.Query);
            if (lowTest && hardware > 10) throw new IOException("Low-volume test requires hardware VOL <= 10.");
            Display(hardware);
            log($"READY limit={(lowTest ? 10 : 100)}");
            while (await events.Reader.WaitToReadAsync(stop))
            {
                // The first event runs immediately. While USB is busy the channel retains the latest event.
                if (!events.Reader.TryRead(out var change)) continue;
                if (change.Source == Origin.Knob)
                {
                    hardware = await request(HidReports.Query);
                    // A newer user action wins if it arrived during the round trip.
                    if (!events.Reader.TryPeek(out _)) Display(hardware);
                    continue;
                }
                int target = Percent(audio.MasterVolumeLevelScalar);
                if (lowTest && target > 10)
                {
                    hardware = await request(HidReports.Query);
                    if (!events.Reader.TryPeek(out _)) Display(hardware);
                    continue;
                }
                if (target == hardware)
                {
                    // A mute-only or duplicate event usually needs no write. A concurrent knob change
                    // might have overwritten the previous state before its notification was processed.
                    hardware = await request(HidReports.Query);
                    log($"Windows event level={target} mute={audio.Mute} hardware={hardware}");
                    if (target == hardware) continue;
                }
                if (events.Reader.TryPeek(out _)) continue;
                var issuing = Stopwatch.GetTimestamp();
                int feedback = await request(HidReports.SetVolume(target));
                double toFeedback = Stopwatch.GetElapsedTime(change.Received).TotalMilliseconds;
                int readback = await request(HidReports.Query);
                hardware = readback;
                double toIssue = Stopwatch.GetElapsedTime(change.Received, issuing).TotalMilliseconds;
                log($"Windows->hardware target={target} feedback={feedback} readback={readback} eventToIssueMs={toIssue:F2} eventToFeedbackMs={toFeedback:F2}");
                if (readback != target || feedback != target)
                {
                    if (events.Reader.TryPeek(out _)) continue;
                    Display(readback);
                    throw new IOException("SET feedback or independent readback differs from target.");
                }
                showVolume?.Invoke(readback);
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
