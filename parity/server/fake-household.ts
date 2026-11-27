// A fake household server for the parity suite. Both test harnesses — the
// iOS apps' Swift code (parity/ios) and the Android apps' Kotlin code
// (:parity) — talk to it over real HTTP, so what is compared is what each
// app actually puts on the wire and what it makes of the same answers.
//
//   bun parity/server/fake-household.ts            # port 47800, or $PARITY_PORT
//
// Control routes (never reached by the apps):
//   POST /__parity/reset  {routes: Route[]}   new script, empty log
//   GET  /__parity/log                        the requests received since
//
// A Route answers the first request that matches it:
//   { method?: "GET", path: "/api/x" | "/api/x*" | "re:^/api/x/\\d+$",
//     status?: 200, json?: any, text?: string, ndjson?: any[],
//     headers?: {k: v}, times?: n,    // times: answers n requests, then retires
//     query?: {k: v | null} }         // only when these query parameters are so (null: absent)
// A request that matches nothing gets 404 {"error":"parity: no route"} and is
// logged with `unmatched: true`.

type Route = {
  method?: string;
  path: string;
  status?: number;
  json?: unknown;
  text?: string;
  ndjson?: unknown[];
  headers?: Record<string, string>;
  times?: number;
  query?: Record<string, string | null>;
};

type Logged = {
  method: string;
  path: string;
  query: Record<string, string>;
  auth: string | null;
  contentType: string | null;
  body: unknown;
  unmatched?: boolean;
};

let routes: Route[] = [];
let log: Logged[] = [];

function matches(route: Route, method: string, path: string, query: URLSearchParams): boolean {
  if (route.times != null && route.times <= 0) return false;
  if (route.method && route.method.toUpperCase() !== method) return false;
  for (const [k, v] of Object.entries(route.query ?? {})) {
    if (query.get(k) !== v) return false;
  }
  if (route.path.startsWith("re:")) return new RegExp(route.path.slice(3)).test(path);
  if (route.path.endsWith("*")) return path.startsWith(route.path.slice(0, -1));
  return route.path === path;
}

// The body as both harnesses compare it: JSON parsed (key order is not
// behaviour), multipart reduced to its fields and the file's name and size,
// anything else as text.
async function readBody(req: Request, contentType: string | null): Promise<unknown> {
  if (req.method === "GET" || req.method === "HEAD") return null;
  const raw = await req.arrayBuffer();
  if (raw.byteLength === 0) return null;
  if (contentType?.startsWith("multipart/form-data")) {
    const form = await new Response(raw, { headers: { "content-type": req.headers.get("content-type")! } }).formData();
    const out: Record<string, unknown> = {};
    for (const [k, v] of form.entries()) {
      out[k] = typeof v === "string" ? v : { file: v.name, size: v.size, type: v.type || null };
    }
    return { multipart: out };
  }
  const text = new TextDecoder().decode(raw);
  if (contentType?.includes("json")) {
    try { return JSON.parse(text); } catch { return { unparsable: text }; }
  }
  return { text };
}

function answer(route: Route): Response {
  const status = route.status ?? 200;
  const headers = new Headers(route.headers ?? {});
  if (route.ndjson) {
    headers.set("content-type", "application/x-ndjson");
    return new Response(route.ndjson.map((l) => (typeof l === "string" ? l : JSON.stringify(l)) + "\n").join(""), { status, headers });
  }
  if (route.text !== undefined) {
    if (!headers.has("content-type")) headers.set("content-type", "text/plain");
    return new Response(route.text, { status, headers });
  }
  if (status === 204) return new Response(null, { status, headers });
  headers.set("content-type", "application/json");
  return new Response(JSON.stringify(route.json ?? {}), { status, headers });
}

const port = Number(process.env.PARITY_PORT ?? 47800);

Bun.serve({
  port,
  hostname: "127.0.0.1",
  async fetch(req) {
    const url = new URL(req.url);
    if (url.pathname === "/__parity/reset") {
      const body = (await req.json()) as { routes?: Route[] };
      routes = (body.routes ?? []).map((r) => ({ ...r }));
      log = [];
      return Response.json({ ok: true });
    }
    if (url.pathname === "/__parity/log") return Response.json(log);

    // The live sockets (…/ws) reconnect on their own schedule, from whatever
    // store an earlier case left alive: never part of what a case observes.
    if (url.pathname.endsWith("/ws")) return new Response(null, { status: 404 });

    const contentType = req.headers.get("content-type")?.split(";")[0].trim() ?? null;
    const query: Record<string, string> = {};
    for (const k of [...url.searchParams.keys()].sort()) query[k] = url.searchParams.getAll(k).join(",");
    const entry: Logged = {
      method: req.method,
      path: url.pathname,
      query,
      auth: req.headers.get("authorization"),
      contentType: req.method === "GET" ? null : contentType,
      body: await readBody(req, contentType),
    };
    const route = routes.find((r) => matches(r, req.method, url.pathname, url.searchParams));
    if (!route) {
      log.push({ ...entry, unmatched: true });
      return Response.json({ error: "parity: no route" }, { status: 404 });
    }
    if (route.times != null) route.times -= 1;
    log.push(entry);
    return answer(route);
  },
});

console.log(`fake household on http://127.0.0.1:${port}`);
