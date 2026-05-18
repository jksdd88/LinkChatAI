package ai.linkchat.bridge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class DouyinIdentityCache {
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_ENTRIES = 200;
    private static final Map<String, Entry> RECENT = new LinkedHashMap<>();

    private DouyinIdentityCache() {
    }

    static synchronized void remember(String customerName, String body, DouyinConversationIdentity identity) {
        if (identity == null || !identity.stable) {
            return;
        }
        prune();
        RECENT.put(messageKey(customerName, body), new Entry(identity, System.currentTimeMillis()));
        while (RECENT.size() > MAX_ENTRIES) {
            Iterator<String> iterator = RECENT.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    static synchronized DouyinConversationIdentity lookup(String customerName, String body) {
        prune();
        Entry entry = RECENT.get(messageKey(customerName, body));
        return entry == null ? null : entry.identity;
    }

    private static void prune() {
        long expiresBefore = System.currentTimeMillis() - TTL_MS;
        Iterator<Map.Entry<String, Entry>> iterator = RECENT.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().createdAt < expiresBefore) {
                iterator.remove();
            }
        }
    }

    private static String messageKey(String customerName, String body) {
        return normalize(customerName).toLowerCase(Locale.ROOT) + "\n" + normalize(body);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Entry {
        final DouyinConversationIdentity identity;
        final long createdAt;

        Entry(DouyinConversationIdentity identity, long createdAt) {
            this.identity = identity;
            this.createdAt = createdAt;
        }
    }
}
