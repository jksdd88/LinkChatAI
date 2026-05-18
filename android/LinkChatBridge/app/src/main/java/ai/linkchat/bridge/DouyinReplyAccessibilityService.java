package ai.linkchat.bridge;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DouyinReplyAccessibilityService extends AccessibilityService {
    private static final String TAG = "LinkChatReply";
    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String DOUYIN_LITE_PACKAGE = "com.ss.android.ugc.aweme.lite";
    private static final int POLL_INTERVAL_MS = 2500;
    private static final int TASK_LIMIT = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> forwardedInboxKeys = new HashSet<>();
    private boolean running = false;
    private boolean polling = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (polling) {
                scheduleNextPoll();
                return;
            }
            polling = true;
            new Thread(() -> {
                try {
                    pollAndProcessTasks();
                } catch (Exception exc) {
                    Log.e(TAG, "Reply poll failed", exc);
                } finally {
                    polling = false;
                    scheduleNextPoll();
                }
            }, "linkchat-reply-poller").start();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        handler.post(pollRunnable);
        Log.i(TAG, "Douyin reply accessibility service connected");
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null || !isDouyinPackage(event.getPackageName().toString())) {
            return;
        }
        scanVisibleInbox();
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "Douyin reply accessibility service interrupted");
    }

    private void scheduleNextPoll() {
        if (!running) {
            return;
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void pollAndProcessTasks() throws Exception {
        scanVisibleInbox();

        List<OutboundTask> tasks = LinkChatApi.getPendingSendTasks(this, TASK_LIMIT);
        if (tasks.isEmpty()) {
            return;
        }

        for (OutboundTask task : tasks) {
            if (trySendTask(task)) {
                LinkChatApi.ackSendTask(
                        this,
                        task.id,
                        "sent",
                        "accessibility:" + task.id,
                        ""
                );
                Log.i(TAG, "Sent LinkChatAI task " + task.id + " to " + task.customerName);
            }
        }
    }

    private boolean trySendTask(OutboundTask task) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!isDouyinRoot(root)) {
            return false;
        }
        if (!screenMatchesCustomer(root, task)) {
            Log.i(TAG, "Task " + task.id + " waits for matching Douyin chat: " + task.customerName);
            return false;
        }

        AccessibilityNodeInfo input = findEditableNode(root);
        if (input == null || !setNodeText(input, task.body)) {
            Log.i(TAG, "Task " + task.id + " waits for Douyin input box");
            return false;
        }

        sleepQuietly(350);
        AccessibilityNodeInfo refreshedRoot = getRootInActiveWindow();
        AccessibilityNodeInfo sendButton = findSendButton(refreshedRoot == null ? root : refreshedRoot);
        if (sendButton == null) {
            Log.i(TAG, "Task " + task.id + " text inserted, but send button is not visible");
            return false;
        }
        return sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean isDouyinRoot(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) {
            return false;
        }
        return isDouyinPackage(root.getPackageName().toString());
    }

    private boolean isDouyinPackage(String packageName) {
        return DOUYIN_PACKAGE.equals(packageName) || DOUYIN_LITE_PACKAGE.equals(packageName);
    }

    private boolean screenMatchesCustomer(AccessibilityNodeInfo root, OutboundTask task) {
        if (task.customerName.isEmpty()) {
            return false;
        }
        String text = collectVisibleText(root).toLowerCase(Locale.ROOT);
        String customerName = task.customerName.toLowerCase(Locale.ROOT);
        String customerHandle = task.customerHandle.toLowerCase(Locale.ROOT);
        return text.contains(customerName) || (!customerHandle.isEmpty() && text.contains(customerHandle));
    }

    private void scanVisibleInbox() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return;
        }
        String packageName = root.getPackageName().toString();
        if (!isDouyinPackage(packageName)) {
            return;
        }
        String visibleText = collectVisibleText(root);
        if (!visibleText.contains("消息")) {
            return;
        }

        List<InboxRow> rows = new ArrayList<>();
        collectInboxRows(root, rows);
        if (rows.isEmpty()) {
            List<TextNode> textNodes = new ArrayList<>();
            collectTextNodes(root, textNodes);
            rows.addAll(parseInboxRowsByAggregatedText(textNodes));
            rows.addAll(parseInboxRowsByUnreadBadges(textNodes));
        }
        for (InboxRow row : rows) {
            forwardInboxRow(row);
        }
    }

    private void collectInboxRows(AccessibilityNodeInfo node, List<InboxRow> rows) {
        if (node == null) {
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (isInboxRowBounds(bounds)) {
            InboxRow row = parseInboxRow(node, bounds);
            if (row != null) {
                rows.add(row);
            }
        }
        for (int index = 0; index < node.getChildCount(); index += 1) {
            collectInboxRows(node.getChild(index), rows);
        }
    }

    private boolean isInboxRowBounds(Rect bounds) {
        return bounds.left <= 40
                && bounds.width() >= 900
                && bounds.height() >= 130
                && bounds.height() <= 260
                && bounds.top >= 560
                && bounds.top <= 2100;
    }

    private InboxRow parseInboxRow(AccessibilityNodeInfo rowNode, Rect rowBounds) {
        List<TextNode> textNodes = new ArrayList<>();
        collectTextNodes(rowNode, textNodes);
        if (textNodes.size() < 3) {
            return null;
        }

        String name = "";
        String preview = "";
        String visibleTime = "";
        String unreadText = "";
        int middleY = rowBounds.top + rowBounds.height() / 2;

        for (TextNode textNode : textNodes) {
            if (isIgnoredInboxText(textNode.text)) {
                return null;
            }
            if (isTimeText(textNode.text) && textNode.bounds.left >= 800) {
                visibleTime = textNode.text;
                continue;
            }
            if (isUnreadBadge(textNode) && unreadText.isEmpty()) {
                unreadText = textNode.text;
                continue;
            }
            if (textNode.bounds.left >= 180 && textNode.bounds.left < 820 && textNode.bounds.top < middleY && name.isEmpty()) {
                name = textNode.text;
                continue;
            }
            if (textNode.bounds.left >= 180 && textNode.bounds.top >= middleY && preview.isEmpty()) {
                preview = textNode.text;
            }
        }

        if (name.isEmpty() || preview.isEmpty() || unreadText.isEmpty()) {
            return null;
        }
        return new InboxRow(name, preview, visibleTime, unreadText);
    }

    private List<InboxRow> parseInboxRowsByAggregatedText(List<TextNode> textNodes) {
        List<InboxRow> rows = new ArrayList<>();
        for (TextNode node : textNodes) {
            if (node.bounds.left > 40 || node.bounds.width() < 900 || node.bounds.top < 560 || node.bounds.top > 2100) {
                continue;
            }
            if (!node.text.contains("未读") || !node.text.contains("条消息")) {
                continue;
            }
            InboxRow row = parseAggregatedInboxText(node.text);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private InboxRow parseAggregatedInboxText(String value) {
        String[] parts = value.split("[,，]");
        if (parts.length < 3) {
            return null;
        }

        String name = "";
        String unread = "";
        StringBuilder previewBuilder = new StringBuilder();
        boolean afterUnread = false;
        for (String rawPart : parts) {
            String part = normalize(rawPart);
            if (part.isEmpty()) {
                continue;
            }
            if (name.isEmpty()) {
                name = part;
                continue;
            }
            if (part.matches("未读\\d+条消息")) {
                unread = part;
                afterUnread = true;
                continue;
            }
            if (afterUnread) {
                if (previewBuilder.length() > 0) {
                    previewBuilder.append('，');
                }
                previewBuilder.append(part);
            }
        }

        String previewWithTime = normalize(previewBuilder.toString());
        if (name.isEmpty() || unread.isEmpty() || previewWithTime.isEmpty() || isIgnoredInboxText(name)) {
            return null;
        }

        ParsedPreview parsedPreview = stripVisibleTime(previewWithTime);
        if (parsedPreview.preview.isEmpty() || isIgnoredInboxText(parsedPreview.preview)) {
            return null;
        }
        return new InboxRow(name, parsedPreview.preview, parsedPreview.visibleTime, unread);
    }

    private ParsedPreview stripVisibleTime(String value) {
        String text = normalize(value);
        String[] patterns = {
                "刚刚$",
                "\\d+分钟前$",
                "昨天\\s*\\d{1,2}:\\d{2}$",
                "\\d{1,2}:\\d{2}$",
                "\\d{1,2}-\\d{1,2}$",
                "\\d{4}-\\d{1,2}-\\d{1,2}$"
        };
        for (String pattern : patterns) {
            java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher matcher = compiled.matcher(text);
            if (matcher.find()) {
                String visibleTime = matcher.group();
                String preview = normalize(text.substring(0, matcher.start()));
                return new ParsedPreview(preview, visibleTime);
            }
        }
        return new ParsedPreview(text, "");
    }

    private List<InboxRow> parseInboxRowsByUnreadBadges(List<TextNode> textNodes) {
        List<InboxRow> rows = new ArrayList<>();
        for (TextNode badge : textNodes) {
            if (!isUnreadBadge(badge) || badge.bounds.top < 560 || badge.bounds.top > 2100) {
                continue;
            }

            TextNode preview = closestPreviewForBadge(textNodes, badge);
            if (preview == null || isIgnoredInboxText(preview.text)) {
                continue;
            }
            TextNode name = closestNameForPreview(textNodes, preview);
            if (name == null || isIgnoredInboxText(name.text)) {
                continue;
            }
            TextNode time = closestTimeForName(textNodes, name);
            rows.add(new InboxRow(name.text, preview.text, time == null ? "" : time.text, badge.text));
        }
        return rows;
    }

    private TextNode closestPreviewForBadge(List<TextNode> textNodes, TextNode badge) {
        TextNode best = null;
        int bestDistance = Integer.MAX_VALUE;
        int badgeCenter = badge.bounds.centerY();
        for (TextNode candidate : textNodes) {
            if (candidate.bounds.left < 180 || candidate.bounds.left > 930) {
                continue;
            }
            if (candidate.text.matches("\\d+") || isTimeText(candidate.text) || isIgnoredInboxText(candidate.text)) {
                continue;
            }
            int distance = Math.abs(candidate.bounds.centerY() - badgeCenter);
            if (distance <= 70 && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private TextNode closestNameForPreview(List<TextNode> textNodes, TextNode preview) {
        TextNode best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (TextNode candidate : textNodes) {
            if (candidate.bounds.left < 180 || candidate.bounds.left > 820 || candidate.bounds.top >= preview.bounds.top) {
                continue;
            }
            if (candidate.text.matches("\\d+") || isTimeText(candidate.text) || isIgnoredInboxText(candidate.text)) {
                continue;
            }
            int distance = preview.bounds.top - candidate.bounds.top;
            if (distance > 0 && distance <= 140 && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private TextNode closestTimeForName(List<TextNode> textNodes, TextNode name) {
        TextNode best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (TextNode candidate : textNodes) {
            if (!isTimeText(candidate.text) || candidate.bounds.left < 800) {
                continue;
            }
            int distance = Math.abs(candidate.bounds.centerY() - name.bounds.centerY());
            if (distance <= 80 && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void collectTextNodes(AccessibilityNodeInfo node, List<TextNode> result) {
        if (node == null) {
            return;
        }
        addTextNode(node, result, node.getText());
        addTextNode(node, result, node.getContentDescription());
        for (int index = 0; index < node.getChildCount(); index += 1) {
            collectTextNodes(node.getChild(index), result);
        }
    }

    private void addTextNode(AccessibilityNodeInfo node, List<TextNode> result, CharSequence value) {
        String text = normalize(value);
        if (text.isEmpty()) {
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        result.add(new TextNode(text, bounds));
    }

    private boolean isUnreadBadge(TextNode textNode) {
        return textNode.text.matches("\\d+")
                && textNode.bounds.left >= 850
                && textNode.bounds.width() <= 100
                && textNode.bounds.height() <= 90;
    }

    private boolean isTimeText(String text) {
        return text.equals("刚刚")
                || text.startsWith("昨天")
                || text.matches("\\d{1,2}:\\d{2}")
                || text.matches("\\d{1,2}-\\d{1,2}")
                || text.matches("\\d{4}-\\d{1,2}-\\d{1,2}");
    }

    private boolean isIgnoredInboxText(String text) {
        return text.equals("互动消息")
                || text.equals("陌生人消息")
                || text.equals("暂时没有更多了")
                || text.equals("没有新通知")
                || text.equals("没有新消息")
                || text.equals("智能助手")
                || text.equals("豆包")
                || text.equals("系统通知")
                || text.equals("服务通知")
                || text.startsWith("嗨，我是豆包")
                || text.equals("首页")
                || text.equals("朋友")
                || text.equals("消息")
                || text.equals("我");
    }

    private void forwardInboxRow(InboxRow row) {
        String key = row.messageKey();
        synchronized (forwardedInboxKeys) {
            if (forwardedInboxKeys.contains(key)) {
                return;
            }
            forwardedInboxKeys.add(key);
        }

        new Thread(() -> {
            try {
                DouyinConversationIdentity identity = DouyinIdentityCache.lookup(row.customerName, row.preview);
                if (identity == null) {
                    identity = DouyinConversationIdentity.accessibilityRow(row.identityKey());
                }
                String messageToken = stableToken(key);
                LinkChatApi.postMobileMessage(
                        getApplicationContext(),
                        identity.conversationExternalId,
                        row.customerName,
                        identity.customerHandle,
                        row.preview,
                        "douyin-accessibility-message-" + messageToken
                );
                Log.i(TAG, "Forwarded Douyin inbox row from " + row.customerName + ": " + row.preview);
            } catch (Exception exc) {
                synchronized (forwardedInboxKeys) {
                    forwardedInboxKeys.remove(key);
                }
                Log.e(TAG, "Failed to forward Douyin inbox row", exc);
            }
        }, "linkchat-inbox-forward").start();
    }

    private AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isVisibleToUser() && node.isEnabled() && node.isEditable()) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index += 1) {
            AccessibilityNodeInfo match = findEditableNode(node.getChild(index));
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String text) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
        );
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isVisibleToUser() && node.isEnabled() && hasSendLabel(node)) {
            AccessibilityNodeInfo clickable = clickableNode(node);
            if (clickable != null) {
                return clickable;
            }
        }
        for (int index = 0; index < node.getChildCount(); index += 1) {
            AccessibilityNodeInfo match = findSendButton(node.getChild(index));
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo clickableNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable() && current.isEnabled() && current.isVisibleToUser()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private boolean hasSendLabel(AccessibilityNodeInfo node) {
        String text = normalize(node.getText());
        String description = normalize(node.getContentDescription());
        return "发送".equals(text)
                || "发送".equals(description)
                || "send".equals(text.toLowerCase(Locale.ROOT))
                || "send".equals(description.toLowerCase(Locale.ROOT));
    }

    private String collectVisibleText(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendText(builder, node.getText());
        appendText(builder, node.getContentDescription());
        for (int index = 0; index < node.getChildCount(); index += 1) {
            String childText = collectVisibleText(node.getChild(index));
            if (!childText.isEmpty()) {
                builder.append(' ').append(childText);
            }
        }
        return builder.toString();
    }

    private void appendText(StringBuilder builder, CharSequence value) {
        String text = normalize(value);
        if (!text.isEmpty()) {
            builder.append(' ').append(text);
        }
    }

    private String normalize(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private String stableToken(String value) {
        return Integer.toHexString(normalize(value).hashCode());
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TextNode {
        final String text;
        final Rect bounds;

        TextNode(String text, Rect bounds) {
            this.text = text;
            this.bounds = bounds;
        }
    }

    private static final class InboxRow {
        final String customerName;
        final String preview;
        final String visibleTime;
        final String unreadText;

        InboxRow(String customerName, String preview, String visibleTime, String unreadText) {
            this.customerName = customerName;
            this.preview = preview;
            this.visibleTime = visibleTime;
            this.unreadText = unreadText;
        }

        String identityKey() {
            return customerName + "\n" + visibleTime + "\n" + unreadText + "\n" + preview;
        }

        String messageKey() {
            return identityKey();
        }
    }

    private static final class ParsedPreview {
        final String preview;
        final String visibleTime;

        ParsedPreview(String preview, String visibleTime) {
            this.preview = preview;
            this.visibleTime = visibleTime;
        }
    }
}
