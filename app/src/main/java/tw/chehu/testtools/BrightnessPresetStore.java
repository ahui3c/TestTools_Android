package tw.chehu.testtools;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class BrightnessPresetStore {
    private static final String PREFS = "brightness_presets";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 100;

    static final class Preset {
        String id;
        String name;
        int value;
        int maximum;
        double targetNit;
        double measuredNit;
        String source;
        long savedAt;
    }

    private BrightnessPresetStore() {}

    static synchronized Preset add(Context context, String name, int value, int maximum,
                                   double targetNit, double measuredNit, String source) {
        Preset preset = new Preset();
        preset.id = UUID.randomUUID().toString();
        preset.name = cleanName(name, value, targetNit);
        preset.value = value;
        preset.maximum = maximum;
        preset.targetNit = targetNit;
        preset.measuredNit = measuredNit;
        preset.source = source == null ? "TestTools" : source;
        preset.savedAt = System.currentTimeMillis();

        List<Preset> items = load(context);
        items.add(0, preset);
        if (items.size() > MAX_ITEMS) items = new ArrayList<>(items.subList(0, MAX_ITEMS));
        save(context, items);
        return preset;
    }

    static synchronized List<Preset> load(Context context) {
        ArrayList<Preset> result = new ArrayList<>();
        String raw = preferences(context).getString(KEY_ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Preset preset = new Preset();
                preset.id = item.optString("id", UUID.randomUUID().toString());
                preset.name = item.optString("name", "亮度設定");
                preset.value = item.optInt("value", -1);
                preset.maximum = item.optInt("maximum", 255);
                preset.targetNit = item.optDouble("targetNit", Double.NaN);
                preset.measuredNit = item.optDouble("measuredNit", Double.NaN);
                preset.source = item.optString("source", "TestTools");
                preset.savedAt = item.optLong("savedAt", 0L);
                if (preset.value >= 0 && preset.value <= 100000) result.add(preset);
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    static synchronized void remove(Context context, String id) {
        List<Preset> items = load(context);
        items.removeIf(item -> item.id.equals(id));
        save(context, items);
    }

    private static void save(Context context, List<Preset> items) {
        JSONArray array = new JSONArray();
        for (Preset preset : items) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", preset.id);
                item.put("name", preset.name);
                item.put("value", preset.value);
                item.put("maximum", preset.maximum);
                if (Double.isFinite(preset.targetNit)) item.put("targetNit", preset.targetNit);
                if (Double.isFinite(preset.measuredNit)) item.put("measuredNit", preset.measuredNit);
                item.put("source", preset.source);
                item.put("savedAt", preset.savedAt);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        preferences(context).edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String cleanName(String name, int value, double targetNit) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80);
        if (!cleaned.isEmpty()) return cleaned;
        if (Double.isFinite(targetNit)) return "自動校正 " + Math.round(targetNit) + " nit";
        return "亮度 " + value;
    }
}
