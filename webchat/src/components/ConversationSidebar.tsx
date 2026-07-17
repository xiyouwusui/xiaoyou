import { useMemo, useState } from "react";
import { conversationKey, modeLabel, relativeDate } from "../format";
import type { ConnectionStatus, Conversation } from "../types";

interface ConversationSidebarProps {
  conversations: Conversation[];
  selected: Conversation | null;
  archivedOnly: boolean;
  connectionStatus: ConnectionStatus;
  onCreate: () => void;
  onSelect: (conversation: Conversation) => void;
  onToggleArchived: () => void;
}

const STATUS_LABELS: Record<ConnectionStatus, string> = {
  online: "实时事件已连接",
  offline: "连接中断，正在重试",
  connecting: "正在连接实时事件",
};

export function ConversationSidebar({
  conversations,
  selected,
  archivedOnly,
  connectionStatus,
  onCreate,
  onSelect,
  onToggleArchived,
}: ConversationSidebarProps) {
  const [search, setSearch] = useState("");
  const visible = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return conversations;
    return conversations.filter((conversation) => (
      [conversation.title, conversation.summary, conversation.lastMessage]
        .some((value) => String(value ?? "").toLowerCase().includes(query))
    ));
  }, [conversations, search]);

  return (
    <aside className="conversation-pane">
      <div className="brand-row">
        <div className="brand-mark small" aria-hidden="true">O</div>
        <div><strong>Omnibot</strong><span>Web Chat</span></div>
      </div>
      <button className="new-conversation-button" type="button" onClick={onCreate}>
        <span aria-hidden="true">＋</span> 新对话
      </button>
      <div className="conversation-toolbar">
        <label className="search-field">
          <span aria-hidden="true">⌕</span>
          <input
            type="search"
            placeholder="搜索对话"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        <button
          className={`quiet-button${archivedOnly ? " active" : ""}`}
          type="button"
          onClick={onToggleArchived}
        >
          {archivedOnly ? "返回" : "归档"}
        </button>
      </div>
      <div className="conversation-list" aria-live="polite">
        {!visible.length && (
          <div className="list-empty">{archivedOnly ? "没有归档对话" : "还没有对话"}</div>
        )}
        {visible.map((conversation) => {
          const active = conversationKey(conversation) === conversationKey(selected);
          const preview = conversation.summary || conversation.lastMessage || modeLabel(conversation.mode);
          return (
            <button
              key={conversationKey(conversation)}
              className={`conversation-item${active ? " active" : ""}`}
              type="button"
              onClick={() => onSelect(conversation)}
            >
              <span>
                <strong>{conversation.title || "新对话"}</strong>
                <p>{preview}</p>
              </span>
              <time>{relativeDate(conversation.updatedAt)}</time>
            </button>
          );
        })}
      </div>
      <footer className="connection-footer">
        <span className={`connection-dot ${connectionStatus === "connecting" ? "" : connectionStatus}`} />
        <span>{STATUS_LABELS[connectionStatus]}</span>
      </footer>
    </aside>
  );
}
