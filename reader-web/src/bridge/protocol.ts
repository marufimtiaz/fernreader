import type { WebSettings } from '../reader/styles'

export const PROTOCOL_VERSION = 1

export type TocItem = { label: string; href: string; depth: number; missing: boolean }

export type ToWeb =
  | { v: 1; type: 'ping'; id: number }
  | { v: 1; type: 'open'; sessionId: string; position: string | null; texts: { missingPart: string }; settings?: WebSettings }
  | { v: 1; type: 'close' }
  | { v: 1; type: 'next' }
  | { v: 1; type: 'prev' }
  | { v: 1; type: 'goTo'; target: string; id?: number }
  | { v: 1; type: 'setSettings'; settings: WebSettings }
  | { v: 1; type: 'goToFraction'; fraction: number }

export const TO_WEB_TYPES: ReadonlyArray<ToWeb['type']> = ['ping', 'open', 'close', 'next', 'prev', 'goTo', 'setSettings', 'goToFraction']

export type FromWeb =
  | { v: 1; type: 'ready'; engine: string }
  | { v: 1; type: 'pong'; id: number }
  | { v: 1; type: 'error'; sessionId: string | null; kind: string; message: string }
  | { v: 1; type: 'opened'; sessionId: string; identifier: string | null; title: string; authors: string[];
      dir: 'ltr' | 'rtl'; fixedLayout: boolean; sections: number; toc: TocItem[] }
  | { v: 1; type: 'relocate'; sessionId: string; cfi: string; fraction: number; sectionIndex: number; tocHref: string | null;
      /** Paged: 1-based text page in the section and the section's text pages; 0 and 0 in scrolled mode. */
      page: number; pages: number; sectionFraction: number }
  | { v: 1; type: 'painted'; sessionId: string }
  | { v: 1; type: 'seeked'; sessionId: string; id: number }
  | { v: 1; type: 'pageCounts'; sessionId: string; gen: number; linear?: number[]; counts: [number, number][]; sample: boolean; done: boolean }
  | { v: 1; type: 'tap'; sessionId: string; x: number; y: number }
