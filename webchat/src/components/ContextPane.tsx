import { useEffect, useMemo, useState, type FormEvent } from "react";
import { browserFrameUrl, workspaceDownloadUrl } from "../api";
import { formatBytes } from "../format";
import type {
  BrowserSnapshot,
  ContextPanelName,
  WorkspaceItem,
} from "../types";

interface ContextPaneProps {
  activePanel: ContextPanelName;
  workspacePath: string;
  workspaceItems: WorkspaceItem[];
  workspaceFilePath: string | null;
  workspaceContent: string;
  workspaceDirty: boolean;
  browserSnapshot: BrowserSnapshot | null;
  browserFrameSeed: number;
  onSelectPanel: (panel: ContextPanelName) => void;
  onWorkspacePath: () => void;
  onWorkspaceItem: (item: WorkspaceItem) => void;
  onWorkspaceRefresh: () => void;
  onWorkspaceContent: (content: string) => void;
  onWorkspaceSave: () => void;
  onBrowserAction: (payload: Record<string, unknown>) => void;
  onBrowserRefresh: () => void;
}

export function ContextPane({
  activePanel,
  workspacePath,
  workspaceItems,
  workspaceFilePath,
  workspaceContent,
  workspaceDirty,
  browserSnapshot,
  browserFrameSeed,
  onSelectPanel,
  onWorkspacePath,
  onWorkspaceItem,
  onWorkspaceRefresh,
  onWorkspaceContent,
  onWorkspaceSave,
  onBrowserAction,
  onBrowserRefresh,
}: ContextPaneProps) {
  const [browserUrl, setBrowserUrl] = useState("");
  const browserAvailable = browserSnapshot?.available === true;
  const frameUrl = useMemo(() => browserFrameUrl(browserFrameSeed), [browserFrameSeed]);

  useEffect(() => {
    if (browserSnapshot?.currentUrl) setBrowserUrl(browserSnapshot.currentUrl);
  }, [browserSnapshot?.currentUrl]);

  function navigate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const url = browserUrl.trim();
    if (url) onBrowserAction({ action: "navigate", url, tool_title: "Web Chat Navigate" });
  }

  return (
    <aside className="context-pane">
      <div className="context-tabs" role="tablist">
        {(["workspace", "browser"] as ContextPanelName[]).map((panel) => (
          <button
            className={`context-tab${activePanel === panel ? " active" : ""}`}
            type="button"
            role="tab"
            aria-selected={activePanel === panel}
            onClick={() => onSelectPanel(panel)}
            key={panel}
          >{panel === "workspace" ? "工作区" : "浏览器"}</button>
        ))}
      </div>

      <section id="workspace-panel" className={`context-panel${activePanel === "workspace" ? " active" : ""}`}>
        <header className="context-header">
          <div>
            <strong>工作区</strong>
            <button className="path-button" type="button" title={workspacePath} onClick={onWorkspacePath}>
              {workspacePath || "工作区"}
            </button>
          </div>
          <div className="header-actions">
            {workspaceFilePath && (
              <a className="quiet-link" href={workspaceDownloadUrl(workspaceFilePath)}>下载</a>
            )}
            <button className="quiet-button" type="button" onClick={onWorkspaceRefresh}>刷新</button>
            <button
              className="primary-small-button"
              type="button"
              disabled={!workspaceDirty || !workspaceFilePath}
              onClick={onWorkspaceSave}
            >保存</button>
          </div>
        </header>
        <div className="workspace-layout">
          <div className="workspace-list">
            {!workspaceItems.length && <div className="list-empty">目录为空</div>}
            {workspaceItems.map((item) => (
              <button
                className={`workspace-item${item.path === workspaceFilePath ? " active" : ""}`}
                type="button"
                onClick={() => onWorkspaceItem(item)}
                key={item.path}
              >
                <span aria-hidden="true">{item.isDirectory ? "▸" : "·"}</span>
                <span>{item.name}</span>
                <small>{item.isDirectory ? "" : formatBytes(item.size)}</small>
              </button>
            ))}
          </div>
          <div className="workspace-editor-wrap">
            <p>{workspaceFilePath || "选择文件以查看或编辑"}</p>
            <textarea
              id="workspace-editor"
              spellCheck={false}
              disabled={!workspaceFilePath}
              value={workspaceContent}
              onChange={(event) => onWorkspaceContent(event.target.value)}
            />
          </div>
        </div>
      </section>

      <section id="browser-panel" className={`context-panel${activePanel === "browser" ? " active" : ""}`}>
        <header className="context-header browser-controls">
          <form className="browser-address-form" onSubmit={navigate}>
            <input
              type="url"
              placeholder="输入网址并远程导航"
              value={browserUrl}
              onChange={(event) => setBrowserUrl(event.target.value)}
            />
            <button className="primary-small-button" type="submit">打开</button>
          </form>
          <div className="browser-buttons">
            <button
              className="quiet-button"
              type="button"
              onClick={() => onBrowserAction({ action: "scroll", direction: "up", amount: 420, tool_title: "Web Chat Scroll Up" })}
            >上滑</button>
            <button
              className="quiet-button"
              type="button"
              onClick={() => onBrowserAction({ action: "scroll", direction: "down", amount: 420, tool_title: "Web Chat Scroll Down" })}
            >下滑</button>
            <button className="quiet-button" type="button" onClick={onBrowserRefresh}>刷新画面</button>
          </div>
        </header>
        <div className="browser-summary">
          <strong>{browserSnapshot?.title || "暂无浏览器会话"}</strong>
          <span>{browserSnapshot?.currentUrl || ""}</span>
        </div>
        <div className="browser-frame-wrap">
          {browserAvailable
            ? <img src={frameUrl} alt="浏览器实时画面" />
            : <p>当前没有可镜像的浏览器会话</p>}
        </div>
      </section>
    </aside>
  );
}
