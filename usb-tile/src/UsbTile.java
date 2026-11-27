package cn.local.usbtile;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import java.util.TreeSet;

public class UsbTile extends TileService {
    private static final String EXCLUSIVE = "exclusive";
    private static final String DEVICE_SESSION = "device_session";
    private boolean watchingUsb;
    private final BroadcastReceiver onUsbChange = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    private static boolean hasPlaybackEndpoint(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_AUDIO || intf.getInterfaceSubclass() != 2)
                continue;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint endpoint = intf.getEndpoint(j);
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT
                        && endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) return true;
            }
        }
        return false;
    }
    static String deviceSession(Context context) {
        TreeSet<String> paths = new TreeSet<>();
        for (UsbDevice device : context.getSystemService(UsbManager.class).getDeviceList().values()) {
            if (hasPlaybackEndpoint(device)) paths.add(device.getDeviceName());
        }
        if (paths.isEmpty()) return "";
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        return boot + ":" + String.join(",", paths);
    }
    private static SharedPreferences readings(Context context) {
        return context.getSharedPreferences("usb_switch", MODE_PRIVATE);
    }
    static boolean hasReading(Context context, String session) {
        SharedPreferences prefs = readings(context);
        return prefs.contains(EXCLUSIVE) && session.equals(prefs.getString(DEVICE_SESSION, null));
    }
    static void remember(Context context, boolean enabled) {
        SharedPreferences prefs = readings(context);
        String session = deviceSession(context);
        if (!prefs.contains(EXCLUSIVE) || prefs.getBoolean(EXCLUSIVE, false) != enabled
                || !session.equals(prefs.getString(DEVICE_SESSION, null))) {
            prefs.edit().putBoolean(EXCLUSIVE, enabled).putString(DEVICE_SESSION, session).apply();
        }
    }
    private final SharedPreferences.OnSharedPreferenceChangeListener onReading =
        (prefs, key) -> render();
    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::open);
        else open();
    }
    private void open() {
        android.util.Log.i("CloudUsbTile", "快捷磁贴已触发");
        android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
        if (android.os.Build.VERSION.SDK_INT >= 35)
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        startActivityAndCollapse(PendingIntent.getActivity(this, 0,
            new Intent(this, ToggleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT, options.toBundle()));
    }
    @Override public void onStartListening() {
        super.onStartListening();
        readings(this).registerOnSharedPreferenceChangeListener(onReading);
        if (!watchingUsb) {
            IntentFilter changes = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
            changes.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            registerReceiver(onUsbChange, changes, Context.RECEIVER_NOT_EXPORTED);
            watchingUsb = true;
        }
        render();
    }
    @Override public void onStopListening() {
        readings(this).unregisterOnSharedPreferenceChangeListener(onReading);
        if (watchingUsb) {
            unregisterReceiver(onUsbChange);
            watchingUsb = false;
        }
        super.onStopListening();
    }
    private void render() {
        Tile tile = getQsTile();
        if (tile != null) {
            SharedPreferences prefs = readings(this);
            String session = deviceSession(this);
            boolean known = hasReading(this, session);
            boolean enabled = !session.isEmpty() && known && prefs.getBoolean(EXCLUSIVE, false);
            String status;
            if (session.isEmpty()) status = "未连接 DAC";
            else if (!known) status = "待确认";
            else status = enabled ? "已开启" : "已关闭";
            tile.setLabel("USB 独占");
            tile.setSubtitle(status);
            tile.setStateDescription(status);
            tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }
}
