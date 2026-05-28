package ai.linkchat.bridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DouyinNetworkActivityMonitor {
    private static final String TAG = "LinkChatNetwork";
    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String DOUYIN_LITE_PACKAGE = "com.ss.android.ugc.aweme.lite";
    private static final long MIN_RX_DELTA_BYTES = 2048L;
    private static final long MIN_TX_DELTA_BYTES = 1024L;
    private static final long SIGNAL_COOLDOWN_MS = 15000L;
    private static final int MAX_ENDPOINTS = 8;

    private int douyinUid = -1;
    private String douyinPackage = "";
    private long lastRxBytes = -1L;
    private long lastTxBytes = -1L;
    private long lastSignalAt = 0L;

    void poll(Context context) {
        ensureDouyinUid(context);
        if (douyinUid < 0) {
            return;
        }

        long rxBytes = TrafficStats.getUidRxBytes(douyinUid);
        long txBytes = TrafficStats.getUidTxBytes(douyinUid);
        if (rxBytes == TrafficStats.UNSUPPORTED || txBytes == TrafficStats.UNSUPPORTED) {
            return;
        }
        if (lastRxBytes < 0 || lastTxBytes < 0) {
            lastRxBytes = rxBytes;
            lastTxBytes = txBytes;
            return;
        }

        long rxDelta = Math.max(0L, rxBytes - lastRxBytes);
        long txDelta = Math.max(0L, txBytes - lastTxBytes);
        lastRxBytes = rxBytes;
        lastTxBytes = txBytes;

        if (rxDelta < MIN_RX_DELTA_BYTES && txDelta < MIN_TX_DELTA_BYTES) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSignalAt < SIGNAL_COOLDOWN_MS) {
            return;
        }
        lastSignalAt = now;
        postNetworkSignal(context, rxDelta, txDelta, now, collectDouyinEndpoints());
    }

    private void ensureDouyinUid(Context context) {
        if (douyinUid >= 0) {
            return;
        }
        PackageManager packageManager = context.getPackageManager();
        String[] packages = {DOUYIN_PACKAGE, DOUYIN_LITE_PACKAGE};
        for (String packageName : packages) {
            try {
                ApplicationInfo info = packageManager.getApplicationInfo(packageName, 0);
                douyinUid = info.uid;
                douyinPackage = packageName;
                Log.i(TAG, "Monitoring Douyin network counters for " + packageName + " uid=" + douyinUid);
                return;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Try the next known Douyin package.
            }
        }
    }

    private void postNetworkSignal(Context context, long rxDelta, long txDelta, long now, List<String> endpoints) {
        String endpointText = endpoints.isEmpty()
                ? "endpoints=未读取到连接表，可能被系统权限限制或当时没有活跃连接"
                : "endpoints=" + join(endpoints);
        String body = String.format(
                Locale.US,
                "检测到抖音网络活跃：package=%s uid=%d rx=+%dB tx=+%dB %s。这只是新消息线索，不包含请求正文。",
                douyinPackage,
                douyinUid,
                rxDelta,
                txDelta,
                endpointText
        );
        String channelMessageId = String.format(
                Locale.US,
                "douyin-network-signal-%d-%d-%d",
                douyinUid,
                now / SIGNAL_COOLDOWN_MS,
                rxDelta + txDelta
        );
        try {
            LinkChatApi.postMobileSignal(context, body, channelMessageId);
            Log.i(TAG, body);
        } catch (Exception exc) {
            Log.w(TAG, "Failed to post Douyin network signal", exc);
        }
    }

    private List<String> collectDouyinEndpoints() {
        Set<String> endpoints = new LinkedHashSet<>();
        readProcNet(endpoints, "/proc/net/tcp", "tcp4", false);
        readProcNet(endpoints, "/proc/net/tcp6", "tcp6", true);
        readProcNet(endpoints, "/proc/net/udp", "udp4", false);
        readProcNet(endpoints, "/proc/net/udp6", "udp6", true);
        return new ArrayList<>(endpoints);
    }

    private void readProcNet(Set<String> endpoints, String path, String protocol, boolean ipv6) {
        if (endpoints.size() >= MAX_ENDPOINTS || douyinUid < 0) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null && endpoints.size() < MAX_ENDPOINTS) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 8) {
                    continue;
                }
                int uid = parseInt(parts[7], -1);
                if (uid != douyinUid) {
                    continue;
                }
                Endpoint endpoint = parseEndpoint(parts[2], ipv6);
                if (endpoint == null || endpoint.port == 0 || isEmptyAddress(endpoint.host)) {
                    continue;
                }
                String state = parts.length > 3 ? parts[3] : "";
                endpoints.add(String.format(Locale.US, "%s %s:%d st=%s", protocol, endpoint.host, endpoint.port, state));
            }
        } catch (Exception exc) {
            Log.d(TAG, "Cannot read " + path + ": " + exc.getMessage());
        }
    }

    private Endpoint parseEndpoint(String value, boolean ipv6) {
        String[] pieces = value.split(":");
        if (pieces.length != 2) {
            return null;
        }
        int port = parseHexInt(pieces[1], 0);
        String host = ipv6 ? parseIpv6(pieces[0]) : parseIpv4(pieces[0]);
        if (host.isEmpty()) {
            return null;
        }
        return new Endpoint(host, port);
    }

    private String parseIpv4(String hex) {
        if (hex == null || hex.length() != 8) {
            return "";
        }
        try {
            return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    Integer.parseInt(hex.substring(6, 8), 16),
                    Integer.parseInt(hex.substring(4, 6), 16),
                    Integer.parseInt(hex.substring(2, 4), 16),
                    Integer.parseInt(hex.substring(0, 2), 16)
            );
        } catch (NumberFormatException exc) {
            return "";
        }
    }

    private String parseIpv6(String hex) {
        if (hex == null || hex.length() != 32) {
            return "";
        }
        try {
            byte[] bytes = new byte[16];
            for (int block = 0; block < 4; block += 1) {
                int offset = block * 8;
                for (int index = 0; index < 4; index += 1) {
                    String part = hex.substring(offset + (3 - index) * 2, offset + (4 - index) * 2);
                    bytes[block * 4 + index] = (byte) Integer.parseInt(part, 16);
                }
            }
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (Exception exc) {
            return "";
        }
    }

    private boolean isEmptyAddress(String host) {
        return "0.0.0.0".equals(host)
                || "::".equals(host)
                || "0:0:0:0:0:0:0:0".equals(host);
    }

    private int parseHexInt(String value, int fallback) {
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException exc) {
            return fallback;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exc) {
            return fallback;
        }
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index += 1) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(values.get(index));
        }
        return builder.toString();
    }

    private static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
