package ai.linkchat.bridge;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class DouyinDomainVpnService extends VpnService {
    static final String ACTION_START = "ai.linkchat.bridge.action.START_DOMAIN_VPN";
    static final String ACTION_STOP = "ai.linkchat.bridge.action.STOP_DOMAIN_VPN";

    private static final String TAG = "LinkChatDomainVpn";
    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String DOUYIN_LITE_PACKAGE = "com.ss.android.ugc.aweme.lite";
    private static final String VPN_ADDRESS = "10.88.0.2";
    private static final String DNS_SERVER = "223.5.5.5";
    private static final int DNS_PORT = 53;
    private static final long DOMAIN_COOLDOWN_MS = 20000L;

    private final Set<String> recentlyReportedDomains = new HashSet<>();
    private ParcelFileDescriptor vpnInterface;
    private Thread workerThread;
    private volatile boolean running = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private synchronized void startVpn() {
        if (running) {
            return;
        }
        try {
            Builder builder = new Builder()
                    .setSession("LinkChat Douyin Domain Probe")
                    .addAddress(VPN_ADDRESS, 32)
                    .addRoute(DNS_SERVER, 32)
                    .addDnsServer(DNS_SERVER);

            int allowedCount = addDouyinPackages(builder);
            if (allowedCount == 0) {
                postStatus("域名探针未启动：没有找到国内抖音包名。", "douyin-domain-vpn-no-package");
                return;
            }

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                postStatus("域名探针未启动：系统没有建立 VPN 通道。", "douyin-domain-vpn-no-interface");
                return;
            }

            running = true;
            workerThread = new Thread(this::runLoop, "linkchat-domain-vpn");
            workerThread.start();
            postStatus(
                    "抖音域名探针已启动：仅转发并记录抖音 DNS 域名，不解密请求内容。",
                    "douyin-domain-vpn-started-" + System.currentTimeMillis()
            );
            Log.i(TAG, "Domain VPN probe started");
        } catch (Exception exc) {
            Log.e(TAG, "Failed to start domain VPN", exc);
            postStatus("域名探针启动失败：" + exc.getMessage(), "douyin-domain-vpn-start-failed-" + System.currentTimeMillis());
            stopVpn();
        }
    }

    private int addDouyinPackages(Builder builder) {
        PackageManager packageManager = getPackageManager();
        int count = 0;
        String[] packages = {DOUYIN_PACKAGE, DOUYIN_LITE_PACKAGE};
        for (String packageName : packages) {
            try {
                packageManager.getPackageInfo(packageName, 0);
                builder.addAllowedApplication(packageName);
                count += 1;
                Log.i(TAG, "Domain VPN scoped to " + packageName);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Try the next known Douyin package.
            }
        }
        return count;
    }

    private void runLoop() {
        byte[] buffer = new byte[32767];
        try (
                FileInputStream input = new FileInputStream(vpnInterface.getFileDescriptor());
                FileOutputStream output = new FileOutputStream(vpnInterface.getFileDescriptor())
        ) {
            while (running) {
                int length = input.read(buffer);
                if (length <= 0) {
                    continue;
                }
                byte[] packet = Arrays.copyOf(buffer, length);
                DnsQuery query = parseDnsQuery(packet, length);
                if (query == null) {
                    continue;
                }
                reportDomain(query);
                byte[] response = forwardDnsQuery(query);
                if (response != null && response.length > 0) {
                    output.write(response);
                }
            }
        } catch (Exception exc) {
            if (running) {
                Log.e(TAG, "Domain VPN loop failed", exc);
                postStatus("域名探针运行失败：" + exc.getMessage(), "douyin-domain-vpn-loop-failed-" + System.currentTimeMillis());
            }
        } finally {
            stopVpn();
        }
    }

    private DnsQuery parseDnsQuery(byte[] packet, int length) {
        if (length < 48) {
            return null;
        }
        int version = (packet[0] >> 4) & 0x0F;
        if (version != 4) {
            return null;
        }
        int ihl = (packet[0] & 0x0F) * 4;
        if (ihl < 20 || length < ihl + 20) {
            return null;
        }
        int protocol = unsigned(packet[9]);
        if (protocol != 17) {
            return null;
        }
        int udpOffset = ihl;
        int sourcePort = readU16(packet, udpOffset);
        int destinationPort = readU16(packet, udpOffset + 2);
        int udpLength = readU16(packet, udpOffset + 4);
        if (destinationPort != DNS_PORT || udpLength < 20 || udpOffset + udpLength > length) {
            return null;
        }
        int dnsOffset = udpOffset + 8;
        int dnsLength = udpLength - 8;
        ParsedDomain parsedDomain = parseDnsDomain(packet, dnsOffset, dnsLength);
        if (parsedDomain == null) {
            return null;
        }
        byte[] dnsPayload = Arrays.copyOfRange(packet, dnsOffset, dnsOffset + dnsLength);
        byte[] sourceAddress = Arrays.copyOfRange(packet, 12, 16);
        byte[] destinationAddress = Arrays.copyOfRange(packet, 16, 20);
        int ipId = readU16(packet, 4);
        return new DnsQuery(
                parsedDomain.domain,
                parsedDomain.queryType,
                sourcePort,
                destinationPort,
                sourceAddress,
                destinationAddress,
                ipId,
                dnsPayload
        );
    }

    private ParsedDomain parseDnsDomain(byte[] packet, int dnsOffset, int dnsLength) {
        if (dnsLength < 17) {
            return null;
        }
        int questionCount = readU16(packet, dnsOffset + 4);
        if (questionCount <= 0) {
            return null;
        }
        int cursor = dnsOffset + 12;
        int end = dnsOffset + dnsLength;
        StringBuilder domain = new StringBuilder();
        while (cursor < end) {
            int labelLength = unsigned(packet[cursor]);
            cursor += 1;
            if (labelLength == 0) {
                break;
            }
            if ((labelLength & 0xC0) != 0 || cursor + labelLength > end) {
                return null;
            }
            if (domain.length() > 0) {
                domain.append('.');
            }
            for (int index = 0; index < labelLength; index += 1) {
                int value = unsigned(packet[cursor + index]);
                if (value < 32 || value > 126) {
                    return null;
                }
                domain.append((char) value);
            }
            cursor += labelLength;
        }
        if (domain.length() == 0 || cursor + 4 > end) {
            return null;
        }
        int queryType = readU16(packet, cursor);
        return new ParsedDomain(domain.toString().toLowerCase(Locale.ROOT), queryType);
    }

    private byte[] forwardDnsQuery(DnsQuery query) {
        try (DatagramSocket socket = new DatagramSocket()) {
            protect(socket);
            socket.setSoTimeout(4000);
            InetAddress server = InetAddress.getByAddress(query.destinationAddress);
            DatagramPacket request = new DatagramPacket(query.dnsPayload, query.dnsPayload.length, server, DNS_PORT);
            socket.send(request);

            byte[] responseBuffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            byte[] dnsResponse = Arrays.copyOf(response.getData(), response.getLength());
            return buildUdpIpv4Response(query, dnsResponse);
        } catch (Exception exc) {
            Log.w(TAG, "Failed to forward DNS query for " + query.domain, exc);
            return null;
        }
    }

    private byte[] buildUdpIpv4Response(DnsQuery query, byte[] dnsResponse) {
        int ipHeaderLength = 20;
        int udpHeaderLength = 8;
        int totalLength = ipHeaderLength + udpHeaderLength + dnsResponse.length;
        byte[] packet = new byte[totalLength];

        packet[0] = 0x45;
        packet[1] = 0;
        writeU16(packet, 2, totalLength);
        writeU16(packet, 4, query.ipId);
        writeU16(packet, 6, 0);
        packet[8] = 64;
        packet[9] = 17;
        System.arraycopy(query.destinationAddress, 0, packet, 12, 4);
        System.arraycopy(query.sourceAddress, 0, packet, 16, 4);
        writeU16(packet, 10, ipChecksum(packet, 0, ipHeaderLength));

        int udpOffset = ipHeaderLength;
        writeU16(packet, udpOffset, query.destinationPort);
        writeU16(packet, udpOffset + 2, query.sourcePort);
        writeU16(packet, udpOffset + 4, udpHeaderLength + dnsResponse.length);
        writeU16(packet, udpOffset + 6, 0);
        System.arraycopy(dnsResponse, 0, packet, udpOffset + udpHeaderLength, dnsResponse.length);
        return packet;
    }

    private void reportDomain(DnsQuery query) {
        String key = query.domain + ":" + query.queryType;
        synchronized (recentlyReportedDomains) {
            if (recentlyReportedDomains.contains(key)) {
                return;
            }
            recentlyReportedDomains.add(key);
        }

        new Thread(() -> {
            String endpoint = bytesToIpv4(query.destinationAddress) + ":" + query.destinationPort;
            String body = String.format(
                    Locale.US,
                    "抖音域名线索：domain=%s qtype=%s dns=%s。这只是 DNS 元数据，不包含请求正文。",
                    query.domain,
                    queryTypeName(query.queryType),
                    endpoint
            );
            String channelMessageId = "douyin-domain-" + Integer.toHexString(key.hashCode()) + "-" + (System.currentTimeMillis() / DOMAIN_COOLDOWN_MS);
            try {
                LinkChatApi.postMobileSignal(getApplicationContext(), body, channelMessageId);
                Log.i(TAG, body);
            } catch (Exception exc) {
                Log.w(TAG, "Failed to post domain signal", exc);
            }
        }, "linkchat-domain-signal").start();
    }

    private void postStatus(String body, String channelMessageId) {
        new Thread(() -> {
            try {
                LinkChatApi.postMobileSignal(getApplicationContext(), body, channelMessageId);
            } catch (Exception exc) {
                Log.w(TAG, "Failed to post VPN status", exc);
            }
        }, "linkchat-domain-status").start();
    }

    private synchronized void stopVpn() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception ignored) {
            }
            vpnInterface = null;
        }
        Log.i(TAG, "Domain VPN probe stopped");
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }

    private int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private void writeU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private int ipChecksum(byte[] data, int offset, int length) {
        long sum = 0;
        for (int index = offset; index < offset + length; index += 2) {
            int word = ((data[index] & 0xFF) << 8) + (data[index + 1] & 0xFF);
            sum += word;
            while ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >> 16);
            }
        }
        return (int) (~sum) & 0xFFFF;
    }

    private String bytesToIpv4(byte[] value) {
        return String.format(
                Locale.US,
                "%d.%d.%d.%d",
                value[0] & 0xFF,
                value[1] & 0xFF,
                value[2] & 0xFF,
                value[3] & 0xFF
        );
    }

    private String queryTypeName(int queryType) {
        if (queryType == 1) {
            return "A";
        }
        if (queryType == 28) {
            return "AAAA";
        }
        if (queryType == 5) {
            return "CNAME";
        }
        if (queryType == 65) {
            return "HTTPS";
        }
        return String.valueOf(queryType);
    }

    private static final class ParsedDomain {
        final String domain;
        final int queryType;

        ParsedDomain(String domain, int queryType) {
            this.domain = domain;
            this.queryType = queryType;
        }
    }

    private static final class DnsQuery {
        final String domain;
        final int queryType;
        final int sourcePort;
        final int destinationPort;
        final byte[] sourceAddress;
        final byte[] destinationAddress;
        final int ipId;
        final byte[] dnsPayload;

        DnsQuery(
                String domain,
                int queryType,
                int sourcePort,
                int destinationPort,
                byte[] sourceAddress,
                byte[] destinationAddress,
                int ipId,
                byte[] dnsPayload
        ) {
            this.domain = domain;
            this.queryType = queryType;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.ipId = ipId;
            this.dnsPayload = dnsPayload;
        }
    }
}
