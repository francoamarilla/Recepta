import { useEffect, useSyncExternalStore } from "react";

// On Render's free plan a sleeping ai-service answers the backend with a 429
// and stays asleep, so the first message fell back to the button bot. A browser
// request does wake it: Render holds it until the service is up (~20-30 s cold).
// The service is only "ready" once /health answered 200 {"status":"ok"} (it
// sends Access-Control-Allow-Origin so the browser can read that).
//
// One request in flight at most, no automatic retries: a failure or timeout
// leaves the chat usable (the backend's button-bot fallback answers) and the
// visitor can retry by hand. While awake, Render keeps the service up for
// 15 min after the last request, so before a message a new check is only sent
// once the last one is older than REWAKE_MS. A failed check counts too: after
// a timeout the next messages go straight through instead of each waiting
// another minute.
export const WAKE_TIMEOUT_MS = 60_000;
export const REWAKE_MS = 10 * 60_000;

export type AiStatus = "waking" | "ready" | "unavailable";

export function createAiWaker(
  url: string | undefined,
  { timeoutMs = WAKE_TIMEOUT_MS, rewakeMs = REWAKE_MS, now = Date.now } = {},
) {
  let lastCheck = -Infinity;
  let pending: Promise<boolean> | null = null;
  // "waking" until the first check settles: whoever reads the status starts it.
  let status: AiStatus = url ? "waking" : "ready";
  const listeners = new Set<() => void>();
  const set = (s: AiStatus) => {
    status = s;
    listeners.forEach((l) => l());
  };

  function check(): Promise<boolean> {
    if (pending) return pending;
    set("waking");
    pending = fetch(url!, { cache: "no-store", signal: AbortSignal.timeout(timeoutMs) })
      .then(async (res) => res.ok && (await res.json())?.status === "ok")
      .catch(() => false)
      .then((ok) => {
        set(ok ? "ready" : "unavailable");
        return ok;
      })
      .finally(() => {
        lastCheck = now();
        pending = null;
      });
    return pending;
  }

  // Resolves true when the service is verified up (or there is nothing to
  // wake), false otherwise. Never rejects.
  function ensureAwake(): Promise<boolean> {
    if (!url) return Promise.resolve(true);
    if (pending) return pending;
    if (now() - lastCheck < rewakeMs) return Promise.resolve(status === "ready");
    return check();
  }

  // The visitor's "Reintentar": one more check right now, never a loop.
  function retry(): Promise<boolean> {
    return url ? check() : Promise.resolve(true);
  }

  function subscribe(listener: () => void) {
    listeners.add(listener);
    return () => void listeners.delete(listener);
  }

  return { ensureAwake, retry, subscribe, status: () => status };
}

// One waker per page, shared by the public chat and the dashboard launcher.
const waker = createAiWaker(import.meta.env.VITE_AI_WAKE_URL as string | undefined);
export const ensureAiAwake = waker.ensureAwake;
export const retryAi = waker.retry;

// Starts the check on mount and follows its status.
export function useAiStatus(): AiStatus {
  useEffect(() => void waker.ensureAwake(), []);
  return useSyncExternalStore(waker.subscribe, waker.status);
}
