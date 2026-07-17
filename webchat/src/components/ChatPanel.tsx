import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import { isRecord } from "../api";
import { markdownToHtml, messageContent, messageTime, modeLabel } from "../format";
import type { Attachment, ChatMessage, Conversation } from "../types";

interface ChatPanelProps {
  conversation: Conversation | null;
  messages: ChatMessage[];
  globalError: string;
  sending: boolean;
  activeTaskId: string | null;
  clarifyTaskId: string | null;
  onArchive: () => void;
  onDelete: () => void;
  onSend: (text: string, attachments: Attachment[]) => Promise<boolean>;
  onCancel: () => void;
  onClearError: () => void;
  onAttachmentError: (error: unknown) => void;
}

function fileToAttachment(file: File): Promise<Attachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve({
      fileName: file.name,
      mimeType: file.type || "application/octet-stream",
      size: file.size,
      dataUrl: String(reader.result),
      isImage: file.type.startsWith("image/"),
    });
    reader.onerror = () => reject(reader.error ?? new Error(`无法读取 ${file.name}`));
    reader.readAsDataURL(file);
  });
}

function Message({ message }: { message: ChatMessage }) {
  const content = messageContent(message);
  const isUser = Number(message.user) === 1;
  const rawCard = isRecord(content.cardData) ? content.cardData : null;
  const card = rawCard ?? content;
  const attachments = Array.isArray(content.attachments)
    ? content.attachments.filter(isRecord)
    : [];
  const reasoning = String(message.reasoning_content ?? message.reasoningContent ?? "").trim();
  const classes = `message-row ${isUser ? "user" : "assistant"}${message.isError ? " error" : ""}`;

  if (Number(message.type) === 2 || rawCard) {
    const title = card.toolTitle ?? card.title ?? card.toolType ?? card.type ?? "工具运行";
    const status = card.status ?? (message.isLoading ? "running" : "completed");
    return (
      <article className={classes}>
        <div className="message-content">
          <details className="tool-message" open={status === "running"}>
            <summary>{String(title)} · {String(status)}</summary>
            <pre>{JSON.stringify(card, null, 2)}</pre>
          </details>
        </div>
      </article>
    );
  }

  const text = String(content.text ?? "");
  return (
    <article className={classes}>
      <div className="message-content">
        {reasoning && (
          <details className="message-reasoning">
            <summary>思考过程</summary>
            <div dangerouslySetInnerHTML={{ __html: markdownToHtml(reasoning) }} />
          </details>
        )}
        <div
          className="message-text"
          dangerouslySetInnerHTML={{
            __html: markdownToHtml(text || (message.isLoading ? "正在生成…" : "")),
          }}
        />
        {!!attachments.length && (
          <div className="message-attachments">
            {attachments.map((attachment, index) => (
              <span className="attachment-chip" key={`${String(attachment.fileName ?? attachment.name)}-${index}`}>
                <span>{String(attachment.fileName ?? attachment.name ?? "附件")}</span>
              </span>
            ))}
          </div>
        )}
      </div>
    </article>
  );
}

export function ChatPanel({
  conversation,
  messages,
  globalError,
  sending,
  activeTaskId,
  clarifyTaskId,
  onArchive,
  onDelete,
  onSend,
  onCancel,
  onClearError,
  onAttachmentError,
}: ChatPanelProps) {
  const [draft, setDraft] = useState("");
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const messageListRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const attachmentInputRef = useRef<HTMLInputElement>(null);
  const sortedMessages = [...messages].sort((left, right) => messageTime(left) - messageTime(right));
  const canSend = !sending && (clarifyTaskId ? Boolean(draft.trim()) : Boolean(draft.trim() || attachments.length));

  useEffect(() => {
    const list = messageListRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [messages]);

  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 160)}px`;
  }, [draft]);

  async function submit(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    if (!canSend) return;
    onClearError();
    const sent = await onSend(draft.trim(), attachments);
    if (sent) {
      setDraft("");
      setAttachments([]);
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void submit();
    }
  }

  async function addAttachments(event: ChangeEvent<HTMLInputElement>) {
    const files = [...(event.target.files ?? [])];
    event.target.value = "";
    if (!files.length) return;
    try {
      const nextAttachments = await Promise.all(files.map(fileToAttachment));
      setAttachments((current) => [...current, ...nextAttachments]);
    } catch (error) {
      onAttachmentError(error);
    }
  }

  return (
    <section className="chat-pane">
      <header className="pane-header chat-header">
        <div className="title-block">
          <h2>{conversation?.title || "选择一个对话"}</h2>
          <p>{conversation
            ? `${modeLabel(conversation.mode)} · ${Number(conversation.messageCount ?? 0)} 条消息`
            : "聊天、工具调用与实时输出"}</p>
        </div>
        <div className="header-actions">
          <button className="quiet-button" type="button" disabled={!conversation} onClick={onArchive}>
            {conversation?.isArchived ? "取消归档" : "归档"}
          </button>
          <button className="danger-button" type="button" disabled={!conversation} onClick={onDelete}>删除</button>
        </div>
      </header>
      {globalError && <div className="global-error" role="alert">{globalError}</div>}
      <div className="message-list" aria-live="polite" ref={messageListRef}>
        {!sortedMessages.length && (
          <div className="empty-state"><span>O</span><p>有什么可以帮助你的？</p></div>
        )}
        {sortedMessages.map((message, index) => (
          <Message message={message} key={String(message.id ?? `${messageTime(message)}-${index}`)} />
        ))}
      </div>
      {clarifyTaskId && (
        <div className="clarify-banner">Agent 正在等待你的补充说明。发送下一条消息后将继续执行。</div>
      )}
      <form className="composer" onSubmit={(event) => void submit(event)}>
        {!!attachments.length && (
          <div className="attachment-list">
            {attachments.map((attachment, index) => (
              <span className="attachment-chip" key={`${attachment.fileName}-${index}`}>
                <span>{attachment.fileName}</span>
                <button
                  type="button"
                  aria-label="移除附件"
                  onClick={() => setAttachments((current) => current.filter((_, itemIndex) => itemIndex !== index))}
                >×</button>
              </span>
            ))}
          </div>
        )}
        <textarea
          ref={textareaRef}
          rows={1}
          placeholder="直接和 Agent 对话…"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={handleKeyDown}
        />
        <div className="composer-actions">
          <button
            className="icon-button"
            type="button"
            aria-label="添加附件"
            disabled={sending}
            onClick={() => attachmentInputRef.current?.click()}
          >＋</button>
          <input ref={attachmentInputRef} type="file" multiple hidden onChange={(event) => void addAttachments(event)} />
          <span className="composer-status">{sending ? "正在发送…" : "Enter 发送 · Shift+Enter 换行"}</span>
          {activeTaskId && <button className="quiet-button" type="button" onClick={onCancel}>停止</button>}
          <button className="send-button" type="submit" aria-label="发送" disabled={!canSend}>↑</button>
        </div>
      </form>
    </section>
  );
}
