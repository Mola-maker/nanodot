export class RequestError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}
export function error(status: number, code: string, message: string, headers?: HeadersInit): Response {
  return Response.json({ error: { code, message } }, { status, headers: { 'Cache-Control': 'no-store', ...headers } });
}
export function record(value: unknown): value is Record<string, any> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
