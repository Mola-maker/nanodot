#!/usr/bin/env node
// Phase B only. No login, credential creation, subdomain registration or deployment.
import { spawn } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
export async function startRelay({ env = process.env, freePlanConfirmed = false, sessionControlsReviewed = false, fetcher = fetch, spawnProcess = spawn } = {}) {
  if (!freePlanConfirmed || !sessionControlsReviewed) throw Error('Require verified Free plan and reviewed session controls.');
  const token = env.CLOUDFLARE_API_TOKEN, account = env.CLOUDFLARE_ACCOUNT_ID;
  if (!token || !/^[a-f0-9]{32}$/i.test(account ?? '')) throw Error('Explicit private control environment is required; no login or credential discovery.');
  // Wrangler would auto-register a random subdomain on API10007. Read-check first;
  // no fallback PUT/login/upgrade is permitted by this launch path.
  let response;
  try {
    response = await fetcher(`https://api.cloudflare.com/client/v4/accounts/${account}/workers/subdomain`, {
      method: 'GET', headers: { Authorization: `Bearer ${token}` }, redirect: 'error', signal: AbortSignal.timeout(10000),
    });
    const value = await response.json();
    if (!response.ok || value.success !== true || !/^[a-z0-9-]+$/.test(value.result?.subdomain ?? '')) throw Error();
  } catch { throw Error('An existing workers.dev subdomain must be verified; no automatic registration.'); }
  const gateway = fileURLToPath(new URL('../../gateway/', import.meta.url));
  return spawnProcess(process.execPath, [fileURLToPath(new URL('../../gateway/node_modules/wrangler/bin/wrangler.js', import.meta.url)), 'dev', '--config', 'wrangler.operator-relay.jsonc', '--ip', '127.0.0.1', '--port', '8788'], {
    cwd: gateway, env: { ...env, WRANGLER_SEND_METRICS: 'false', WRANGLER_LOG: 'warn' }, stdio: 'inherit',
  });
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const args = new Set(process.argv.slice(2));
  if ([...args].some(arg => !['--free-plan-confirmed', '--session-controls-reviewed'].includes(arg))) { console.error('Use only reviewed session flags; credentials never come from argv.'); process.exitCode = 1; }
  else startRelay({ freePlanConfirmed: args.has('--free-plan-confirmed'), sessionControlsReviewed: args.has('--session-controls-reviewed') })
    .then(child => { child.on('error', () => { console.error('Local relay could not start.'); process.exitCode = 1; }); child.on('exit', code => { process.exitCode = code ?? 1; }); })
    .catch(() => { console.error('Private relay launch refused; review Free/session controls, explicit private environment and existing subdomain readiness.'); process.exitCode = 1; });
}
