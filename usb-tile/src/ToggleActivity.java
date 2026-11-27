package cn.local.usbtile;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class ToggleActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Navigation navigation = Navigation.instance;
        if (navigation == null) {
            Toast.makeText(this, "请启用「网易云 USB 独占」无障碍服务", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else if (navigation.begin(TileService.ACTION_QS_TILE_PREFERENCES.equals(getIntent().getAction()))) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(Navigation.MUSIC);
            if (launch != null) startActivity(launch);
            else navigation.finish("找不到网易云音乐");
        }
        finish();
    }
}
