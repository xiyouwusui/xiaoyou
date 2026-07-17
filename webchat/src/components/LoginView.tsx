import { useState, type FormEvent } from "react";

interface LoginViewProps {
  initialToken: string;
  busy: boolean;
  error: string;
  onLogin: (token: string) => Promise<void>;
}

export function LoginView({ initialToken, busy, error, onLogin }: LoginViewProps) {
  const [token, setToken] = useState(initialToken);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = token.trim();
    if (value) void onLogin(value);
  }

  return (
    <main className="login-view">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="brand-mark" aria-hidden="true">O</div>
        <p className="eyebrow">LOCAL CONTROL</p>
        <h1 id="login-title">Omnibot Web Chat</h1>
        <p className="login-copy">输入本机 MCP Server Token，连接同一局域网中的 Omnibot。</p>
        <form className="login-form" onSubmit={submit}>
          <label htmlFor="token-input">Server Token</label>
          <input
            id="token-input"
            name="token"
            type="password"
            autoComplete="current-password"
            value={token}
            disabled={busy}
            onChange={(event) => setToken(event.target.value)}
            required
          />
          <button className="primary-button" type="submit" disabled={busy}>
            {busy ? "正在连接…" : "连接"}
          </button>
        </form>
        <p className="form-error" role="alert">{error}</p>
      </section>
    </main>
  );
}
