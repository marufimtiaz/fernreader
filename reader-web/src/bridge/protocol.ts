export const PROTOCOL_VERSION = 1

export type TocItem = { label: string; href: string; depth: number; missing: boolean }

export type ToWeb =
  | { v: 1; type: 'ping'; id: number }
  | { v: 1; type: 'open'; sessionId: string; position: string | null; texts: { missingPart: string } }
  | { v: 1; type: 'close' }
  | { v: 1; type: 'next' }
  | { v: 1; type: 'prev' }
  | { v: 1; type: 'goTo'; target: string }

export const TO_WEB_TYPES: ReadonlyArray<ToWeb['type']> = ['ping', 'open', 'close', 'next', 'prev', 'goTo']

export type FromWeb =
  | { v: 1; type: 'ready'; engine: string }
  | { v: 1; type: 'pong'; id: number }
  | { v: 1; type: 'error'; sessionId: string | null; kind: string; message: string }
  | { v: 1; type: 'opened'; sessionId: string; identifier: string | null; title: string; authors: string[];
      dir: 'ltr' | 'rtl'; fixedLayout: boolean; sections: number; toc: TocItem[] }
  | { v: 1; type: 'relocate'; sessionId: string; cfi: string; fraction: number; sectionIndex: number; tocHref: string | null }
  | { v: 1; type: 'painted'; sessionId: string }
  | { v: 1; type: 'tap'; sessionId: string; x: number; y: number }
