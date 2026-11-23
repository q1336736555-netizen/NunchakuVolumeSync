using Microsoft.Win32.SafeHandles;
using System.Runtime.Versioning;
[assembly: SupportedOSPlatform("windows")]

namespace Nunchaku.Usb;

public static class HidReports
{
    public static byte[] Query => [0, 0x42, 0x11, 0xee, 0, 0, 0, 0, 0];
    public static byte[] SetVolume(int volume)
    {
        if (volume is < 0 or > 100) throw new ArgumentOutOfRangeException(nameof(volume));
        byte value = (byte)volume;
        return [0, 1, 0x10, 0xef, value, value, 0, 0, 0];
    }
    public static async Task<int?> Exchange(FileStream input, SafeFileHandle output, byte[] request, Action<string> log, CancellationToken stop = default)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(stop);
        deadline.CancelAfter(TimeSpan.FromSeconds(3));
        var pending = Read(input, log, deadline.Token);
        bool sent = false;
        try
        {
            // A new handle can already have queued input. Drain it before issuing our query.
            while (pending.IsCompleted)
            {
                await pending;
                log("discarding input queued before request");
                pending = Read(input, log, deadline.Token);
            }
            stop.ThrowIfCancellationRequested();
            Write(output, request, log);
            sent = true;
            while (true)
            {
                var reply = await pending;
                if (reply[1] == request[1])
                {
                    if (reply[5] > 100) throw new InvalidDataException($"Invalid VOL {reply[5]}.");
                    return reply[5];
                }
                pending = Read(input, log, deadline.Token);
            }
        }
        catch (OperationCanceledException) when (sent && deadline.IsCancellationRequested && !stop.IsCancellationRequested)
        {
            log($"response timeout seq=0x{request[1]:X2}");
            return null;
        }
        finally
        {
            deadline.Cancel();
            try { await pending; } catch (Exception) when (pending.IsCanceled || pending.IsFaulted) { }
        }
    }

    public static void Write(SafeFileHandle output, byte[] frame, Action<string> log)
    {
        log($"OUT request len={frame.Length} {BitConverter.ToString(frame)}");
        uint sent = HidDevice.Write(output, frame);
        log($"OUT completed bytes={sent}");
        if (sent != frame.Length) throw new IOException("Short HID write.");
    }

    public static async Task<byte[]> Read(FileStream input, Action<string> log, CancellationToken token)
    {
        byte[] frame = new byte[9];
        int count = await input.ReadAsync(frame.AsMemory(), token);
        log($"IN bytes={count} {BitConverter.ToString(frame, 0, count)}");
        if (count != frame.Length || frame[0] != 0) throw new InvalidDataException("Unexpected input report length or report ID.");
        return frame;
    }
}
