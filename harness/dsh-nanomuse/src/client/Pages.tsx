/**
 * The Settings pages Muse has that are not about one service: Permissions (one
 * place that says what the agent may do and where each switch is), Files (where
 * it works, what it may touch) and Dictation (voice input: the harness's own
 * bundle, or the system's). Connectors has a file of its own (Connectors.tsx).
 */
import { createElement as h, Fragment, useEffect, useState, type ReactNode } from 'react'
import { groupGrants, standingGrants, type GrantTier, type StandingGrant } from '../desk.ts'
import { call, type Translate } from './api.ts'
import { bridge, gatedPermissions, type PermissionKind } from './bridge.ts'
import type { Words } from './locales.ts'
import { permissionTitle, usePermissions } from './permissions.ts'
import { settingsBus } from './bus.ts'
import { IconCheck, IconChevronRight, IconDevices, IconFolder, IconHand, IconLink, IconMic, IconShield } from './icons.tsx'
import { useLive, type Live } from './live.ts'
import { DEVICES_PANEL } from './panels.ts'
import { usePrefs } from './prefs.ts'
import { CONNECTORS_SECTION, useConnectors } from './Connectors.tsx'
import { roomsCall } from './rooms.ts'
import { ago } from './ui.tsx'

export const PERMISSIONS_SECTION = 'nanomuse-permissions'
export const FILES_SECTION = 'nanomuse-files'
export const DICTATION_SECTION = 'nanomuse-dictation'
/** The Computer-use page's id (MuseSettings owns it; repeated here to avoid a cycle). */
const COMPUTER_SECTION = 'nanomuse-computer'
const PLUGINS_PANEL = 'plugins'

function Row({ icon, title, sub, right, onClick }: { icon?: ReactNode; title: string; sub?: ReactNode; right?: ReactNode; onClick?: () => void }): ReactNode {
  const body = [
    icon ? h('span', { key: 'i', className: 'nm-row-icon' }, icon) : null,
    h('div', { key: 'm', className: 'nm-row-main' },
      h('span', { className: 'nm-row-title' }, title),
      sub ? h('span', { className: 'nm-row-sub nm-wrap' }, sub) : null),
    right ?? (onClick ? h('span', { key: 'r', className: 'nm-row-chevron' }, h(IconChevronRight, { size: 16 })) : null),
  ]
  return onClick
    ? h('button', { type: 'button', className: 'nm-row nm-row-button', onClick }, ...body)
    : h('div', { className: 'nm-row' }, ...body)
}


// ---- Permissions --------------------------------------------------------------------

export function makePermissionsSection(t: Translate) {
  return function PermissionsSection(): ReactNode {
    const prefs = usePrefs()
    const connectors = useConnectors()
    const live = useLive()
    const gated = gatedPermissions()
    const { states } = usePermissions(['accessibility', 'screen', 'microphone'])
    const word = (kind: PermissionKind) => {
      if (!gated) return t('pmNotGated')
      const state = states[kind]
      return state === 'granted' ? t('pmGranted') : state === 'not-determined' ? t('pmNotAsked') : state ? t('pmDenied') : '…'
    }
    const devices = live.hub.devices.filter((d) => d.kind !== 'web').length
    const approvals = prefs.approvals.slice(0, 8)
    return h('div', { className: 'nm-section' },
      h('p', null, t('pmLead')),
      h('h2', null, t('pmWhat')),
      h('div', { className: 'nm-card' },
        h(Row, { icon: h(IconHand, { size: 18 }), title: t('pmComputer'), sub: `${permissionTitle(t, 'accessibility', gated)}: ${word('accessibility')} · ${permissionTitle(t, 'screen', gated)}: ${word('screen')}`, onClick: () => { settingsBus.openSection?.(COMPUTER_SECTION) } }),
        h(Row, { icon: h(IconFolder, { size: 18 }), title: t('pmFiles'), sub: t('pmFilesSub'), onClick: () => { settingsBus.openSection?.(FILES_SECTION) } }),
        h(Row, { icon: h(IconMic, { size: 18 }), title: t('pmMic'), sub: word('microphone'), onClick: () => { settingsBus.openSection?.(DICTATION_SECTION) } }),
        h(Row, { icon: h(IconLink, { size: 18 }), title: t('pmConnectors'), sub: connectors ? t('pmConnectorsSub', { n: connectors.servers.length }) : '…', onClick: () => { settingsBus.openSection?.(CONNECTORS_SECTION) } }),
        h(Row, { icon: h(IconDevices, { size: 18 }), title: t('pmDevices'), sub: `${t('remoteControl')}: ${live.hub.remoteControl ? t('cnOn') : t('cnOff')} · ${devices ? t('pmDevicesSub', { n: devices }) : t('pmDevicesNone')}`, onClick: () => { settingsBus.openSection?.(DEVICES_PANEL) } })),
      h('h2', null, t('pmHow')),
      h('div', { className: 'nm-card' },
        h(Row, { icon: h(IconShield, { size: 18 }), title: t('pmPreset'), sub: t('pmPresetSub'), onClick: () => { settingsBus.openSection?.('agent-presets') } })),
      h('h2', null, t('sgTitle')),
      h(StandingGrants, { t, live }),
      h('h2', null, t('pmRecent')),
      approvals.length
        ? h('div', { className: 'nm-card' }, approvals.map((a, i) => h(Row, { key: `${a.at}-${i}`, icon: h(IconShield, { size: 18 }), title: a.reason || a.toolName, sub: `${a.outcome === 'allowed' ? t('pfAllowed') : t('pfDenied')} · ${ago(t, a.at)}` })))
        : h('p', null, t('pmRecentNone')))
  }
}

const TIER_WORDS: Record<GrantTier, { title: Words; sub: Words }> = {
  highest: { title: 'sgTierHighest', sub: 'sgTierHighestSub' },
  confirm: { title: 'sgTierConfirm', sub: 'sgTierConfirmSub' },
  notice: { title: 'sgTierNotice', sub: 'sgTierNoticeSub' },
}

/**
 * Every remembered permission on this computer, by the Sentinel's three tiers (the phone's
 * Permissions page groups the same way): the remote-control switch, the devices allowed
 * without asking, the hands' per-app grants. The Computer page and the profile drawer keep
 * their own lists; this one is the complete one. Read from the live snapshot, so a grant
 * given on a card appears at once; one revoke route takes any of them.
 */
function StandingGrants({ t, live }: { t: Translate; live: Live }): ReactNode {
  const [busy, setBusy] = useState<string | undefined>()
  const name = live.profile.name || 'Dot'
  const groups = groupGrants(standingGrants({ grants: live.grants, trusted: live.hub.trusted, remoteControl: live.hub.remoteControl }))
  if (groups.length === 0) return h(Fragment, null, h('p', null, t('sgLead')), h('p', null, t('sgEmpty')))
  const revoke = (id: string) => {
    setBusy(id)
    void call('grants/revoke', { id }).catch(() => undefined).finally(() => setBusy(undefined))
  }
  const words = (g: StandingGrant): { title: string; sub: string; icon: ReactNode } => {
    switch (g.kind) {
      case 'remote_control': return { title: t('sgRemoteControl'), sub: t('sgRemoteControlSub'), icon: h(IconDevices, { size: 18 }) }
      case 'device': return { title: t('sgDevice', { name: g.target }), sub: `${t('sgDeviceSub')} · ${ago(t, g.at)}`, icon: h(IconDevices, { size: 18 }) }
      case 'computer_app': return { title: t('sgHands', { app: g.target }), sub: `${t('sgHandsSub', { name })} · ${ago(t, g.at)}`, icon: h(IconHand, { size: 18 }) }
    }
  }
  return h(Fragment, null,
    h('p', null, t('sgLead')),
    groups.map(({ tier, grants }) => h('div', { key: tier, className: 'nm-card' },
      h('div', { className: 'nm-row' },
        h('span', { className: 'nm-row-icon' }, h(IconShield, { size: 18 })),
        h('div', { className: 'nm-row-main' },
          h('span', { className: 'nm-row-title' }, t(TIER_WORDS[tier].title)),
          h('span', { className: 'nm-row-sub nm-wrap' }, t(TIER_WORDS[tier].sub)))),
      grants.map((g) => {
        const w = words(g)
        return h(Row, { key: g.id, icon: w.icon, title: w.title, sub: w.sub, right: h('button', { type: 'button', className: 'nm-pill nm-pill-ghost nm-pill-sm', disabled: busy !== undefined, onClick: () => revoke(g.id) }, busy === g.id ? t('working') : t('pmRevoke')) })
      }))))
}

// ---- Files --------------------------------------------------------------------------

interface Folders {
  home: string
  library: string
  downloads: string
  state: string
}

export function makeFilesSection(t: Translate) {
  return function FilesSection(): ReactNode {
    const [folders, setFolders] = useState<Folders | undefined>()
    useEffect(() => {
      void roomsCall<Folders>('files').then(setFolders).catch(() => undefined)
    }, [])
    const b = bridge()
    const reveal = (which: keyof Folders) => { void roomsCall('files/reveal', { which }).catch(() => undefined) }
    const folder = (which: keyof Folders, title: string, sub: string) =>
      h(Row, { icon: h(IconFolder, { size: 18 }), title, sub: h(Fragment, null, sub, folders ? h('span', { className: 'nm-path' }, folders[which]) : null), onClick: () => reveal(which) })
    return h('div', { className: 'nm-section' },
      h('p', null, t('fsLead')),
      h('h2', null, t('fsFolders')),
      h('div', { className: 'nm-card' },
        folder('home', t('fsHome'), t('fsHomeSub')),
        folder('library', t('fsLibrary'), t('fsLibrarySub')),
        folder('downloads', t('fsDownloads'), t('fsDownloadsSub')),
        folder('state', t('fsState'), t('fsStateSub'))),
      h('h2', null, t('fsRules')),
      h('div', { className: 'nm-card' },
        h(Row, { icon: h(IconShield, { size: 18 }), title: t('fsRead'), sub: t('fsReadSub') }),
        h(Row, { icon: h(IconShield, { size: 18 }), title: t('fsWrite'), sub: t('fsWriteSub') }),
        h(Row, { icon: h(IconShield, { size: 18 }), title: t('fsSandbox'), sub: t('fsSandboxSub'), onClick: () => { settingsBus.openSection?.('agent-presets') } })),
      b?.platform === 'darwin'
        ? h(Fragment, null,
            h('h2', null, t('fsMacTitle')),
            h('div', { className: 'nm-card' },
              h(Row, { icon: h(IconFolder, { size: 18 }), title: t('fsFullDisk'), sub: t('fsFullDiskSub'), onClick: () => { void b.openPermissionSettings('files') } })))
        : null)
  }
}

// ---- Dictation ----------------------------------------------------------------------

export function makeDictationSection(t: Translate, selectPanel: (id: string) => void) {
  return function DictationSection(): ReactNode {
    const b = bridge()
    const gated = gatedPermissions()
    const perms = usePermissions(['microphone'])
    const mic = perms.states.microphone
    const os = b?.platform === 'darwin' ? t('dcOsMac') : b?.platform === 'win32' ? t('dcOsWin') : t('dcOsOther')
    return h('div', { className: 'nm-section' },
      h('p', null, t('dcLead')),
      h('h2', null, t('dcBuiltIn')),
      h('div', { className: 'nm-card' },
        h(Row, { icon: h(IconMic, { size: 18 }), title: t('dcVoiceInput'), sub: t('dcVoiceInputSub'), onClick: () => selectPanel(PLUGINS_PANEL) }),
        gated
          ? h(Row, { icon: h(IconShield, { size: 18 }), title: t('dcMic'), sub: mic === 'granted' ? t('pmGranted') : mic === 'denied' ? t('pmDenied') : t('pmNotAsked'),
              right: mic === 'granted'
                ? h('span', { className: 'nm-ob-granted', 'aria-label': t('obAllowed') }, h(IconCheck, { size: 16 }))
                : perms.asked('microphone')
                  ? h('button', { type: 'button', className: 'nm-pill nm-pill-sm', onClick: () => perms.settings('microphone') }, t('obOpenSettings'))
                  : h('button', { type: 'button', className: 'nm-pill nm-pill-sm', onClick: () => perms.allow('microphone') }, t('obAllow')) })
          : null),
      h('h2', null, t('dcSystem')),
      h('div', { className: 'nm-card' },
        h(Row, { icon: h(IconMic, { size: 18 }), title: t('dcOs'), sub: os })),
      h('p', { className: 'nm-fine' }, t('dcPrivacy')))
  }
}
