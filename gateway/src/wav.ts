import { RequestError } from './errors';
const MAX_WAV_BYTES = 2 * 1024 * 1024;
function invalid(): never { throw new RequestError(400, 'invalid_audio', 'Use a complete, nonempty 16 kHz mono PCM16 WAV of at most 30 seconds.'); }
export function parseWav(data: Uint8Array): {sampleRate:number;channels:number;durationSeconds:number} {
  if (data.byteLength > MAX_WAV_BYTES) throw new RequestError(413, 'audio_too_large', 'WAV exceeds 2 MiB.');
  if (data.byteLength < 44) invalid();
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const tag = (i: number) => String.fromCharCode(...data.subarray(i, i + 4));
  if (tag(0) !== 'RIFF' || tag(8) !== 'WAVE' || view.getUint32(4,true) !== data.byteLength - 8) invalid();
  let offset = 12, formatSeen = false, dataSeen = false, dataSize = 0;
  while (offset + 8 <= data.byteLength) {
    const size = view.getUint32(offset + 4,true), start = offset + 8, end = start + size;
    if (end > data.byteLength) invalid();
    if (tag(offset) === 'fmt ') {
      if (formatSeen || size < 16 || view.getUint16(start,true) !== 1 || view.getUint16(start+2,true) !== 1
        || view.getUint32(start+4,true) !== 16000 || view.getUint32(start+8,true) !== 32000
        || view.getUint16(start+12,true) !== 2 || view.getUint16(start+14,true) !== 16) invalid();
      formatSeen = true;
    } else if (tag(offset) === 'data') {
      if (!formatSeen || dataSeen || !size || size % 2 !== 0) invalid();
      dataSeen = true; dataSize = size;
    }
    offset = end + (size % 2);
  }
  if (!formatSeen || !dataSeen || offset !== data.byteLength || dataSize / 32000 > 30) invalid();
  return {sampleRate:16000,channels:1,durationSeconds:dataSize / 32000};
}
