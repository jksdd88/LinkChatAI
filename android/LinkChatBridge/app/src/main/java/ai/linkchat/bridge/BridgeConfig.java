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
    private static final String DEMO_ACCOUNT_HANDLE = "dy-demo-01";
    private static final String DEMO_ACCOUNT_DISPLAY_NAME = "抖音试用号 01";

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
        String legacy = prefs(context).getString(KEY_ACCOUNT_HANDLE, "");
        if (legacy != null && !legacy.trim().isEmpty() && !DEMO_ACCOUNT_HANDLE.equals(legacy.trim())) {
            return legacy.trim();
        }
        return "dy-device-" + stableToken(deviceSerial(context));
    }

    static String accountDisplayName(Context context) {
        String value = prefs(context).getString(KEY_ACCOUNT_DISPLAY_NAME, "");
        if (isUsefulAccountName(value)) {
            return value.trim();
        }
        return "抖音账号 " + shortSuffix(accountHandle(context));
    }

    static void rememberAccountDisplayName(Context context, String value) {
        if (!isUsefulAccountName(value)) {
            return;
        }
        prefs(context).edit().putString(KEY_ACCOUNT_DISPLAY_NAME, value.trim()).apply();
    }

    private static boolean isUsefulAccountName(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        return !text.isEmpty()
                && !DEMO_ACCOUNT_DISPLAY_NAME.equals(text)
                && !"抖音".equals(text)
                && !"抖音消息".equals(text)
                && !"消息".equals(text)
                && !"私信".equals(text);
    }

    private static String stableToken(String value) {
        return Integer.toHexString((value == null ? "" : value.trim()).hashCode());
    }

    private static String shortSuffix(String value) {
        String token = stableToken(value);
        return token.length() <= 4 ? token : token.substring(token.length() - 4);
    }
}
