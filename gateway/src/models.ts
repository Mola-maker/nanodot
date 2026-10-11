export interface ModelSpec { id: string; inputModalities: readonly string[]; outputModalities: readonly string[] }
export const QWEN = '@cf/qwen/qwen3-30b-a3b-fp8';
export const GEMMA = '@cf/google/gemma-4-26b-a4b-it';
export const WHISPER = '@cf/openai/whisper-large-v3-turbo';
export function listModels(ttsVerified: boolean): Response {
  const models: ModelSpec[] = [
    { id: QWEN, inputModalities: ['text'], outputModalities: ['text'] },
    { id: GEMMA, inputModalities: ['text', 'image'], outputModalities: ['text'] },
    { id: WHISPER, inputModalities: ['audio'], outputModalities: ['text'] },
  ];
  if (ttsVerified) models.push({ id: '@cf/myshell-ai/melotts', inputModalities: ['text'], outputModalities: ['audio'] });
  return Response.json({ object: 'list', data: models.map(m => ({ id: m.id, name: m.id.split('/').at(-1), architecture: { input_modalities: m.inputModalities, output_modalities: m.outputModalities } })) }, { headers: { 'Cache-Control': 'no-store' } });
}
