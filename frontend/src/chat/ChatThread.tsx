import { useEffect, useRef, useState, type FormEvent } from "react";
import type { AiStatus } from "./wakeAi";

// One chat UI, styled like a WhatsApp conversation, for the public customer
// chat and the owner's playground. The customer always writes first: there
// is no opening message from the assistant. The backend decides who answers
// (AI, button bot, person); this renders the thread, the bot's buttons and
// the "Cancelar este turno" action under an automatic booking.
export interface ChatOption {
  id: string;
  label: string;
}

export interface ChatAction {
  type: "booking_created" | "booking_cancelled" | "booking_rescheduled" | "handoff";
  appointmentId?: string | null;
  summary?: string | null;
}

export interface ChatReply {
  reply: string;
  options: ChatOption[];
  actions: ChatAction[];
  mode: "AI" | "FALLBACK" | "HUMAN";
}

export interface HistoryMessage {
  role: "USER" | "ASSISTANT";
  content: string;
  createdAt: string;
}

interface Line {
  from: "user" | "assistant";
  text: string;
  at: Date;
  options?: ChatOption[];
  actions?: ChatAction[];
}

interface Props {
  send: (message: string | null, selectionId: string | null) => Promise<ChatReply>;
  cancel?: (appointmentId: string) => Promise<string>;
  // The conversation so far, so the screen shows exactly what the assistant sees.
  loadHistory?: () => Promise<HistoryMessage[]>;
  title: string;
  disabled?: boolean;
  placeholder?: string;
  // Header menu: start over with an empty thread (the old one stays saved).
  onRestart?: () => void;
  // Closes the popup (website widget, dashboard chat).
  onClose?: () => void;
  // Grey notice shown while the thread is empty.
  notice?: string;
  // AI service state (wakeAi.ts). "waking": say so and hold the send button
  // (typing stays on). "unavailable": sending works (the button bot answers)
  // and the visitor can retry the check by hand.
  aiStatus?: AiStatus;
  onRetryAi?: () => void;
}

const time = (d: Date) => d.toLocaleTimeString("es-AR", { hour: "2-digit", minute: "2-digit", hour12: false });

function dayLabel(d: Date) {
  const today = new Date();
  const yesterday = new Date(today.getTime() - 86_400_000);
  if (d.toDateString() === today.toDateString()) return "Hoy";
  if (d.toDateString() === yesterday.toDateString()) return "Ayer";
  return d.toLocaleDateString("es-AR", { day: "numeric", month: "long", year: "numeric" });
}

function Ticks() {
  return (
    <svg className="wa-ticks" viewBox="0 0 16 11" aria-label="Leído">
      <path d="M11.1 0.6 4.9 7.7 2.2 5.2 1 6.5l3.9 3.6 7.4-8.4z M15 0.6 8.8 7.7l-0.8-0.7-1.2 1.4 2.1 1.9 7.4-8.4z" />
    </svg>
  );
}

export function ChatThread({
  send,
  cancel,
  loadHistory,
  title,
  disabled,
  placeholder = "Mensaje",
  onRestart,
  onClose,
  notice,
  aiStatus = "ready",
  onRetryAi,
}: Props) {
  const preparing = aiStatus === "waking";
  const [lines, setLines] = useState<Line[]>([]);
  const [menuOpen, setMenuOpen] = useState(false);
  const [draft, setDraft] = useState("");
  const [waiting, setWaiting] = useState(false);
  const [cancelled, setCancelled] = useState<Set<string>>(new Set());
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!loadHistory) return;
    loadHistory().then(
      (messages) =>
        setLines(
          messages.map((m) => ({
            from: m.role === "USER" ? "user" : "assistant",
            text: m.content,
            at: new Date(m.createdAt),
          })),
        ),
      () => undefined,
    );
  }, [loadHistory]);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [lines, waiting]);

  // Close the header menu with Escape or a click anywhere else.
  const menuRef = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setMenuOpen(false);
    };
    const onDown = (e: PointerEvent) => {
      if (!menuRef.current?.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onDown);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onDown);
    };
  }, [menuOpen]);

  async function deliver(message: string | null, selection: ChatOption | null) {
    if (waiting) return;
    setLines((l) => [...l, { from: "user", text: message ?? selection?.label ?? "", at: new Date() }]);
    setWaiting(true);
    try {
      const r = await send(message, selection?.id ?? null);
      setLines((l) => [
        ...l,
        { from: "assistant", text: r.reply, at: new Date(), options: r.options, actions: r.actions },
      ]);
    } catch (e) {
      const status = (e as { status?: number }).status;
      const text =
        status === 429
          ? "Mandaste muchos mensajes seguidos. Esperá un minuto y probá de nuevo."
          : status === 404
            ? "No encontramos este local."
            : status === 403
              ? "No pudimos verificar que no seas un robot. Recargá la página."
              : "No pudimos enviar tu mensaje. Probá de nuevo.";
      setLines((l) => [...l, { from: "assistant", text, at: new Date() }]);
    } finally {
      setWaiting(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const message = draft.trim();
    if (!message || preparing) return;
    setDraft("");
    void deliver(message, null);
  }

  async function cancelBooking(appointmentId: string) {
    if (!cancel) return;
    const text = await cancel(appointmentId).catch(() => "No se pudo cancelar. Probá de nuevo.");
    setCancelled((s) => new Set(s).add(appointmentId));
    setLines((l) => [...l, { from: "assistant", text, at: new Date() }]);
  }

  const lastIndex = lines.length - 1;
  return (
    <div className="wa-chat">
      <header className="wa-head">
        <img className="wa-avatar" src="/brand/avatar-bot.png" alt="" />
        <div>
          <strong>{title}</strong>
          <small>
            {waiting
              ? "escribiendo…"
              : preparing
                ? "conectando…"
                : aiStatus === "unavailable"
                  ? "asistente no disponible"
                  : "en línea"}
          </small>
        </div>
        <span className="wa-head-actions">
          {onRestart && (
            <span className="wa-menu" ref={menuRef}>
              <button
                type="button"
                className="wa-icon"
                aria-label="Más opciones"
                aria-haspopup="menu"
                aria-expanded={menuOpen}
                onClick={() => setMenuOpen((o) => !o)}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <circle cx="12" cy="5" r="1.8" />
                  <circle cx="12" cy="12" r="1.8" />
                  <circle cx="12" cy="19" r="1.8" />
                </svg>
              </button>
              {menuOpen && (
                <span className="wa-menu-list" role="menu">
                  <button
                    type="button"
                    role="menuitem"
                    disabled={waiting}
                    onClick={() => {
                      setMenuOpen(false);
                      onRestart();
                    }}
                  >
                    Nueva conversación
                  </button>
                </span>
              )}
            </span>
          )}
          {onClose && (
            <button type="button" className="wa-icon" aria-label="Cerrar chat" onClick={onClose}>
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M6 6l12 12M18 6 6 18" />
              </svg>
            </button>
          )}
        </span>
      </header>

      <div className="wa-body" aria-live="polite">
        {lines.length === 0 && aiStatus === "ready" && (
          <p className="wa-notice">{notice ?? `Escribile a ${title} para sacar un turno o hacer una consulta.`}</p>
        )}
        {preparing && (
          <p className="wa-notice" role="status">
            Estamos iniciando el asistente. Puede tardar hasta un minuto; mientras tanto podés ir escribiendo.
          </p>
        )}
        {aiStatus === "unavailable" && (
          <div className="wa-notice" role="status">
            <p>El asistente no está disponible en este momento. Podés escribir igual y te atiende el menú de opciones.</p>
            {onRetryAi && (
              <div className="wa-buttons">
                <button type="button" disabled={waiting} onClick={onRetryAi}>
                  Reintentar conexión
                </button>
              </div>
            )}
          </div>
        )}
        {lines.map((line, i) => (
          <div key={i} className="wa-row-group">
            {(i === 0 || line.at.toDateString() !== lines[i - 1].at.toDateString()) && (
              <p className="wa-day">{dayLabel(line.at)}</p>
            )}
            <div className={`wa-msg ${line.from}`}>
              <p>{line.text}</p>
              <span className="wa-meta">
                {time(line.at)}
                {line.from === "user" && <Ticks />}
              </span>
            </div>
            {i === lastIndex && line.options && line.options.length > 0 && (
              <div className="wa-buttons">
                {line.options.map((o) => (
                  <button key={o.id} type="button" disabled={waiting} onClick={() => void deliver(null, o)}>
                    {o.label}
                  </button>
                ))}
              </div>
            )}
            {line.actions
              ?.filter((a) => a.type === "booking_created" && a.appointmentId && cancel)
              .map((a) => (
                <div key={a.appointmentId} className="wa-buttons">
                  <button
                    type="button"
                    disabled={cancelled.has(a.appointmentId!)}
                    onClick={() => void cancelBooking(a.appointmentId!)}
                  >
                    {cancelled.has(a.appointmentId!) ? "Turno cancelado" : "Cancelar este turno"}
                  </button>
                </div>
              ))}
          </div>
        ))}
        <div ref={endRef} />
      </div>

      <form className="wa-input" onSubmit={submit}>
        <input
          aria-label="Mensaje"
          value={draft}
          maxLength={1000}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
        />
        <button type="submit" className="wa-send" aria-label="Enviar" disabled={disabled || preparing || waiting || !draft.trim()}>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M3.4 20.4 21 12 3.4 3.6 3.4 10.1 15 12 3.4 13.9z" />
          </svg>
        </button>
      </form>
    </div>
  );
}
