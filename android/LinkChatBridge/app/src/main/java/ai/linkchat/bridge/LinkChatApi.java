package ai.linkchat.bridge;

import android.content.Context;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class LinkChatApi {
    private LinkChatApi() {
    }

    static String postMobileMessage(
            Context context,
            String conversationExternalId,
            String customerName,
            String customerHandle,
            String body,
            String channelMessageId
    ) throws Exception {
        return postMobileMessage(
                context,
                conversationExternalId,
                customerName,
                customerHandle,
                "inbound",
                body,
                channelMessageId
        );
    }

    static String postMobileSignal(Context context, String body, String channelMessageId) throws Exception {
        String token = Integer.toHexString(BridgeConfig.deviceSerial(context).hashCode());
        return postMobileMessage(
                context,
                "douyin-network-signal-" + token,
                "抖音网络线索",
                "douyin-network-signal",
                "system",
                body,
                channelMessageId
        );
    }

    private static String postMobileMessage(
            Context context,
            String conversationExternalId,
            String customerName,
            String customerHandle,
            String direction,
            String body,
            String channelMessageId
    ) throws Exception {
        String baseUrl = trimTrailingSlash(BridgeConfig.serverUrl(context));
        URL url = new URL(baseUrl + "/api/mobile/messages");

        JSONObject payload = new JSONObject();
        payload.put("device_serial", BridgeConfig.deviceSerial(context));
        payload.put("account_handle", BridgeConfig.accountHandle(context));
        payload.put("account_display_name", BridgeConfig.accountDisplayName(context));
        payload.put("conversation_external_id", conversationExternalId);
        payload.put("customer_name", customerName);
        payload.put("customer_handle", customerHandle);
        payload.put("direction", direction);
        payload.put("body", body);
        payload.put("channel_message_id", channelMessageId);

        return requestJson("POST", url, payload).toString();
    }

    static List<OutboundTask> getPendingSendTasks(Context context, int limit) throws Exception {
        String baseUrl = trimTrailingSlash(BridgeConfig.serverUrl(context));
        String query = "device_serial=" + urlEncode(BridgeConfig.deviceSerial(context))
                + "&account_handle=" + urlEncode(BridgeConfig.accountHandle(context))
                + "&limit=" + Math.max(1, Math.min(limit, 20));
        URL url = new URL(baseUrl + "/api/mobile/send-tasks?" + query);
        JSONObject response = requestJson("GET", url, null);
        JSONArray tasks = response.optJSONArray("tasks");
        List<OutboundTask> result = new ArrayList<>();
        if (tasks == null) {
            return result;
        }
        for (int index = 0; index < tasks.length(); index += 1) {
            JSONObject object = tasks.optJSONObject(index);
            if (object == null) {
                continue;
            }
            OutboundTask task = OutboundTask.fromJson(object);
            if (task.isValid()) {
                result.add(task);
            }
        }
        return result;
    }

    static void ackSendTask(
            Context context,
            int taskId,
            String status,
            String channelMessageId,
            String error
    ) throws Exception {
        String baseUrl = trimTrailingSlash(BridgeConfig.serverUrl(context));
        URL url = new URL(baseUrl + "/api/mobile/send-tasks/" + taskId + "/ack");
        JSONObject payload = new JSONObject();
        payload.put("status", status);
        payload.put("channel_message_id", channelMessageId == null ? "" : channelMessageId);
        payload.put("error", error == null ? "" : error);
        requestJson("POST", url, payload);
    }

    private static JSONObject requestJson(String method, URL url, JSONObject payload) throws Exception {
        byte[] data = payload == null ? null : payload.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        if (data != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(data.length);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(data);
            }
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(stream);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(String.format(Locale.US, "HTTP %d: %s", status, response));
        }
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String urlEncode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }
}
