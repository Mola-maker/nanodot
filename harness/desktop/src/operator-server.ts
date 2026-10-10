import { randomBytes } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { Operator, OperatorError, type OperatorAction, type ScreenshotRequest } from "./operator";

/**
 * The operator on loopback for the Python runtime this app starts. JSON both ways, a bearer
 * token made per launch (the runtime gets it in its environment as NANOMUSE_OPERATOR_TOKEN
 * next to NANOMUSE_OPERATOR_URL), a random port on 127.0.0.1, bodies capped at 2 MB. Three
 * routes, and nothing a browser could use by accident (no CORS, no GET with effects):
 *
 *   GET  /info        → { available, reason, platform, display: { width, height, scaleFactor, logical }, helper? }
 *                     helper (macOS): { present, running, pid?, version?, capture?, reason } — "Nanodot Computer Use"
 *   POST /screenshot  { width?, height?, format?: "png"|"jpeg", quality?, max_pixels? }
 *                     → { base64, mime, width, height, screen: { width, height }, scaleFactor, display: { id, bounds } }
 *                     width×height is the picture asked for; without them the screen's size, capped at
 *                     max_pixels (default 2 Mpx, 0 = uncapped) with the aspect kept
 *   POST /execute     { action, x?, y?, x2?, y2?, dy?, text?, submit?, clear?, keys?, seconds? }
 *                     → { ok: true, note } — coordinates in the operator's screen pixels
 *   GET  /windows     → { windows: [{ id, pid, app, bundle_id, title, bounds: [x, y, w, h], layer, on_screen }] }
 *                     macOS with the helper only: the windows on screen for the runtime's window mode (503 elsewhere)
 *   POST /window      { id, max_pixels?, format?, quality? }
 *                     → { base64, mime, width, height, window: { id, x, y, width, height }, scale }
 *                     one window's own pixels and its frame in points; 404 when the window is gone
 *
 * Errors are 4xx/5xx with { error }. The contract is mirrored in nanomuse/computer/operator.py.
 */

const MAX_BODY = 2 * 1024 * 1024;

export interface OperatorServer {
  url: string;
  token: string;
  close(): Promise<void>;
}

function send(res: ServerResponse, status: number, body: unknown): void {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Content-Length": String(data.length), "Cache-Control": "no-store" });
  res.end(data);
}

function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(new OperatorError("the request body is over 2 MB", 413));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      const text = Buffer.concat(chunks).toString("utf8").trim();
      if (!text) return resolve({});
      try {
        const parsed: unknown = JSON.parse(text);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return reject(new OperatorError("the body must be a JSON object"));
        resolve(parsed as Record<string, unknown>);
      } catch {
        reject(new OperatorError("the body is not JSON"));
      }
    });
    req.on("error", (exc) => reject(new OperatorError(String(exc.message), 400)));
  });
}

export function startOperatorServer(operator: Operator, log: (line: string) => void = () => undefined): Promise<OperatorServer> {
  const token = randomBytes(24).toString("hex");
  const authorised = (req: IncomingMessage): boolean => {
    const header = req.headers.authorization ?? "";
    return header === `Bearer ${token}`;
  };
  const handle = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    const path = (req.url ?? "/").split("?")[0] ?? "/";
    if (!authorised(req)) return send(res, 401, { error: "a bearer token is required" });
    if (req.method === "GET" && path === "/info") return send(res, 200, operator.info());
    if (req.method === "POST" && path === "/screenshot") {
      const body = await readJson(req);
      const shot = await operator.screenshot(body as ScreenshotRequest);
      return send(res, 200, shot);
    }
    if (req.method === "POST" && path === "/execute") {
      const body = await readJson(req);
      if (typeof body.action !== "string" || !body.action) throw new OperatorError("`action` is required");
      const availability = operator.availability();
      if (!availability.available) throw new OperatorError(availability.reason, 503);
      return send(res, 200, await operator.execute(body as unknown as OperatorAction));
    }
    if (req.method === "GET" && path === "/windows") return send(res, 200, { windows: await operator.windows() });
    if (req.method === "POST" && path === "/window") {
      const body = await readJson(req);
      if (typeof body.id !== "number") throw new OperatorError("`id` is required (a window id from /windows)");
      return send(res, 200, await operator.windowShot(body as { id: number; max_pixels?: number; format?: "png" | "jpeg"; quality?: number }));
    }
    send(res, 404, { error: `no route ${req.method ?? ""} ${path}` });
  };
  const server: Server = createServer((req, res) => {
    handle(req, res).catch((exc: unknown) => {
      const status = exc instanceof OperatorError ? exc.status : 500;
      const message = exc instanceof Error ? exc.message : String(exc);
      // 5xx and the permission refusal (403, macOS without Screen Recording) go to desktop.log; the client's own mistakes (4xx) do not
      if (status >= 500 || status === 403) log(`operator server: ${req.method} ${req.url}: ${status} ${message}`);
      if (!res.headersSent) send(res, status, { error: message });
      else res.end();
    });
  });
  server.keepAliveTimeout = 65_000;
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      const url = `http://127.0.0.1:${port}`;
      log(`operator server: ${url}`);
      resolve({
        url,
        token,
        close: () =>
          new Promise<void>((done) => {
            server.closeAllConnections?.();
            server.close(() => done());
          }),
      });
    });
  });
}
