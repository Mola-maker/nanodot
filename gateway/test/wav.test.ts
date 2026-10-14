import { expect, it } from 'vitest';
import { parseWav } from '../src/wav';
import { RequestError } from '../src/errors';
import { wav } from './helpers';
it('parses complete 16k mono PCM16 WAV and exact thirty second boundary', () => {
  expect(parseWav(wav())).toEqual({sampleRate:16000,channels:1,durationSeconds:1});
  expect(parseWav(wav(30)).durationSeconds).toBe(30);
});
it('rejects bad RIFF, empty data, truncation, unsupported PCM and duration', () => {
  const bad = wav(); bad[0] = 0;
  const stereo = wav(); new DataView(stereo.buffer).setUint16(22,2,true);
  const bits = wav(); new DataView(bits.buffer).setUint16(34,8,true);
  const rate = wav(); new DataView(rate.buffer).setUint32(28,0,true);
  for (const input of [new Uint8Array(),bad,wav(0),wav().slice(0,-1),stereo,bits,rate,wav(31),wav(1,8000)]) {
    expect(() => parseWav(input)).toThrowError(RequestError);
    try { parseWav(input); } catch (e) { expect((e as RequestError).status).toBe(400); }
  }
});
it('rejects over two MiB before inspecting its header', () => {
  try { parseWav(new Uint8Array(2*1024*1024+1)); throw new Error('accepted'); }
  catch(e) { expect(e).toBeInstanceOf(RequestError); expect((e as RequestError).status).toBe(413); }
});
