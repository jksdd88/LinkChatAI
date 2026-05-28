package ai.linkchat.bridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final int REQUEST_VPN_PERMISSION = 7102;

    private EditText serverUrlInput;
    private EditText deviceSerialInput;
    private TextView accountInfoText;
    private TextView statusText;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        loadConfig();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private ScrollView buildContentView() {
        int padding = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("LinkChat Bridge");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("监听国内抖音通知，并把私信通知上报到 LinkChatAI。网页回复仍由 CrowdMasterAI 负责输入到手机。");
        hint.setPadding(0, 0, 0, dp(16));
        root.addView(hint);

        serverUrlInput = addInput(root, "LinkChatAI 地址，例如 http://192.168.3.206:8002");
        deviceSerialInput = addInput(root, "CrowdMasterAI 设备 serial，例如 192.168.163.130:5555");
        accountInfoText = new TextView(this);
        accountInfoText.setPadding(0, 0, 0, dp(10));
        root.addView(accountInfoText);

        Button saveButton = addButton(root, "保存配置");
        saveButton.setOnClickListener(view -> saveConfig());

        Button permissionButton = addButton(root, "打开通知使用权设置");
        permissionButton.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        Button accessibilityButton = addButton(root, "打开无障碍设置");
        accessibilityButton.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button douyinButton = addButton(root, "打开抖音");
        douyinButton.setOnClickListener(view -> openDouyin());

        Button vpnStartButton = addButton(root, "启动域名探针 VPN");
        vpnStartButton.setOnClickListener(view -> requestStartDomainVpn());

        Button vpnStopButton = addButton(root, "停止域名探针 VPN");
        vpnStopButton.setOnClickListener(view -> stopDomainVpn());

        Button testButton = addButton(root, "发送测试私信到 LinkChatAI");
        testButton.setOnClickListener(view -> sendTestMessage());

        statusText = new TextView(this);
        statusText.setPadding(0, dp(16), 0, 0);
        root.addView(statusText);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        return scrollView;
    }

    private EditText addInput(LinearLayout root, String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(10));
        root.addView(input, params);
        return input;
    }

    private Button addButton(LinearLayout root, String label) {
        Button button = new Button(this);
        button.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(4), 0, dp(4));
        root.addView(button, params);
        return button;
    }

    private void loadConfig() {
        SharedPreferences prefs = BridgeConfig.prefs(this);
        serverUrlInput.setText(BridgeConfig.serverUrl(this));
        deviceSerialInput.setText(BridgeConfig.deviceSerial(this));
        prefs.edit().remove(BridgeConfig.KEY_ACCOUNT_HANDLE).apply();
        updateAccountInfo();
        updatePermissionStatus();
    }

    private void saveConfig() {
        BridgeConfig.prefs(this)
                .edit()
                .putString(BridgeConfig.KEY_SERVER_URL, serverUrlInput.getText().toString().trim())
                .putString(BridgeConfig.KEY_DEVICE_SERIAL, deviceSerialInput.getText().toString().trim())
                .remove(BridgeConfig.KEY_ACCOUNT_HANDLE)
                .apply();
        updateAccountInfo();
        updatePermissionStatus("配置已保存。");
    }

    private void updateAccountInfo() {
        accountInfoText.setText("当前抖音账号：" + BridgeConfig.accountDisplayName(this) + "\n系统会自动关联到这台手机，无需手动填写账号。");
    }

    private void sendTestMessage() {
        saveConfig();
        statusText.setText("正在发送测试消息...");
        new Thread(() -> {
            try {
                String channelMessageId = "apk-test-" + System.currentTimeMillis();
                LinkChatApi.postMobileMessage(
                        getApplicationContext(),
                        "apk-test-conversation",
                        "APK 测试用户",
                        "apk-test-user",
                        "这是一条来自 LinkChat Bridge APK 的测试私信。",
                        channelMessageId
                );
                mainHandler.post(() -> statusText.setText("测试消息已发送到 LinkChatAI。"));
            } catch (Exception exc) {
                mainHandler.post(() -> statusText.setText("测试失败：" + exc.getMessage()));
            }
        }, "linkchat-test-message").start();
    }

    private void openDouyin() {
        PackageManager packageManager = getPackageManager();
        Intent intent = packageManager.getLaunchIntentForPackage(DOUYIN_PACKAGE);
        if (intent == null) {
            statusText.setText("没有找到国内抖音 App：com.ss.android.ugc.aweme");
            return;
        }
        startActivity(intent);
    }

    private void requestStartDomainVpn() {
        saveConfig();
        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent != null) {
            statusText.setText("请在系统弹窗里允许 LinkChat Bridge 建立本机 VPN，用于采集抖音域名线索。");
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION);
            return;
        }
        startDomainVpn();
    }

    private void startDomainVpn() {
        Intent intent = new Intent(this, DouyinDomainVpnService.class);
        intent.setAction(DouyinDomainVpnService.ACTION_START);
        startService(intent);
        updatePermissionStatus("域名探针 VPN 正在启动。");
    }

    private void stopDomainVpn() {
        Intent intent = new Intent(this, DouyinDomainVpnService.class);
        intent.setAction(DouyinDomainVpnService.ACTION_STOP);
        startService(intent);
        updatePermissionStatus("域名探针 VPN 已请求停止。");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_PERMISSION && resultCode == RESULT_OK) {
            startDomainVpn();
        } else if (requestCode == REQUEST_VPN_PERMISSION) {
            updatePermissionStatus("没有获得 VPN 授权，域名探针未启动。");
        }
    }

    private void updatePermissionStatus() {
        updatePermissionStatus("");
    }

    private void updatePermissionStatus(String prefix) {
        String permissionText = isNotificationListenerEnabled()
                ? "通知使用权已开启，可以接收抖音通知。"
                : "通知使用权未开启，请打开后启用 LinkChat Bridge。";
        String accessibilityText = isAccessibilityServiceEnabled()
                ? "无障碍回复代发已开启，可以处理网页回复任务。"
                : "无障碍回复代发未开启，网页回复暂时不会自动发到抖音。";
        String vpnText = "域名探针 VPN：需要点击启动并授权后，才会采集抖音 DNS 域名线索。";
        String text = permissionText + "\n" + accessibilityText + "\n" + vpnText;
        statusText.setText((prefix == null || prefix.trim().isEmpty()) ? text : prefix + "\n" + text);
        updateAccountInfo();
    }

    private boolean isNotificationListenerEnabled() {
        String enabledListeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabledListeners == null) {
            return false;
        }
        return enabledListeners.toLowerCase(Locale.ROOT).contains(getPackageName().toLowerCase(Locale.ROOT));
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
        if (enabledServices == null) {
            return false;
        }
        return enabledServices.toLowerCase(Locale.ROOT).contains(getPackageName().toLowerCase(Locale.ROOT));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
