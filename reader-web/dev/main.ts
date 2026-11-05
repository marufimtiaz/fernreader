import type { FromWeb, ToWeb, TocItem } from '../src/bridge/protocol'
import { THEMES, type WebSettings } from '../src/reader/styles'
import { FakeHost, type HostStore } from './host'

declare global { interface Window { __devHost?: (json: string) => void } }

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T
const frame = $<HTMLIFrameElement>('reader')
const phone = $('phone')
const store: HostStore = { get: k => localStorage.getItem(`dev.${k}`), set: (k, v) => localStorage.setItem(`dev.${k}`, v) }

const LOG_LIMIT = 200
const QUIET: ReadonlySet<FromWeb['type']> = new Set(['pageCounts'])

function log(direction: string, m: { type: string }) {
  if (QUIET.has(m.type as FromWeb['type'])) return
  const row = document.createElement('div')
  row.textContent = `${direction} ${JSON.stringify(m).slice(0, 240)}`
  const box = $('log')
  box.prepend(row)
  while (box.childElementCount > LOG_LIMIT) box.lastElementChild!.remove()
}

const toWeb = (m: ToWeb) => { log('→', m); (frame.contentWindow as any).__reader.receive(JSON.stringify(m)) }

function fromWeb(m: FromWeb) {
  log('←', m)
  switch (m.type) {
    case 'ready': $('status').textContent = `engine ${m.engine}`; break
    case 'opened': showOpened(m); break
    case 'relocate':
      $('where').textContent = m.page ? `section ${m.sectionIndex + 1}, page ${m.page} of ${m.pages}` : `section ${m.sectionIndex + 1}`
      $<HTMLInputElement>('fraction').value = String(m.fraction)
      break
    case 'painted':
      void fetch('/__dev/painted', { method: 'POST' }).then(r => r.json()).then(e => {
        if (e) $('edit').textContent = `last edit: ${e.ms} ms to screen (${e.file})`
      })
      break
    case 'history': $<HTMLButtonElement>('back').disabled = m.depth === 0; break
    case 'error': $('status').textContent = `${m.kind}: ${m.message}`; $('where').textContent = ''; break
  }
  window.focus() // a tap in the book takes the focus into its frame, where the arrow keys would not reach this page
}

function showOpened(m: Extract<FromWeb, { type: 'opened' }>) {
  $('status').textContent = `${m.title} (${m.authors.join(', ') || 'no author'}), ${m.sections} sections, ${m.dir}${m.fixedLayout ? ', fixed layout' : ''}`
  $('toc').replaceChildren(...m.toc.map((t: TocItem) => {
    const o = document.createElement('option')
    o.value = t.href
    o.textContent = `${'  '.repeat(t.depth)}${t.label}`
    o.disabled = t.missing
    return o
  }))
}

const host = new FakeHost(toWeb, fromWeb, store)
window.__devHost = json => host.fromWeb(json)

const paintFrame = () => { phone.style.background = THEMES[host.settings.theme].bg }

function bindSetting<K extends keyof WebSettings>(id: string, key: K, parse: (v: string) => WebSettings[K]) {
  const el = $<HTMLInputElement | HTMLSelectElement>(id)
  el.value = String(host.settings[key])
  el.addEventListener('input', () => {
    host.changeSettings({ [key]: parse(el.value) } as Partial<WebSettings>)
    paintFrame()
    showSettings()
  })
}

function showSettings() {
  $('fontScaleValue').textContent = `${Math.round(host.settings.fontScale * 100)} %`
  $('marginValue').textContent = `${host.settings.marginPercent} %`
  $('lineHeightValue').textContent = host.settings.lineHeight.toFixed(1)
}

bindSetting('flow', 'flow', v => v as WebSettings['flow'])
bindSetting('chapters', 'chapters', v => v as WebSettings['chapters'])
bindSetting('fontScale', 'fontScale', Number)
bindSetting('marginPercent', 'marginPercent', Number)
bindSetting('lineHeight', 'lineHeight', Number)
bindSetting('fontFamily', 'fontFamily', v => v)
bindSetting('align', 'align', v => v as WebSettings['align'])
bindSetting('theme', 'theme', v => v as WebSettings['theme'])
bindSetting('pageAnimation', 'pageAnimation', v => v as WebSettings['pageAnimation'])
paintFrame()
showSettings()

const size = $<HTMLSelectElement>('size')
const applySize = () => {
  const [w, h] = size.value.split('x')
  phone.style.width = w ? `${w}px` : '100%'
  phone.style.height = h ? `${h}px` : '100%'
  store.set('size', size.value)
}
size.value = store.get('size') ?? size.value
size.addEventListener('input', applySize)
applySize()

$('prev').addEventListener('click', () => host.command({ v: 1, type: 'prev' }))
$('next').addEventListener('click', () => host.command({ v: 1, type: 'next' }))
$('back').addEventListener('click', () => host.command({ v: 1, type: 'back' }))
$('start').addEventListener('click', () => { host.forgetPosition(); if (host.book) host.openBook(host.book) })
$('toc').addEventListener('input', e => host.command({ v: 1, type: 'goTo', target: (e.target as HTMLSelectElement).value }))
$<HTMLInputElement>('fraction').addEventListener('change', e => host.command({ v: 1, type: 'goToFraction', fraction: Number((e.target as HTMLInputElement).value) }))
window.addEventListener('keydown', e => {
  if (e.target instanceof HTMLSelectElement || e.target instanceof HTMLInputElement) return
  if (e.key === 'ArrowRight') host.command({ v: 1, type: 'next' })
  if (e.key === 'ArrowLeft') host.command({ v: 1, type: 'prev' })
})

const picker = $<HTMLSelectElement>('book')
picker.addEventListener('input', () => { store.set('book', picker.value); host.openBook(picker.value) })

const DEFAULT_BOOK = 'epub3-samples~moby-dick.epub'

async function start() {
  const { corpus, books } = await (await fetch('/__dev/books')).json() as { corpus: string; books: { id: string; folder: string; name: string }[] }
  if (!books.length) { $('status').textContent = `No books in ${corpus}: run tools/corpus.sh, or set CORPUS_DIR.`; return }
  const groups = new Map<string, HTMLOptGroupElement>()
  for (const b of books) {
    let g = groups.get(b.folder)
    if (!g) { g = document.createElement('optgroup'); g.label = b.folder; groups.set(b.folder, g); picker.append(g) }
    g.append(new Option(b.name, b.id))
  }
  const wanted = new URLSearchParams(location.search).get('book') ?? store.get('book') ?? DEFAULT_BOOK
  const book = books.find(b => b.id === wanted)?.id ?? books[0]!.id
  picker.value = book
  host.book = book
  frame.src = '/src/index.html' // only now: the page's `ready` opens host.book
}

void start()
