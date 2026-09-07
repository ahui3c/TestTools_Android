package tw.chehu.testtools;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BrightnessPresetReceiver extends BroadcastReceiver {
    public static final String ACTION_SAVE = "tw.chehu.testtools.action.SAVE_BRIGHTNESS_PRESET";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SAVE.equals(intent.getAction())) return;
        int value = intent.getIntExtra("brightness_value", -1);
        int maximum = intent.getIntExtra("brightness_maximum", 255);
        double targetNit = numberExtra(intent, "target_nit");
        double measuredNit = numberExtra(intent, "measured_nit");
        String name = intent.getStringExtra("preset_name");
        if (value < 0 || value > 100000 || maximum < value || maximum > 100000
                || !validNit(targetNit) || !validNit(measuredNit)) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("INVALID_BRIGHTNESS_PRESET");
            return;
        }
        BrightnessPresetStore.add(context, name, value, maximum, targetNit, measuredNit,
                "AndroidADBTools 自動校正");
        setResultCode(Activity.RESULT_OK);
        setResultData("SAVED");
    }

    private boolean validNit(double value) {
        return Double.isNaN(value) || (Double.isFinite(value) && value >= 0 && value <= 100000);
    }

    private double numberExtra(Intent intent, String key) {
        Object value = intent.getExtras() == null ? null : intent.getExtras().get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }
}
