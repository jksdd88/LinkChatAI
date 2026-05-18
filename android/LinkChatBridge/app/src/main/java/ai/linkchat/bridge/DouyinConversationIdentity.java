package ai.linkchat.bridge;

final class DouyinConversationIdentity {
    final String conversationExternalId;
    final String customerHandle;
    final boolean stable;

    private DouyinConversationIdentity(String conversationExternalId, String customerHandle, boolean stable) {
        this.conversationExternalId = conversationExternalId;
        this.customerHandle = customerHandle;
        this.stable = stable;
    }

    static DouyinConversationIdentity notificationThread(String key) {
        String token = stableToken(key);
        return new DouyinConversationIdentity(
                "douyin-notification-thread-" + token,
                "douyin-notification-user-" + token,
                true
        );
    }

    static DouyinConversationIdentity notificationEvent(String key) {
        String token = stableToken(key);
        return new DouyinConversationIdentity(
                "douyin-notification-event-" + token,
                "douyin-notification-event-user-" + token,
                false
        );
    }

    static DouyinConversationIdentity accessibilityRow(String key) {
        String token = stableToken(key);
        return new DouyinConversationIdentity(
                "douyin-accessibility-row-" + token,
                "douyin-accessibility-user-" + token,
                false
        );
    }

    private static String stableToken(String value) {
        return Integer.toHexString(normalize(value).hashCode());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
