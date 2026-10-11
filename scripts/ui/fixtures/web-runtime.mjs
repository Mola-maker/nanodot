// Controlled local runtime protocol fixture. No model, relay, credentials or external API.
const profile = { name: 'Dot', color: '#136F63', avatar: 'dragon', description: '' };
export const settings = { version: 'fixture', profile, onboarded: true, llm_ready: true, llm: { provider: 'fixture', model: 'fixture', stream: true }, agent: { language: 'auto', max_steps: 10, workspace: '/fixture' }, sentinel: { mode: 'ask', always_ask_tools: [], always_allow_tools: [], deny_tools: [], egress_allowlist: [] }, sandbox: { mode: 'off', active: false, status: '' }, connectors: { mcp: [] }, phone: { connected: false }, tools: [], memory_enabled: true, skills: { enabled: true, count: 0, yours: 0 }, data_dir: '/fixture', started_at: '' };
export const thread = { id: 'main', title: 'Main chat', events: 0, busy: false, created_at: '', updated_at: '' };
export const hub = { device: { id: 'fixture', name: 'Fixture device', kind: 'web', actions: [] }, state: 'signed_out', remote_control: false, detail: '', connected: false, enabled: false, devices: [], account: { signed_in: false }, remote: { enabled: false, trusted: [] } };
export const snapshot = { version: 'fixture', profile, settings, hub, status: { state: 'idle', detail: '', thread: 'main' }, threads: [thread], pending_approvals: [], goals: [] };
export function response(path) {
  if (path === '/api/state') return snapshot;
  if (path === '/api/settings') return settings;
  if (path === '/api/hub') return hub;
  if (path.includes('/events')) return { thread, events: [], has_more: false };
  if (path === '/api/skills') return { skills: [] };
  if (path === '/api/feed/posts') return { posts: [], instructions: '', routine: { on: false, time: '08:00' } };
  if (path === '/api/upcoming') return { queue: [], reminders: [], check_ins: [] };
  if (path === '/api/calendar') return { events: [], feeds: [] };
  if (path === '/api/ideas') return { items: [], groups: [] };
  if (path === '/api/feed' || path === '/api/goals' || path === '/api/files') return [];
  return {};
}
