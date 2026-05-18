package ai.linkchat.bridge;

import org.json.JSONObject;

final class OutboundTask {
    final int id;
    final String accountHandle;
    final String conversationExternalId;
    final String customerName;
    final String customerHandle;
    final String body;

    private OutboundTask(
            int id,
            String accountHandle,
            String conversationExternalId,
            String customerName,
            String customerHandle,
            String body
    ) {
        this.id = id;
        this.accountHandle = normalize(accountHandle);
        this.conversationExternalId = normalize(conversationExternalId);
        this.customerName = normalize(customerName);
        this.customerHandle = normalize(customerHandle);
        this.body = normalize(body);
    }

    static OutboundTask fromJson(JSONObject object) {
        return new OutboundTask(
                object.optInt("id", 0),
                object.optString("account_handle", ""),
                object.optString("conversation_external_id", ""),
                object.optString("customer_name", ""),
                object.optString("customer_handle", ""),
                object.optString("body", "")
        );
    }

    boolean isValid() {
        return id > 0 && !body.isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
