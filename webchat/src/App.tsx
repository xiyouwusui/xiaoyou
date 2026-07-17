import { useEffect, useRef, useState } from "react";
import { request } from "./api";
import { ChatPanel } from "./components/ChatPanel";
import { ContextPane } from "./components/ContextPane";
import { ConversationSidebar } from "./components/ConversationSidebar";
import { LoginView } from "./components/LoginView";
import { conversationKey } from "./format";
import { useRealtime } from "./hooks/useRealtime";
import type {
  Attachment,
  BootstrapPayload,
  BrowserActionResult,
  BrowserSnapshot,
  ChatMessage,
  ContextPanelName,
  Conversation,
  MobileSection,
  RealtimeEventData,
  RealtimeEventName,
  RunResult,
  WorkspaceFilePayload,
  WorkspaceInfo,
  WorkspaceItem,
  WorkspaceListing,
} from "./types";

const TOKEN_STORAGE_KEY = "omnibot_webchat_token";

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error ?? "请求失败");
}

function initialToken(): string {
  const queryToken = new URLSearchParams(window.location.search).get("token")?.trim();
  return queryToken || localStorage.getItem(TOKEN_STORAGE_KEY)?.trim() || "";
}

export default function App() {
  const [authenticated, setAuthenticated] = useState(false);
  const [authenticating, setAuthenticating] = useState(false);
  const [loginError, setLoginError] = useState("");
  const [globalError, setGlobalError] = useState("");
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedConversation, setSelectedConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [archivedOnly, setArchivedOnly] = useState(false);
  const [sending, setSending] = useState(false);
  const [activeTaskId, setActiveTaskId] = useState<string | null>(null);
  const [clarifyTaskId, setClarifyTaskId] = useState<string | null>(null);
  const [workspaceInfo, setWorkspaceInfo] = useState<WorkspaceInfo | null>(null);
  const [workspacePath, setWorkspacePath] = useState("");
  const [workspaceItems, setWorkspaceItems] = useState<WorkspaceItem[]>([]);
  const [workspaceFilePath, setWorkspaceFilePath] = useState<string | null>(null);
  const [workspaceContent, setWorkspaceContent] = useState("");
  const [workspaceDirty, setWorkspaceDirty] = useState(false);
  const [browserSnapshot, setBrowserSnapshot] = useState<BrowserSnapshot | null>(null);
  const [browserFrameSeed, setBrowserFrameSeed] = useState(0);
  const [contextPanel, setContextPanel] = useState<ContextPanelName>("workspace");
  const [mobileSection, setMobileSectionState] = useState<MobileSection>("chat");
  const [conversationsOpen, setConversationsOpen] = useState(false);
  const [toast, setToast] = useState("");
  const selectedRef = useRef<Conversation | null>(null);
  const workspacePathRef = useRef("");
  const toastTimerRef = useRef<number | null>(null);
  const autoLoginToken = useRef(initialToken());

  function showError(error: unknown) {
    setGlobalError(errorMessage(error));
  }

  function showToast(message: string) {
    if (toastTimerRef.current !== null) window.clearTimeout(toastTimerRef.current);
    setToast(message);
    toastTimerRef.current = window.setTimeout(() => setToast(""), 2600);
  }

  async function loadMessages(conversation = selectedRef.current) {
    if (!conversation) return;
    try {
      const payload = await request<ChatMessage[]>(`/conversations/${conversation.id}/messages`, {
        query: { mode: conversation.mode ?? "normal" },
      });
      if (conversationKey(conversation) === conversationKey(selectedRef.current)) {
        setMessages(Array.isArray(payload) ? payload : []);
      }
    } catch (error) {
      showError(error);
    }
  }

  async function loadConversations(preserveSelection = true, archived = archivedOnly) {
    const payload = await request<Conversation[]>("/conversations", {
      query: { includeArchived: archived, archivedOnly: archived },
    });
    const previousKey = preserveSelection ? conversationKey(selectedRef.current) : null;
    const nextConversations = (Array.isArray(payload) ? payload : [])
      .filter((item) => archived ? item.isArchived : !item.isArchived)
      .sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0));
    const nextSelected = nextConversations.find((item) => conversationKey(item) === previousKey)
      ?? nextConversations[0]
      ?? null;
    setConversations(nextConversations);
    selectedRef.current = nextSelected;
    setSelectedConversation(nextSelected);
    if (nextSelected) await loadMessages(nextSelected);
    else setMessages([]);
  }

  async function loadWorkspace(path = workspacePathRef.current, reportError = true) {
    if (!path) return;
    try {
      const payload = await request<WorkspaceListing>("/workspaces", { query: { path } });
      const nextPath = String(payload?.path ?? path);
      workspacePathRef.current = nextPath;
      setWorkspacePath(nextPath);
      setWorkspaceItems(Array.isArray(payload?.items) ? payload.items : []);
    } catch (error) {
      if (reportError) showError(error);
    }
  }

  async function authenticate(token: string) {
    setLoginError("");
    setAuthenticating(true);
    try {
      await request("/session/bootstrap", { method: "POST", body: { token } });
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
      const bootstrap = await request<BootstrapPayload>("/bootstrap");
      const info = bootstrap?.workspace?.workspace ?? null;
      const rootPath = bootstrap?.workspace?.root?.path ?? info?.rootPath ?? "";
      setWorkspaceInfo(info);
      workspacePathRef.current = rootPath;
      setWorkspacePath(rootPath);
      setBrowserSnapshot(bootstrap?.browser ?? null);
      setAuthenticated(true);

      const url = new URL(window.location.href);
      if (url.searchParams.has("token")) {
        url.searchParams.delete("token");
        window.history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
      }
      await Promise.all([
        loadConversations(false, false),
        rootPath ? loadWorkspace(rootPath, false) : Promise.resolve(),
      ]);
    } catch (error) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      setLoginError(errorMessage(error));
      setAuthenticated(false);
    } finally {
      setAuthenticating(false);
    }
  }

  async function createConversation() {
    setGlobalError("");
    try {
      const conversation = await request<Conversation>("/conversations", {
        method: "POST",
        body: { title: "新对话", mode: "normal" },
      });
      setArchivedOnly(false);
      selectedRef.current = conversation;
      setSelectedConversation(conversation);
      await loadConversations(true, false);
      setConversationsOpen(false);
    } catch (error) {
      showError(error);
    }
  }

  async function selectConversation(conversation: Conversation) {
    selectedRef.current = conversation;
    setSelectedConversation(conversation);
    setConversationsOpen(false);
    await loadMessages(conversation);
  }

  async function toggleArchivedFilter() {
    const next = !archivedOnly;
    setArchivedOnly(next);
    try {
      await loadConversations(false, next);
    } catch (error) {
      showError(error);
    }
  }

  async function updateArchiveState() {
    const conversation = selectedRef.current;
    if (!conversation) return;
    try {
      await request(`/conversations/${conversation.id}`, {
        method: "PATCH",
        body: { isArchived: !conversation.isArchived },
      });
      await loadConversations(false);
    } catch (error) {
      showError(error);
    }
  }

  async function deleteConversation() {
    const conversation = selectedRef.current;
    if (!conversation) return;
    if (!window.confirm(`删除“${conversation.title || "当前对话"}”？此操作无法撤销。`)) return;
    try {
      await request(`/conversations/${conversation.id}`, { method: "DELETE" });
      await loadConversations(false);
    } catch (error) {
      showError(error);
    }
  }

  async function sendMessage(text: string, attachments: Attachment[]): Promise<boolean> {
    setGlobalError("");
    setSending(true);
    try {
      if (clarifyTaskId) {
        await request(`/tasks/${encodeURIComponent(clarifyTaskId)}/clarify`, {
          method: "POST",
          body: { reply: text },
        });
        setClarifyTaskId(null);
        return true;
      }

      let conversation = selectedRef.current;
      if (!conversation) {
        conversation = await request<Conversation>("/conversations", {
          method: "POST",
          body: { title: text || "新对话", mode: "normal" },
        });
        selectedRef.current = conversation;
        setSelectedConversation(conversation);
        setArchivedOnly(false);
      }
      const result = await request<RunResult>(`/conversations/${conversation.id}/runs`, {
        method: "POST",
        body: {
          userMessage: text,
          conversationMode: conversation.mode ?? "normal",
          attachments,
        },
      });
      setActiveTaskId(String(result?.taskId ?? "") || null);
      void loadConversations(true, false).catch(showError);
      return true;
    } catch (error) {
      showError(error);
      return false;
    } finally {
      setSending(false);
    }
  }

  async function cancelRun() {
    if (!activeTaskId) return;
    try {
      await request(`/tasks/${encodeURIComponent(activeTaskId)}/cancel`, { method: "POST" });
      setActiveTaskId(null);
    } catch (error) {
      showError(error);
    }
  }

  async function openWorkspaceFile(path: string) {
    try {
      const payload = await request<WorkspaceFilePayload>("/workspaces/file", {
        query: { path, maxChars: 64_000 },
      });
      setWorkspaceFilePath(path);
      setWorkspaceContent(String(payload?.content ?? ""));
      setWorkspaceDirty(false);
    } catch (error) {
      showError(error);
    }
  }

  function workspaceParentPath(): string {
    const root = String(workspaceInfo?.rootPath ?? "").replace(/\/$/, "");
    const current = String(workspacePathRef.current).replace(/\/$/, "");
    if (!current || current === root) return current;
    const index = current.lastIndexOf("/");
    const parent = index > 0 ? current.slice(0, index) : "/";
    return root && !parent.startsWith(root) ? root : parent;
  }

  async function saveWorkspaceFile() {
    if (!workspaceFilePath || !workspaceDirty) return;
    try {
      await request("/workspaces/file", {
        method: "PUT",
        body: { path: workspaceFilePath, content: workspaceContent, append: false },
      });
      setWorkspaceDirty(false);
      showToast("文件已保存");
    } catch (error) {
      showError(error);
    }
  }

  async function refreshBrowser(reportError = true) {
    try {
      setBrowserSnapshot(await request<BrowserSnapshot>("/browser/snapshot"));
      setBrowserFrameSeed((seed) => seed + 1);
    } catch (error) {
      if (reportError) showError(error);
    }
  }

  async function browserAction(payload: Record<string, unknown>) {
    try {
      const result = await request<BrowserActionResult>("/browser/action", { method: "POST", body: payload });
      if (result?.snapshot !== undefined) setBrowserSnapshot(result.snapshot);
      setBrowserFrameSeed((seed) => seed + 1);
    } catch (error) {
      showError(error);
    }
  }

  function sameSelectedConversation(data: RealtimeEventData): boolean {
    const selected = selectedRef.current;
    return Boolean(
      selected
      && Number(data.conversationId ?? 0) === Number(selected.id)
      && String(data.conversationMode ?? data.mode ?? "normal") === String(selected.mode ?? "normal"),
    );
  }

  function handleRealtimeEvent(eventName: RealtimeEventName, data: RealtimeEventData) {
    if (["conversation_created", "conversation_updated", "conversation_deleted"].includes(eventName)) {
      void loadConversations(true).catch(showError);
      return;
    }
    if (eventName === "messages_replaced" && sameSelectedConversation(data)) {
      setMessages(Array.isArray(data.messages) ? data.messages : []);
      return;
    }
    if (eventName === "workspace_changed" && workspacePathRef.current) {
      void loadWorkspace(workspacePathRef.current, false);
      return;
    }
    if (eventName === "browser_snapshot_updated") {
      setBrowserSnapshot(data.snapshot ?? null);
      setBrowserFrameSeed((seed) => seed + 1);
      return;
    }
    if (eventName !== "agent_stream_event") return;
    const kind = String(data.kind ?? "");
    const taskId = String(data.taskId ?? "");
    if (kind === "clarify_required") setClarifyTaskId(taskId || activeTaskId);
    if (["completed", "error"].includes(kind)) {
      setActiveTaskId(null);
      setClarifyTaskId(null);
    }
    if (kind === "tool_completed" && String(data.toolType ?? "") === "browser") {
      void refreshBrowser(false);
    }
  }

  const connectionStatus = useRealtime(authenticated, handleRealtimeEvent);

  function selectMobileSection(section: MobileSection) {
    setMobileSectionState(section);
    if (section !== "chat") setContextPanel(section);
  }

  useEffect(() => {
    const token = autoLoginToken.current;
    if (token) void authenticate(token);
    return () => {
      if (toastTimerRef.current !== null) window.clearTimeout(toastTimerRef.current);
    };
  }, []);

  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => {
      if (!workspaceDirty) return;
      event.preventDefault();
    };
    window.addEventListener("beforeunload", guard);
    return () => window.removeEventListener("beforeunload", guard);
  }, [workspaceDirty]);

  if (!authenticated) {
    return (
      <LoginView
        initialToken={autoLoginToken.current}
        busy={authenticating}
        error={loginError}
        onLogin={authenticate}
      />
    );
  }

  return (
    <>
      <div
        className={`app-view${conversationsOpen ? " conversations-open" : ""}`}
        data-mobile-section={mobileSection}
      >
        <header className="mobile-header">
          <button
            className="icon-button"
            type="button"
            aria-label="打开对话列表"
            onClick={() => setConversationsOpen(true)}
          >☰</button>
          <strong>Omnibot</strong>
          <span
            className={`connection-dot ${connectionStatus === "connecting" ? "" : connectionStatus}`}
            title="实时连接状态"
          />
        </header>

        <ConversationSidebar
          conversations={conversations}
          selected={selectedConversation}
          archivedOnly={archivedOnly}
          connectionStatus={connectionStatus}
          onCreate={() => void createConversation()}
          onSelect={(conversation) => void selectConversation(conversation)}
          onToggleArchived={() => void toggleArchivedFilter()}
        />
        <ChatPanel
          conversation={selectedConversation}
          messages={messages}
          globalError={globalError}
          sending={sending}
          activeTaskId={activeTaskId}
          clarifyTaskId={clarifyTaskId}
          onArchive={() => void updateArchiveState()}
          onDelete={() => void deleteConversation()}
          onSend={sendMessage}
          onCancel={() => void cancelRun()}
          onClearError={() => setGlobalError("")}
          onAttachmentError={showError}
        />
        <ContextPane
          activePanel={contextPanel}
          workspacePath={workspacePath}
          workspaceItems={workspaceItems}
          workspaceFilePath={workspaceFilePath}
          workspaceContent={workspaceContent}
          workspaceDirty={workspaceDirty}
          browserSnapshot={browserSnapshot}
          browserFrameSeed={browserFrameSeed}
          onSelectPanel={setContextPanel}
          onWorkspacePath={() => {
            const parent = workspaceParentPath();
            if (parent && parent !== workspacePathRef.current) void loadWorkspace(parent);
          }}
          onWorkspaceItem={(item) => {
            if (item.isDirectory) void loadWorkspace(item.path);
            else void openWorkspaceFile(item.path);
          }}
          onWorkspaceRefresh={() => void loadWorkspace()}
          onWorkspaceContent={(content) => {
            setWorkspaceContent(content);
            setWorkspaceDirty(true);
          }}
          onWorkspaceSave={() => void saveWorkspaceFile()}
          onBrowserAction={(payload) => void browserAction(payload)}
          onBrowserRefresh={() => void refreshBrowser()}
        />

        <nav className="mobile-nav" aria-label="Web Chat 区域">
          {(["chat", "workspace", "browser"] as MobileSection[]).map((section) => (
            <button
              className={mobileSection === section ? "active" : ""}
              type="button"
              onClick={() => selectMobileSection(section)}
              key={section}
            >{{ chat: "聊天", workspace: "工作区", browser: "浏览器" }[section]}</button>
          ))}
        </nav>
        <button
          className="conversation-scrim"
          type="button"
          aria-label="关闭对话列表"
          onClick={() => setConversationsOpen(false)}
        />
      </div>
      {toast && <div className="toast" role="status">{toast}</div>}
    </>
  );
}
