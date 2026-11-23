using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;

internal static class Endpoint
{
    [StructLayout(LayoutKind.Sequential)]
    private struct PropertyKey(Guid format, uint id)
    {
        public Guid Format = format;
        public uint Id = id;
    }

    [DllImport("cfgmgr32", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint CM_Locate_DevNodeW(out uint node, string id, uint flags);
    [DllImport("cfgmgr32", ExactSpelling = true)]
    private static extern uint CM_Get_DevNode_PropertyW(uint node, ref PropertyKey key, out uint type,
        out Guid value, ref uint bytes, uint flags);
    [DllImport("cfgmgr32", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint CM_Get_Device_Interface_PropertyW(string path, ref PropertyKey key, out uint type,
        [Out] char[] value, ref uint bytes, uint flags);

    private static Guid? Container(string instance)
    {
        var key = new PropertyKey(new("8c7ed206-3f8a-4827-b3ab-ae9e1faefc6c"), 2);
        uint bytes = 16;
        if (CM_Locate_DevNodeW(out uint node, instance, 0) == 0 &&
            CM_Get_DevNode_PropertyW(node, ref key, out uint type, out Guid id, ref bytes, 0) == 0 &&
            type == 13 && bytes == 16 && id != Guid.Empty) return id;
        return null;
    }

    internal static Guid? HidContainer(string hidPath)
    {
        var instanceKey = new PropertyKey(new("78c34fc8-104a-4aca-9ea4-524d52996e57"), 256);
        var buffer = new char[512];
        uint bytes = (uint)buffer.Length * 2;
        if (CM_Get_Device_Interface_PropertyW(hidPath, ref instanceKey, out uint type, buffer, ref bytes, 0) != 0 || type != 18)
            return null;
        return Container(new string(buffer, 0, (int)bytes / 2 - 1));
    }

    internal static string? DefaultId(MMDeviceEnumerator enumerator)
    {
        try
        {
            using var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            return device.ID;
        }
        catch (COMException ex) when ((uint)ex.HResult == 0x80070490) { return null; }
    }

    internal static string? Selected(MMDeviceEnumerator enumerator, Guid bound)
    {
        string? id = DefaultId(enumerator);
        return id != null && Container(@"SWD\MMDEVAPI\" + id) == bound ? id : null;
    }

    internal static MMDevice Find(MMDeviceEnumerator enumerator, string hidPath, Action<string> log)
    {
        var container = HidContainer(hidPath) ?? throw new IOException("HID container unavailable.");
        var matches = new List<MMDevice>();
        foreach (var endpoint in enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            if (Container(@"SWD\MMDEVAPI\" + endpoint.ID) == container) matches.Add(endpoint);
            else endpoint.Dispose();
        }
        if (matches.Count != 1)
        {
            foreach (var endpoint in matches) endpoint.Dispose();
            throw new IOException($"Expected one active playback endpoint for {container}, found {matches.Count}.");
        }
        var target = matches[0];
        string defaultInfo = "unknown";
        try
        {
            using var defaultDevice = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            defaultInfo = (target.ID == defaultDevice.ID).ToString();
        }
        catch (COMException ex) when ((uint)ex.HResult == 0x80070490) { defaultInfo = "none"; }
        log($"container={container} endpoint={target.ID} name={target.FriendlyName} defaultMultimedia={defaultInfo}");
        return target;
    }
}
