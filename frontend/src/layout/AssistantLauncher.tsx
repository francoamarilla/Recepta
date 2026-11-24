import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import { ChatThread, type ChatReply } from "../chat/ChatThread";
import { ensureAiAwake, retryAi, useAiStatus } from "../chat/wakeAi";

// The owner's test chat, always at hand: a floating button with the brand
// isotype (bottom right, like a site's WhatsApp button) that unfolds a chat
// window docked in the corner, Messenger-style. While the window is open the
// button hides (the chat has its own close button) and comes back on close.
// It talks to the playground endpoint: draft config, bookings simulated.
//
// The window stays mounted while closed, so closing and reopening keeps the
// conversation; "Nueva conversación" (⋮ menu) starts over.
export function AssistantLauncher({ businessName }: { businessName: string }) {
  const [open, setOpen] = useState(false);
  const [sessionId, setSessionId] = useState(() => crypto.randomUUID());
  const dockRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const opened = useRef(false);

  // Wakes the AI service while the owner is still looking at the dashboard.
  const aiStatus = useAiStatus();

  // The button disappears when the chat opens, so focus has to move: into
  // the message field on open, back to the button on close.
  useEffect(() => {
    if (open) {
      opened.current = true;
      dockRef.current?.querySelector<HTMLInputElement>(".wa-input input")?.focus();
    } else if (opened.current) {
      buttonRef.current?.focus();
    }
  }, [open]);

  // Same as the public chat: re-wake after a long pause, then send regardless.
  const send = async (message: string | null): Promise<ChatReply> => {
    await ensureAiAwake();
    return api.post<ChatReply>("/assistant/playground", { sessionId, message: message ?? "" });
  };

  return (
    <>
      <div
        ref={dockRef}
        className={open ? "assist-dock open" : "assist-dock"}
        id="assistant-chat"
        role="dialog"
        aria-label="Probar el asistente"
      >
        <ChatThread
          key={sessionId}
          send={send}
          title={businessName}
          placeholder="Escribí como si fueras un cliente"
          notice="Probá tu asistente como si fueras un cliente. Usa el borrador de la configuración y las reservas son simuladas."
          onRestart={() => setSessionId(crypto.randomUUID())}
          onClose={() => setOpen(false)}
          aiStatus={aiStatus}
          onRetryAi={() => void retryAi()}
        />
      </div>
      <button
        ref={buttonRef}
        type="button"
        className="assist-launcher"
        aria-label="Abrir el chat del asistente"
        title="Probar el asistente"
        aria-controls="assistant-chat"
        aria-expanded={open}
        onClick={() => setOpen(true)}
      >
        <img className="theme-light" src="/brand/isotipo.svg" alt="" />
        <img className="theme-dark" src="/brand/isotipo-oscuro.svg" alt="" />
      </button>
    </>
  );
}
