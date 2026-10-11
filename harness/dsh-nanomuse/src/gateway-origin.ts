/** The project gateway is a separate own-key origin; an account token never belongs here. */
export const GATEWAY_ID = 'nanodot-workers'
export const GATEWAY_ORIGIN = 'https://molamaker.si'
export const GATEWAY_BASE = `${GATEWAY_ORIGIN}/v1`

export function isFresh(configLoaded: boolean, hasAccount: boolean, hasProvider: boolean): boolean {
  return configLoaded && !hasAccount && !hasProvider
}

export function gatewayBase(input: string): string {
  const url = new URL(input)
  if (url.origin !== GATEWAY_ORIGIN || url.username || url.password || url.search || url.hash || !['', '/', '/v1', '/v1/'].includes(url.pathname)) {
    throw new Error('The project key belongs to https://molamaker.si/v1')
  }
  return GATEWAY_BASE
}

export function gatewayKeyRef(instanceId: string): string {
  return `NANODOT_GATEWAY_${instanceId.replace(/-/g, '_').toUpperCase()}`
}
