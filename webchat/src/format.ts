import { isRecord } from "./api";
import type { ChatMessage, Conversation } from "./types";

export function escapeHtml(value: unknown): string {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function inlineMarkdown(value: string): string {
  return escapeHtml(value)
    .replace(/`([^`\n]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
}

export function markdownToHtml(value: unknown): string {
  const source = String(value ?? "").trim();
  if (!source) return "";
  return source.split(/```/).map((block, index) => {
    if (index % 2 === 1) {
      const firstBreak = block.indexOf("\n");
      const code = firstBreak >= 0 ? block.slice(firstBreak + 1) : block;
      return `<pre><code>${escapeHtml(code.trimEnd())}</code></pre>`;
    }
    return block
      .split(/\n{2,}/)
      .filter(Boolean)
      .map((paragraph) => `<p>${inlineMarkdown(paragraph).replaceAll("\n", "<br>")}</p>`)
      .join("");
  }).join("");
}

export function modeLabel(mode?: string): string {
  return ({
    normal: "普通",
    chat_only: "纯聊天",
    openclaw: "OpenClaw",
    subagent: "SubAgent",
    codex: "Codex",
  } as Record<string, string>)[mode ?? "normal"] ?? "普通";
}

export function relativeDate(raw?: number): string {
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return "";
  const date = new Date(value);
  const diff = Date.now() - value;
  if (diff < 60_000) return "刚刚";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时`;
  return `${date.getMonth() + 1}-${date.getDate()}`;
}

export function conversationKey(conversation: Conversation | null | undefined): string {
  return `${conversation?.mode ?? "normal"}:${Number(conversation?.id ?? 0)}`;
}

export function messageTime(message: ChatMessage): number {
  const raw = message.createAt;
  const date = typeof raw === "number" ? new Date(raw) : new Date(String(raw ?? ""));
  return Number.isNaN(date.getTime()) ? 0 : date.getTime();
}

export function formatBytes(raw?: number): string {
  const value = Number(raw ?? 0);
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

export function messageContent(message: ChatMessage): Record<string, unknown> {
  return isRecord(message.content) ? message.content : {};
}
