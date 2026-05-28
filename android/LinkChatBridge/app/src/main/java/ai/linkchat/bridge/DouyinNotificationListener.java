package ai.linkchat.bridge;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Build;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

public class DouyinNotificationListener extends NotificationListenerService {
    private static final String TAG = "LinkChatBridge";
    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!DOUYIN_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) {
            return;
        }
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }

        ParsedNotification parsed = parseNotification(sbn, notification);
        if (parsed == null) {
            return;
        }

        DouyinConversationIdentity identity = notificationIdentity(sbn, notification, parsed);
        BridgeConfig.rememberAccountDisplayName(
                getApplicationContext(),
                notificationAccountDisplayName(notification, parsed)
        );
        String channelMessageId = String.format(
                Locale.US,
                "douyin-notification-%d-%s",
                sbn.getPostTime(),
                stableToken(sbn.getKey() + "\n" + parsed.customerName + "\n" + parsed.body)
        );
        DouyinIdentityCache.remember(parsed.customerName, parsed.body, identity);

        new Thread(() -> {
            try {
                LinkChatApi.postMobileMessage(
                        getApplicationContext(),
                        identity.conversationExternalId,
                        parsed.customerName,
                        identity.customerHandle,
                        parsed.body,
                        channelMessageId
                );
                Log.i(TAG, "Forwarded Douyin notification from " + parsed.customerName);
            } catch (Exception exc) {
                Log.e(TAG, "Failed to forward Douyin notification", exc);
            }
        }, "linkchat-notification-forward").start();
    }

    private static ParsedNotification parseNotification(StatusBarNotification sbn, Notification notification) {
        Bundle extras = notification.extras;
        MessageCandidate message = latestMessageCandidate(extras);
        String rawTitle = firstNonEmpty(
                message.sender,
                charSequenceToString(extras.getCharSequence(Notification.EXTRA_TITLE)),
                charSequenceToString(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)),
                "抖音用户"
        );
        String rawBody = firstNonEmpty(
                message.body,
                lastTextLine(extras),
                charSequenceToString(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                charSequenceToString(extras.getCharSequence(Notification.EXTRA_TEXT)),
                charSequenceToString(notification.tickerText)
        );

        ParsedNotification parsed = parseCustomerAndBody(rawTitle, rawBody, message.identityHint);
        if (parsed == null) {
            return null;
        }
        if (!looksLikeDirectMessage(notification, parsed, message, rawTitle, rawBody)) {
            Log.d(TAG, "Ignored non-message Douyin notification: " + rawTitle + " / " + rawBody);
            return null;
        }
        return parsed;
    }

    private static String notificationAccountDisplayName(Notification notification, ParsedNotification parsed) {
        if (notification.extras == null) {
            return "";
        }
        String candidate = firstNonEmpty(
                charSequenceToString(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)),
                charSequenceToString(notification.extras.getCharSequence("android.subText")),
                ""
        );
        if (candidate.isEmpty()
                || candidate.equals(parsed.customerName)
                || candidate.contains(parsed.body)
                || isGenericDouyinTitle(candidate)) {
            return "";
        }
        return candidate;
    }

    private static DouyinConversationIdentity notificationIdentity(
            StatusBarNotification sbn,
            Notification notification,
            ParsedNotification parsed
    ) {
        String shortcutId = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            shortcutId = normalizeText(notification.getShortcutId());
        }
        if (!shortcutId.isEmpty()) {
            return DouyinConversationIdentity.notificationThread("shortcut:" + sbn.getPackageName() + ":" + shortcutId);
        }

        String personIdentity = firstNonEmpty(parsed.identityHint, peopleIdentityFromExtras(notification.extras), "");
        if (!personIdentity.isEmpty()) {
            return DouyinConversationIdentity.notificationThread("person:" + sbn.getPackageName() + ":" + personIdentity);
        }

        String tag = normalizeText(sbn.getTag());
        String sortKey = normalizeText(notification.getSortKey());
        if (!tag.isEmpty() || !sortKey.isEmpty()) {
            return DouyinConversationIdentity.notificationThread("notification-thread:"
                    + sbn.getPackageName()
                    + ":" + sbn.getId()
                    + ":" + tag
                    + ":" + normalizeText(sbn.getGroupKey())
                    + ":" + sortKey
                    + ":" + normalizeText(notification.category));
        }

        return DouyinConversationIdentity.notificationEvent("notification-event:"
                + sbn.getKey()
                + ":" + sbn.getPostTime()
                + ":" + parsed.customerName
                + ":" + parsed.body);
    }

    private static MessageCandidate latestMessageCandidate(Bundle extras) {
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages == null || messages.length == 0) {
            return MessageCandidate.empty();
        }

        MessageCandidate latest = MessageCandidate.empty();
        for (Parcelable parcelable : messages) {
            if (!(parcelable instanceof Bundle)) {
                continue;
            }
            Bundle message = (Bundle) parcelable;
            String body = charSequenceToString(message.getCharSequence("text"));
            if (body.isEmpty()) {
                continue;
            }
            String sender = charSequenceToString(message.getCharSequence("sender"));
            String identityHint = senderIdentityHint(message);
            long time = message.getLong("time", 0L);
            if (latest.body.isEmpty() || time >= latest.time) {
                latest = new MessageCandidate(sender, body, time, true, identityHint);
            }
        }
        return latest;
    }

    private static ParsedNotification parseCustomerAndBody(String rawTitle, String rawBody, String identityHint) {
        String title = normalizeText(rawTitle);
        String body = normalizeText(rawBody);
        if (body.isEmpty()) {
            return null;
        }

        body = lastMeaningfulLine(body);
        String customerName = isGenericDouyinTitle(title) ? "" : title;
        int colon = firstColonIndex(body);
        if (colon > 0 && colon <= 40) {
            String possibleName = normalizeText(body.substring(0, colon));
            String possibleBody = normalizeText(body.substring(colon + 1));
            if (!possibleName.isEmpty() && !possibleBody.isEmpty()) {
                if (customerName.isEmpty() || isGenericDouyinTitle(customerName)) {
                    customerName = possibleName;
                    body = possibleBody;
                } else if (possibleName.equals(customerName)) {
                    body = possibleBody;
                }
            }
        }

        customerName = firstNonEmpty(customerName, title, "抖音用户");
        body = stripKnownMessagePrefix(body);
        body = stripSenderPrefix(body, customerName);
        if (body.isEmpty()) {
            return null;
        }
        return new ParsedNotification(customerName, body, identityHint);
    }

    private static String senderIdentityHint(Bundle message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Parcelable senderPerson = message.getParcelable("sender_person");
            String personIdentity = personIdentity(senderPerson);
            if (!personIdentity.isEmpty()) {
                return personIdentity;
            }
        }

        Bundle extras = message.getBundle("extras");
        if (extras == null) {
            return "";
        }
        return firstNonEmpty(
                extras.getString("key", ""),
                extras.getString("uri", ""),
                extras.getString("android.key", ""),
                extras.getString("android.uri", ""),
                ""
        );
    }

    private static String peopleIdentityFromExtras(Bundle extras) {
        if (extras == null) {
            return "";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ArrayList<Parcelable> people = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST);
            if (people != null) {
                for (Parcelable value : people) {
                    String personIdentity = personIdentity(value);
                    if (!personIdentity.isEmpty()) {
                        return personIdentity;
                    }
                }
            }
        }

        String[] legacyPeople = extras.getStringArray(Notification.EXTRA_PEOPLE);
        if (legacyPeople != null) {
            for (String value : legacyPeople) {
                String normalized = normalizeText(value);
                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }
        }
        return "";
    }

    private static String personIdentity(Parcelable value) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !(value instanceof Person)) {
            return "";
        }
        Person person = (Person) value;
        return firstNonEmpty(
                person.getKey(),
                person.getUri(),
                charSequenceToString(person.getName()),
                "",
                ""
        );
    }

    private static boolean looksLikeDirectMessage(
            Notification notification,
            ParsedNotification parsed,
            MessageCandidate message,
            String rawTitle,
            String rawBody
    ) {
        if (message.fromMessagingStyle) {
            return true;
        }
        if (Notification.CATEGORY_MESSAGE.equals(notification.category)) {
            return true;
        }
        if (hasInlineReplyAction(notification)) {
            return true;
        }

        String combined = (rawTitle + " " + rawBody + " " + parsed.customerName + " " + parsed.body).toLowerCase(Locale.ROOT);
        if (containsAny(combined, "私信", "消息", "发来", "回复")) {
            return true;
        }
        return !containsAny(combined, "赞了", "点赞", "评论", "关注", "粉丝", "直播", "推荐", "作品", "系统通知");
    }

    private static boolean hasInlineReplyAction(Notification notification) {
        if (notification.actions == null) {
            return false;
        }
        for (Notification.Action action : notification.actions) {
            if (action != null && action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) {
                return true;
            }
        }
        return false;
    }

    private static String lastTextLine(Bundle extras) {
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines == null || lines.length == 0) {
            return "";
        }
        for (int index = lines.length - 1; index >= 0; index -= 1) {
            String line = charSequenceToString(lines[index]);
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    private static String charSequenceToString(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String firstNonEmpty(String first, String second, String third, String fallback) {
        return firstNonEmpty(first, second, third, fallback, "");
    }

    private static String firstNonEmpty(String first, String second, String third, String fourth, String fifth) {
        String[] values = {first, second, third, fourth, fifth};
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String firstNonEmpty(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        return second != null && !second.trim().isEmpty() ? second.trim() : fallback;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replace('\r', '\n').replaceAll("[\\t ]+", " ").trim();
    }

    private static String lastMeaningfulLine(String value) {
        String[] lines = value.split("\\n");
        for (int index = lines.length - 1; index >= 0; index -= 1) {
            String line = normalizeText(lines[index]);
            if (!line.isEmpty()) {
                return line;
            }
        }
        return normalizeText(value);
    }

    private static boolean isGenericDouyinTitle(String value) {
        String normalized = normalizeText(value);
        return normalized.isEmpty()
                || "抖音".equals(normalized)
                || "抖音消息".equals(normalized)
                || "消息".equals(normalized)
                || "私信".equals(normalized);
    }

    private static int firstColonIndex(String value) {
        int ascii = value.indexOf(':');
        int chinese = value.indexOf('：');
        if (ascii < 0) {
            return chinese;
        }
        if (chinese < 0) {
            return ascii;
        }
        return Math.min(ascii, chinese);
    }

    private static String stripKnownMessagePrefix(String value) {
        String result = normalizeText(value);
        String[] prefixes = {
                "发来一条私信：",
                "发来一条私信:",
                "发来私信：",
                "发来私信:",
                "发来一条消息：",
                "发来一条消息:",
                "发来消息：",
                "发来消息:",
                "私信：",
                "私信:",
                "消息：",
                "消息:"
        };
        for (String prefix : prefixes) {
            if (result.startsWith(prefix)) {
                return normalizeText(result.substring(prefix.length()));
            }
        }
        return result;
    }

    private static String stripSenderPrefix(String body, String customerName) {
        String result = normalizeText(body);
        String name = normalizeText(customerName);
        if (name.isEmpty()) {
            return result;
        }
        String asciiPrefix = name + ":";
        String chinesePrefix = name + "：";
        if (result.startsWith(asciiPrefix)) {
            return normalizeText(result.substring(asciiPrefix.length()));
        }
        if (result.startsWith(chinesePrefix)) {
            return normalizeText(result.substring(chinesePrefix.length()));
        }
        return result;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String stableToken(String value) {
        return Integer.toHexString(normalizeText(value).hashCode());
    }

    private static final class MessageCandidate {
        final String sender;
        final String body;
        final long time;
        final boolean fromMessagingStyle;
        final String identityHint;

        MessageCandidate(String sender, String body, long time, boolean fromMessagingStyle, String identityHint) {
            this.sender = normalizeText(sender);
            this.body = normalizeText(body);
            this.time = time;
            this.fromMessagingStyle = fromMessagingStyle;
            this.identityHint = normalizeText(identityHint);
        }

        static MessageCandidate empty() {
            return new MessageCandidate("", "", 0L, false, "");
        }
    }

    private static final class ParsedNotification {
        final String customerName;
        final String body;
        final String identityHint;

        ParsedNotification(String customerName, String body, String identityHint) {
            this.customerName = normalizeText(customerName);
            this.body = normalizeText(body);
            this.identityHint = normalizeText(identityHint);
        }
    }
}
