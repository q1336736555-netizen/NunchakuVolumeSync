using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace Nunchaku.Usb;

public static class HidDevice
{
    private const string InterfaceId = "hid#vid_2fc6&pid_f0b9&mi_02";

    public static string Find()
    {
        var matches = Paths();
        if (matches.Length != 1)
            throw new IOException($"Expected one DC-Nunchaku MI_02 HID collection, found {matches.Length}.");
        return matches[0];
    }

    public static string[] Paths()
    {
        HidD_GetHidGuid(out var guid);
        CheckConfig(CM_Get_Device_Interface_List_SizeW(out var length, ref guid, null, 0));
        var buffer = new char[length];
        CheckConfig(CM_Get_Device_Interface_ListW(ref guid, null, buffer, length, 0));
        return new string(buffer).Split('\0', StringSplitOptions.RemoveEmptyEntries)
            .Where(path => path.Contains(InterfaceId, StringComparison.OrdinalIgnoreCase)).ToArray();
    }

    public static SafeFileHandle Open(string path, FileAccess access)
    {
        uint desired = access == FileAccess.Read ? 0x80000000u : 0x40000000u;
        var handle = CreateFileW(path, desired, 3, IntPtr.Zero, 3, 0x40000000, IntPtr.Zero);
        if (handle.IsInvalid)
        {
            int error = Marshal.GetLastWin32Error();
            handle.Dispose();
            throw new Win32Exception(error, $"Open HID for {access} failed (Win32 {error}).");
        }
        return handle;
    }

    public static unsafe uint Write(SafeFileHandle handle, byte[] report)
    {
        using var signal = new EventWaitHandle(false, EventResetMode.ManualReset);
        var operation = new NativeOverlapped { EventHandle = signal.SafeWaitHandle.DangerousGetHandle() };
        fixed (byte* buffer = report)
        {
            if (!WriteFile(handle, buffer, (uint)report.Length, IntPtr.Zero, &operation))
            {
                int error = Marshal.GetLastWin32Error();
                if (error != 997) throw new Win32Exception(error);
            }
            if (!signal.WaitOne(1500))
            {
                CancelIoEx(handle, &operation);
                GetOverlappedResult(handle, &operation, out _, true);
                throw new TimeoutException("HID write timeout. Device state is unknown.");
            }
            if (!GetOverlappedResult(handle, &operation, out var count, false))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            return count;
        }
    }

    public static unsafe string CheckCaps(SafeFileHandle handle)
    {
        if (!HidD_GetPreparsedData(handle, out var data))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        try
        {
            if (HidP_GetCaps(data, out var caps) != 0x110000)
                throw new IOException("HidP_GetCaps failed.");
            string summary = $"page={caps.Page:X4} usage={caps.Usage:X4} input={caps.Input} output={caps.Output} feature={caps.Feature}";
            if (caps.Page != 12 || caps.Usage != 1 || caps.Input != 9 || caps.Output != 9 || caps.Feature != 0)
                throw new IOException($"Unexpected HID descriptor: {summary}");
            // HIDP_VALUE_CAPS is 72 bytes. Confirm the single unnumbered 8 x 8-bit field in each direction.
            foreach (int type in new[] { 0, 1 })
            {
                ushort count = 1;
                byte[] value = new byte[72];
                if (HidP_GetValueCaps(type, value, ref count, data) != 0x110000 || count != 1 ||
                    value[2] != 0 || BitConverter.ToUInt16(value, 18) != 8 || BitConverter.ToUInt16(value, 20) != 8)
                    throw new IOException($"Unexpected HID value caps for report type {type}.");
            }
            return summary + " reportId=0 payload=8";
        }
        finally { HidD_FreePreparsedData(data); }
    }

    private static void CheckConfig(uint code)
    {
        if (code != 0) throw new IOException($"HID enumeration failed (CONFIGRET 0x{code:X}).");
    }

    [StructLayout(LayoutKind.Sequential)]
    private unsafe struct Caps
    {
        public ushort Usage, Page, Input, Output, Feature;
        public fixed ushort Remaining[27];
    }

    [DllImport("hid")] private static extern void HidD_GetHidGuid(out Guid guid);
    [DllImport("cfgmgr32", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint CM_Get_Device_Interface_List_SizeW(out uint length, ref Guid guid, string? id, uint flags);
    [DllImport("cfgmgr32", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint CM_Get_Device_Interface_ListW(ref Guid guid, string? id, [Out] char[] paths, uint length, uint flags);
    [DllImport("kernel32", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    private static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr security, uint mode, uint flags, IntPtr template);
    [DllImport("kernel32", SetLastError = true)] private static extern unsafe bool WriteFile(SafeFileHandle file, byte* data, uint size, IntPtr bytes, NativeOverlapped* operation);
    [DllImport("kernel32", SetLastError = true)] private static extern unsafe bool GetOverlappedResult(SafeFileHandle file, NativeOverlapped* operation, out uint bytes, bool wait);
    [DllImport("kernel32", SetLastError = true)] private static extern unsafe bool CancelIoEx(SafeFileHandle file, NativeOverlapped* operation);
    [DllImport("hid", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.U1)] private static extern bool HidD_GetPreparsedData(SafeFileHandle file, out IntPtr data);
    [DllImport("hid")]
    [return: MarshalAs(UnmanagedType.U1)] private static extern bool HidD_FreePreparsedData(IntPtr data);
    [DllImport("hid")] private static extern int HidP_GetCaps(IntPtr data, out Caps caps);
    [DllImport("hid")] private static extern int HidP_GetValueCaps(int type, [Out] byte[] values, ref ushort count, IntPtr data);
}
