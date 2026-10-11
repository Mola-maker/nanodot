// Controlled dsh host seats around the real plugin sidebar. No model or relay is contacted.
import { createElement as h, useState } from '../../../harness/dsh-nanomuse/node_modules/react/index.js';
import { createRoot } from '../../../harness/dsh-nanomuse/node_modules/react-dom/client.js';
import { MuseSidebar } from '../../../harness/dsh-nanomuse/src/client/MuseSidebar.tsx';
import { en } from '../../../harness/dsh-nanomuse/src/client/locales.ts';
import { ensureStyles } from '../../../harness/dsh-nanomuse/src/client/styles.ts';
function Fixture() {
  const [active, selectPanel] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [sessions, setSessions] = useState(0);
  return h('div', { style: { display: 'flex', height: '100vh' } },
    h(MuseSidebar, { collapsed: true, width: 300, t: (key: keyof typeof en) => en[key],
      renderSlot: (seat: string) => h('span', { 'data-seat': seat }),
      startSession: () => { setSessions(n => n + 1); selectPanel(null) }, toggleSidebar: () => {},
      selectPanel, openSettings: () => { selectPanel('settings'); return true }, openProfile: () => {},
      openSearch: () => document.querySelector<HTMLTextAreaElement>('textarea')?.focus(), issuesUrl: '', chatActions: {},
      usePanels: (f: (p: unknown[]) => unknown) => f([{ id: 'terminal', label: 'Terminal' }, { id: 'plugins', label: 'Plugins' }, { id: 'schedules', label: 'Schedules' }]),
      usePanelInfo: (f: (p: unknown) => unknown) => f({ activePanelId: active }), useShortcuts: (f: (p: unknown[]) => unknown) => f([]),
    }),
    h('main', { style: { minWidth: 0, flex: 1 } }, h('output', { 'aria-label': 'Active panel' }, active || 'chat'),
      h('output', { 'aria-label': 'Session count' }, sessions),
      h('textarea', { 'aria-label': 'Host composer fixture', value: draft, onChange: (e: { currentTarget: HTMLTextAreaElement }) => setDraft(e.currentTarget.value) })));
}
ensureStyles();
createRoot(document.getElementById('root')!).render(h(Fixture));
