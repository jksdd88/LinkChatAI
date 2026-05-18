package ai.linkchat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

final class BridgeConfig {
    static final String PREFS = "linkchat_bridge";
    static final String KEY_SERVER_URL = "server_url";
    static final String KEY_DEVICE_SERIAL = "device_serial";
    static final String KEY_ACCOUNT_HANDLE = "account_handle";
    static final String KEY_ACCOUNT_DISPLAY_NAME = "account_display_name";

    private BridgeConfig() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String serverUrl(Context context) {
        return prefs(context).getString(KEY_SERVER_URL, context.getString(R.string.default_server_url));
    }

    static String deviceSerial(Context context) {
        String fallback = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String value = prefs(context).getString(KEY_DEVICE_SERIAL, "");
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    static String accountHandle(Context context) {
        String value = prefs(context).getString(KEY_ACCOUNT_HANDLE, "dy-demo-01");
        return value == null || value.trim().isEmpty() ? "dy-demo-01" : value.trim();
    }

    static String accountDisplayName(Context context) {
        String value = prefs(context).getString(KEY_ACCOUNT_DISPLAY_NAME, "抖音试用号 01");
        return value == null || value.trim().isEmpty() ? accountHandle(context) : value.trim();
    }
}
