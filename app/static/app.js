const state = {
  accounts: [],
  conversations: [],
  selectedAccountId: "all",
  selectedConversationId: null,
  selectedConversation: null,
  search: "",
};

const els = {
  accountList: document.querySelector("#accountList"),
  allAccountMeta: document.querySelector("#allAccountMeta"),
  allUnread: document.querySelector("#allUnread"),
  conversationList: document.querySelector("#conversationList"),
  inboxSubtitle: document.querySelector("#inboxSubtitle"),
  search: document.querySelector("#conversationSearch"),
  emptyState: document.querySelector("#emptyState"),
  chatSurface: document.querySelector("#chatSurface"),
  customerAvatar: document.querySelector("#customerAvatar"),
  customerName: document.querySelector("#customerName"),
  conversationMeta: document.querySelector("#conversationMeta"),
  messageTimeline: document.querySelector("#messageTimeline"),
  composer: document.querySelector("#composer"),
  replyInput: document.querySelector("#replyInput"),
  toast: document.querySelector("#newMessageToast"),
};

let refreshInFlight = false;
let conversationsInitialized = false;
let toastTimer = null;
const conversationSnapshots = new Map();
const messageMaxIds = new Map();

function formatTime(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
    ...options,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }
  return response.json();
}

async function loadAccounts() {
  const data = await request("/api/accounts");
  state.accounts = data.accounts;
  renderAccounts();
}

async function loadConversations() {
  const params = new URLSearchParams();
  if (state.selectedAccountId !== "all") params.set("account_id", state.selectedAccountId);
  const data = await request(`/api/conversations?${params.toString()}`);
  detectConversationUpdates(data.conversations);
  state.conversations = data.conversations;
  renderConversations();
}

async function loadMessages(conversationId) {
  const data = await request(`/api/conversations/${conversationId}/messages`);
  state.selectedConversationId = conversationId;
  state.selectedConversation = data.conversation;
  showConversation(data.conversation, data.messages);
  await loadAccounts();
  await loadConversations();
}

function renderAccounts() {
  const totalConversations = state.accounts.reduce((sum, account) => sum + account.conversation_count, 0);
  const totalUnread = state.accounts.reduce((sum, account) => sum + account.unread_count, 0);
  els.allAccountMeta.textContent = `${totalConversations} 个会话`;
  els.allUnread.textContent = totalUnread;
  els.allUnread.classList.toggle("is-empty", totalUnread === 0);

  document.querySelector('[data-account-id="all"]').classList.toggle("is-active", state.selectedAccountId === "all");

  els.accountList.innerHTML = state.accounts.map((account) => `
    <button class="account-row ${String(account.id) === String(state.selectedAccountId) ? "is-active" : ""}" data-account-id="${account.id}" type="button">
      <span class="account-avatar" style="--avatar-color: ${account.avatar_color}">${escapeHtml(account.display_name.slice(-2))}</span>
      <span class="account-copy">
        <strong>${escapeHtml(account.display_name)}</strong>
        <small>${account.conversation_count} 会话</small>
      </span>
      <span class="account-badge ${account.unread_count === 0 ? "is-empty" : ""}">${account.unread_count}</span>
    </button>
  `).join("");

  document.querySelectorAll("[data-account-id]").forEach((button) => {
    button.addEventListener("click", async () => {
      state.selectedAccountId = button.dataset.accountId;
      state.selectedConversationId = null;
      state.selectedConversation = null;
      updateInboxSubtitle();
      hideConversation();
      renderAccounts();
      await loadConversations();
    });
  });
}

function updateInboxSubtitle() {
  if (state.selectedAccountId === "all") {
    els.inboxSubtitle.textContent = "全部账号";
    return;
  }
  const account = state.accounts.find((item) => String(item.id) === String(state.selectedAccountId));
  els.inboxSubtitle.textContent = account ? account.display_name : "指定账号";
}

function renderConversations() {
  updateInboxSubtitle();
  const keyword = state.search.trim().toLowerCase();
  const conversations = state.conversations.filter((conversation) => {
    if (!keyword) return true;
    return [
      conversation.customer_name,
      conversation.customer_handle,
      conversation.account_name,
      conversation.last_message_preview,
    ].some((value) => String(value || "").toLowerCase().includes(keyword));
  });

  if (conversations.length === 0) {
    els.conversationList.innerHTML = `
      <div class="list-empty">
        <strong>没有匹配会话</strong>
        <span>切换账号或搜索词再看。</span>
      </div>
    `;
    return;
  }

  els.conversationList.innerHTML = conversations.map((conversation) => `
    <button class="conversation-row ${conversation.id === state.selectedConversationId ? "is-active" : ""}" data-conversation-id="${conversation.id}" type="button">
      <span class="customer-avatar" style="--avatar-color: ${conversation.customer_avatar_color}">${escapeHtml(conversation.customer_name.slice(0, 1))}</span>
      <span class="conversation-main">
        <span class="conversation-topline">
          <strong>${escapeHtml(conversation.customer_name)}</strong>
          <time>${formatTime(conversation.last_message_at)}</time>
        </span>
        <span class="conversation-preview">${escapeHtml(conversation.last_message_preview)}</span>
        <span class="conversation-tags">
          <em>${escapeHtml(conversation.account_name)}</em>
        </span>
      </span>
      ${conversation.unread_count > 0 ? `<span class="unread-dot">${conversation.unread_count}</span>` : ""}
    </button>
  `).join("");

  document.querySelectorAll("[data-conversation-id]").forEach((button) => {
    button.addEventListener("click", () => loadMessages(Number(button.dataset.conversationId)));
  });
}

function showConversation(conversation, messages) {
  detectSelectedConversationUpdates(conversation.id, messages);
  els.emptyState.classList.add("is-hidden");
  els.chatSurface.classList.remove("is-hidden");
  els.customerAvatar.textContent = conversation.customer_name.slice(0, 1);
  els.customerAvatar.style.setProperty("--avatar-color", conversation.customer_avatar_color);
  els.customerName.textContent = conversation.customer_name;
  els.conversationMeta.textContent = conversation.account_name;
  els.messageTimeline.innerHTML = messages.map((message) => `
    <article class="message-bubble ${message.direction}">
      <div class="message-meta">
        <strong>${escapeHtml(message.sender_name)}</strong>
        <span>${formatTime(message.created_at)}</span>
      </div>
      <p>${escapeHtml(message.body)}</p>
    </article>
  `).join("");
  els.messageTimeline.scrollTop = els.messageTimeline.scrollHeight;
}

function snapshotConversation(conversation) {
  return {
    preview: conversation.last_message_preview || "",
    lastMessageAt: conversation.last_message_at || "",
    unreadCount: Number(conversation.unread_count || 0),
  };
}

function detectConversationUpdates(conversations) {
  conversations.forEach((conversation) => {
    const next = snapshotConversation(conversation);
    const previous = conversationSnapshots.get(conversation.id);
    const isSelected = conversation.id === state.selectedConversationId;

    if (conversationsInitialized && previous && !isSelected) {
      const messageChanged = next.lastMessageAt && next.lastMessageAt !== previous.lastMessageAt;
      const unreadIncreased = next.unreadCount > previous.unreadCount;
      if ((messageChanged || unreadIncreased) && next.preview) {
        showToast(`新消息：${next.preview}`, conversation.id);
      }
    }

    conversationSnapshots.set(conversation.id, next);
  });

  conversationsInitialized = true;
}

function detectSelectedConversationUpdates(conversationId, messages) {
  const maxId = messages.reduce((highest, message) => Math.max(highest, Number(message.id || 0)), 0);
  const previousMaxId = messageMaxIds.get(conversationId);

  if (previousMaxId && maxId > previousMaxId) {
    const newMessage = [...messages]
      .filter((message) => Number(message.id || 0) > previousMaxId)
      .reverse()
      .find((message) => message.direction !== "outbound");

    if (newMessage) {
      showToast(`新消息：${newMessage.body}`, conversationId);
    }
  }

  messageMaxIds.set(conversationId, maxId);
}

function showToast(text, conversationId) {
  if (!els.toast) return;
  els.toast.textContent = text;
  els.toast.dataset.conversationId = String(conversationId);
  els.toast.classList.remove("is-hidden");

  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(hideToast, 5000);
}

function hideToast() {
  if (!els.toast) return;
  els.toast.classList.add("is-hidden");
}

function hideConversation() {
  els.emptyState.classList.remove("is-hidden");
  els.chatSurface.classList.add("is-hidden");
  state.selectedConversation = null;
}

function escapeHtml(value) {
  return String(value || "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

els.search.addEventListener("input", () => {
  state.search = els.search.value;
  renderConversations();
});

els.composer.addEventListener("submit", async (event) => {
  event.preventDefault();
  const body = els.replyInput.value.trim();
  if (!body || !state.selectedConversationId) return;
  const senderName = state.selectedConversation?.account_name || "";
  await request(`/api/conversations/${state.selectedConversationId}/messages`, {
    method: "POST",
    body: JSON.stringify({ body, sender_name: senderName }),
  });
  els.replyInput.value = "";
  await loadMessages(state.selectedConversationId);
});

els.toast?.addEventListener("click", () => {
  const conversationId = Number(els.toast.dataset.conversationId || 0);
  hideToast();
  if (conversationId) {
    loadMessages(conversationId).catch((error) => console.error(error));
  }
});

async function refreshVisibleData() {
  if (refreshInFlight) return;
  refreshInFlight = true;
  try {
    if (state.selectedConversationId) {
      await loadMessages(state.selectedConversationId);
    } else {
      await loadAccounts();
      await loadConversations();
    }
  } finally {
    refreshInFlight = false;
  }
}

await loadAccounts();
await loadConversations();

window.setInterval(() => {
  refreshVisibleData().catch((error) => console.error(error));
}, 3000);
