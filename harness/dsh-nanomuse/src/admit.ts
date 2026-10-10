/**
 * Routes the preset mounts on the harness's web server, admitted the way the
 * harness admits its own `/api`.
 *
 * `webServer.register` dispatches a request to its handler as it came in: the
 * Host/Origin fence (against DNS rebinding and a web page poking the loopback
 * port) and the browser session the launch token minted are applied by the
 * `connection` service, and only to the routes that ask. A route registered
 * raw answers any local process and any page open in any browser — for the
 * account's routes and the connectors' that is a sign-out, a connection to a
 * stranger's server or a spent allowance away. So every Nanodot route goes
 * through here, and a request the harness would turn away from `/api` is
 * turned away from `/nanomuse/*` with the same status.
 */
import type { Context } from '@deepseek-ai/cordis'
import type { IncomingMessage, ServerResponse } from 'node:http'

export type RouteHandler = (req: IncomingMessage, res: ServerResponse) => void | Promise<void>

/** The slice of `@deepseek-ai/dsh-client-connection`'s host handle this needs. */
interface Admission {
  requestRejection(request: { readonly headers: IncomingMessage['headers'] }): 401 | 403 | undefined
}

/** Mount `handler` under `path` once the web server and the connection service are both up. */
export function mountGuarded(ctx: Context, path: string, handler: RouteHandler, label: string): void {
  ctx.inject(['webServer', 'connection' as never], (ctx) => {
    const connection = (ctx as unknown as { connection: Admission }).connection
    const guarded: RouteHandler = async (req, res) => {
      const rejection = connection.requestRejection(req)
      if (rejection !== undefined) {
        res.writeHead(rejection, { 'content-type': 'text/plain' }).end(rejection === 401 ? 'unauthorized' : 'forbidden')
        return
      }
      await handler(req, res)
    }
    ctx.effect(() => ctx.webServer.register({ kind: 'prefix', path, handler: guarded }), label)
  })
}
