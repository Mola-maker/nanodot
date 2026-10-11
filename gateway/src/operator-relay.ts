// Local-only transport to the deployed private service, using Wrangler remote bindings.
export default {
  async fetch(request: Request, env: { OPERATOR: Fetcher }): Promise<Response> {
    const url = new URL(request.url);
    if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || request.headers.has('Origin')) return new Response(null, { status: 403 });
    if (!/^Bearer [A-Za-z0-9._~+/-]+=*$/i.test(request.headers.get('Authorization') ?? '')) return new Response(null, { status: 401 });
    if (request.method !== 'POST' || !['/v1/audio/speech', '/quota-proof'].includes(url.pathname)) return new Response(null, { status: 404 });
    // Dedicated Bearer travels inside the service-binding invocation only.
    return env.OPERATOR.fetch(new Request(`https://private-operator.invalid${url.pathname}`, request));
  },
};
