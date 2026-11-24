import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { api } from "../api/client";
import { ensureAiAwake, retryAi, useAiStatus } from "./wakeAi";
import { ChatThread, type ChatReply, type HistoryMessage } from "./ChatThread";

// Public customer chat with the business's assistant (no login). The
// session id ties messages into one conversation on the backend; it lives
// in localStorage so a reload keeps the thread going. ponytail: the visible
// history is in-memory only - add GET history when reload-and-see matters.
interface ChatInfo {
  businessName: string;
  assistantName: string;
  greeting: string | null;
  captchaRequired: boolean;
}

const TURNSTILE_SITE_KEY = import.meta.env.VITE_TURNSTILE_SITE_KEY as string | undefined;

declare global {
  interface Window {
    turnstile?: { render: (el: HTMLElement, opts: { sitekey: string; callback: (t: string) => void }) => void };
  }
}

// fresh = "Nueva conversación": a new session id, so the backend starts a new
// conversation; the old one stays saved for the owner.
function sessionIdFor(tenantId: string, fresh = false): string {
  const key = `chat-session:${tenantId}`;
  try {
    const existing = localStorage.getItem(key);
    if (existing && !fresh) return existing;
    const created = crypto.randomUUID();
    localStorage.setItem(key, created);
    return created;
  } catch {
    return crypto.randomUUID();
  }
}

// /chat/:tenantId?embed=1 runs inside the website widget (public/widget.js):
// it fills the popup and its close button tells the host page to hide it.
const EMBEDDED = new URLSearchParams(window.location.search).has("embed");
const closeWidget = () => window.parent.postMessage({ type: "recepta:close" }, "*");

// /chat/demo is the public demo's fixed link; its tenant id changes on every reset.
export default function ChatPage() {
  const { tenantId: param = "" } = useParams();
  const [resolved, setResolved] = useState<string | null>(param === "demo" ? null : param);

  useEffect(() => {
    if (param !== "demo") {
      setResolved(param);
      return;
    }
    api.get<{ tenantId: string }>("/demo/chat-tenant").then((r) => setResolved(r.tenantId), () => setResolved(""));
  }, [param]);

  if (resolved === null) return <div className="chat-page"><p className="muted">Cargando…</p></div>;
  return <TenantChat key={resolved} tenantId={resolved} />;
}

function TenantChat({ tenantId }: { tenantId: string }) {
  const [sessionId, setSessionId] = useState(() => sessionIdFor(tenantId));
  const [info, setInfo] = useState<ChatInfo | null>(null);
  const [captchaToken, setCaptchaToken] = useState<string | null>(null);
  const captchaRef = useRef<HTMLDivElement>(null);
  const aiStatus = useAiStatus();

  useEffect(() => {
    api.get<ChatInfo>(`/public/${tenantId}/chat/info`).then(setInfo, () => setInfo(null));
  }, [tenantId]);

  const needsCaptcha = Boolean(info?.captchaRequired && TURNSTILE_SITE_KEY);
  useEffect(() => {
    if (!needsCaptcha || captchaToken || !captchaRef.current) return;
    const el = captchaRef.current;
    const render = () => {
      window.turnstile?.render(el, { sitekey: TURNSTILE_SITE_KEY!, callback: setCaptchaToken });
    };
    if (window.turnstile) {
      render();
      return;
    }
    const script = document.createElement("script");
    script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js";
    script.async = true;
    script.onload = render;
    document.head.appendChild(script);
    // Re-runs after "Nueva conversación": a new session needs a new token.
  }, [needsCaptcha, captchaToken]);

  // After a long pause the AI may have gone back to sleep: wake it first (a
  // no-op within REWAKE_MS of the last wake). If it doesn't wake, send anyway:
  // the backend falls back to the button bot.
  const send = async (message: string | null, selectionId: string | null) => {
    await ensureAiAwake();
    return api.post<ChatReply>(`/public/${tenantId}/chat`, { sessionId, message, selectionId, turnstileToken: captchaToken });
  };

  const cancel = (appointmentId: string) =>
    api.post<{ reply: string }>(`/public/${tenantId}/chat/cancel`, { sessionId, appointmentId }).then((r) => r.reply);

  // Stable identity: ChatThread loads the history once per session.
  const loadHistory = useCallback(
    () => api.get<HistoryMessage[]>(`/public/${tenantId}/chat/history?sessionId=${sessionId}`),
    [tenantId, sessionId],
  );

  const restart = () => {
    setCaptchaToken(null);
    setSessionId(sessionIdFor(tenantId, true));
  };

  return (
    <div className={EMBEDDED ? "chat-page embed" : "chat-page"}>
      {needsCaptcha && !captchaToken && <div ref={captchaRef} className="chat-captcha" />}
      <ChatThread
        key={sessionId}
        send={send}
        cancel={cancel}
        loadHistory={loadHistory}
        title={info?.businessName ?? "Chat"}
        disabled={needsCaptcha && !captchaToken}
        placeholder={needsCaptcha && !captchaToken ? "Completá la verificación para escribir" : undefined}
        onRestart={restart}
        onClose={EMBEDDED ? closeWidget : undefined}
        aiStatus={aiStatus}
        onRetryAi={() => void retryAi()}
      />
    </div>
  );
}
