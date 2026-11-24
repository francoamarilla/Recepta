import { afterEach, describe, expect, it, vi } from "vitest";
import { createAiWaker } from "./wakeAi";

const URL = "https://ai.example/health";
const ok = () => new Response('{"status":"ok"}', { status: 200 });

afterEach(() => vi.unstubAllGlobals());

// fetch that settles when the test says so
function controlledFetch() {
  const calls: { resolve: (r: Response) => void; reject: (e: unknown) => void }[] = [];
  const fetch = vi.fn(() => new Promise<Response>((resolve, reject) => calls.push({ resolve, reject })));
  vi.stubGlobal("fetch", fetch);
  return { fetch, calls };
}

describe("createAiWaker", () => {
  it("is ready without a wake URL and never calls fetch", async () => {
    const { fetch } = controlledFetch();
    const w = createAiWaker(undefined);
    expect(w.status()).toBe("ready");
    expect(await w.ensureAwake()).toBe(true);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("starts as waking, shares one request between callers and is ready only on 200 ok", async () => {
    const { fetch, calls } = controlledFetch();
    const w = createAiWaker(URL);
    expect(w.status()).toBe("waking");
    const a = w.ensureAwake();
    const b = w.ensureAwake();
    expect(fetch).toHaveBeenCalledTimes(1);
    calls[0].resolve(ok());
    expect(await a).toBe(true);
    expect(await b).toBe(true);
    expect(w.status()).toBe("ready");
  });

  it.each([
    ["a 429 from the platform", () => new Response("Too Many Requests", { status: 429 })],
    ["a 200 that is not the health body", () => new Response("<html>", { status: 200 })],
  ])("is not ready on %s", async (_, response) => {
    const { calls } = controlledFetch();
    const w = createAiWaker(URL);
    const p = w.ensureAwake();
    calls[0].resolve(response());
    expect(await p).toBe(false);
    expect(w.status()).toBe("unavailable");
  });

  it("is not ready when the request fails (CORS, network, timeout)", async () => {
    const { calls } = controlledFetch();
    const w = createAiWaker(URL);
    const p = w.ensureAwake();
    calls[0].reject(new TypeError("Failed to fetch"));
    expect(await p).toBe(false);
    expect(w.status()).toBe("unavailable");
  });

  it("does not check again within the window, and does after it", async () => {
    let t = 0;
    const { fetch, calls } = controlledFetch();
    const w = createAiWaker(URL, { rewakeMs: 1000, now: () => t });
    const first = w.ensureAwake();
    calls[0].resolve(ok());
    await first;
    t = 999;
    expect(await w.ensureAwake()).toBe(true);
    expect(fetch).toHaveBeenCalledTimes(1);
    t = 1000;
    const second = w.ensureAwake();
    expect(fetch).toHaveBeenCalledTimes(2);
    calls[1].resolve(ok());
    expect(await second).toBe(true);
  });

  it("never retries by itself; after a failure sends go straight through, and retry() checks once", async () => {
    let t = 0;
    const { fetch, calls } = controlledFetch();
    const w = createAiWaker(URL, { rewakeMs: 1000, now: () => t });
    const p = w.ensureAwake();
    calls[0].reject(new DOMException("timeout", "TimeoutError"));
    expect(await p).toBe(false);
    expect(await w.ensureAwake()).toBe(false); // next send: no wait, no request
    expect(fetch).toHaveBeenCalledTimes(1);

    const r1 = w.retry(); // visitor clicks "Reintentar"
    const r2 = w.retry(); // double click: same request
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(w.status()).toBe("waking");
    calls[1].resolve(ok());
    expect(await r1).toBe(true);
    expect(await r2).toBe(true);
    expect(w.status()).toBe("ready");
  });

  // the abort itself is the platform's AbortSignal.timeout; a timed-out fetch
  // rejects, which the test above covers
  it("sends a timed, uncached, readable (cors) request", async () => {
    const { fetch, calls } = controlledFetch();
    const p = createAiWaker(URL, { timeoutMs: 5000 }).ensureAwake();
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(URL);
    expect(init.mode).toBeUndefined(); // default "cors": the status is readable
    expect(init.cache).toBe("no-store");
    expect(init.signal).toBeInstanceOf(AbortSignal);
    calls[0].resolve(ok());
    expect(await p).toBe(true);
  });
});
