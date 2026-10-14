import fixture from './fixtures/models.json';
import { expect, it } from 'vitest';
import { credentials, dispatch, gatewayEnv } from './helpers';
it('publishes allowlisted modalities without unverified TTS', async () => {
 const { keys, secret } = await credentials();
 const response = await dispatch(new Request('https://example.com/v1/models', { headers: { Authorization: `Bearer ${keys[0]}` } }), gatewayEnv(secret));
 expect(response.status).toBe(200);
 expect(await response.json()).toEqual(fixture);

});
