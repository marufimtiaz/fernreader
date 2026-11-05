import type { ChapterTexts } from '../reader/chapters'
import type { WebSettings } from '../reader/styles'

export const PROTOCOL_VERSION = 1

/** [index]: the section the entry points to, -1 when it resolves to none. */
export type TocItem = { label: string; href: string; depth: number; missing: boolean; index: number }

export type ToWeb =
  | { v: 1; type: 'ping'; id: number }
  | { v: 1; type: 'open'; sessionId: string; position: string | null; texts: { missingPart: string } & Partial<ChapterTexts>; settings?: WebSettings; continued?: number[] }
  | { v: 1; type: 'close' }
  | { v: 1; type: 'next' }
  | { v: 1; type: 'prev' }
  | { v: 1; type: 'goTo'; target: string; id?: number }
  | { v: 1; type: 'setSettings'; settings: WebSettings }
  | { v: 1; type: 'goToFraction'; fraction: number; id?: number }
  /** Search hit (F-050): [needle] is the hit text; the web side finds it near [offset] in the section's text. */
  | { v: 1; type: 'goToText'; sectionIndex: number; offset: number; length: number; needle: string }
  | { v: 1; type: 'clearSearchHighlight' }
  /** F-042: back to where the last followed link was tapped (the app knows the depth from `history`). */
  | { v: 1; type: 'back' }
  /** F-043: close the footnote popup (system Back). */
  | { v: 1; type: 'closeFootnote' }
  /** F-041: the book's bookmarks (virtual CFIs, book order); the web side reports which are on the visible page. */
  | { v: 1; type: 'setBookmarks'; sessionId: string; cfis: string[] }
  /**
   * F-060/F-061: draw a user annotation ([cfi] virtual range CFI, [color] a HIGHLIGHT_COLORS id). Sent again with the
   * same [id] to change it; drawn until the book closes.
   */
  | { v: 1; type: 'addAnnotation'; sessionId: string; id: string; cfi: string; color: string; style: 'highlight' | 'underline' }
  /** F-060: remove the text selection (after a selection menu action). */
  | { v: 1; type: 'clearSelection' }
  /** F-061: the book's annotations (virtual CFIs), replacing the session's set; sent once the first page is painted. */
  | { v: 1; type: 'setAnnotations'; sessionId: string; items: { id: string; cfi: string; color: string; style: 'highlight' | 'underline' }[] }
  /** F-061: the annotation [id] was removed: its drawing goes at once. */
  | { v: 1; type: 'removeAnnotation'; sessionId: string; id: string }
  /** C-083 go to page: jump to text [offset] of section [sectionIndex] (a book page's start; queued like goTo). */
  | { v: 1; type: 'goToOffset'; sectionIndex: number; offset: number }
  /** C-083: where these CFIs are in their sections' text (the lists' book pages); answered by `textOffsets` with [id]. */
  | { v: 1; type: 'textOffsets'; sessionId: string; id: number; cfis: string[] }

export const TO_WEB_TYPES: ReadonlyArray<ToWeb['type']> = ['ping', 'open', 'close', 'next', 'prev', 'goTo', 'setSettings', 'goToFraction', 'goToText', 'clearSearchHighlight', 'back', 'closeFootnote', 'setBookmarks', 'addAnnotation', 'clearSelection', 'setAnnotations', 'removeAnnotation', 'goToOffset', 'textOffsets']

export type FromWeb =
  | { v: 1; type: 'ready'; engine: string }
  | { v: 1; type: 'pong'; id: number }
  | { v: 1; type: 'error'; sessionId: string | null; kind: string; message: string }
  | { v: 1; type: 'opened'; sessionId: string; identifier: string | null; title: string; authors: string[];
      dir: 'ltr' | 'rtl'; fixedLayout: boolean; sections: number; toc: TocItem[]; sizes: number[] }
  | { v: 1; type: 'relocate'; sessionId: string; cfi: string; fraction: number; sectionIndex: number; tocHref: string | null;
      /** Paged: 1-based text page in the section and the section's text pages; 0 and 0 in scrolled mode. */
      page: number; pages: number; sectionFraction: number;
      /** F-041: the `setBookmarks` entries on the visible page (omitted when none). */
      bookmarks?: string[]
      /** C-083: text offsets of the visible range's start and end in the section's text (omitted when unknown). */
      textOffset?: number; textEnd?: number }
  | { v: 1; type: 'painted'; sessionId: string }
  | { v: 1; type: 'seeked'; sessionId: string; id: number }
  | { v: 1; type: 'pageCounts'; sessionId: string; gen: number; linear?: number[]; counts: [number, number][]; sample: boolean; done: boolean }
  | { v: 1; type: 'tap'; sessionId: string; x: number; y: number }
  /** C-097: a gesture from the right edge. [fraction]: the share of the screen under the panel; [open]: on `end`, whether it opens. */
  | { v: 1; type: 'edgeSwipe'; sessionId: string; phase: 'start' | 'move' | 'end'; fraction: number; open: boolean }
  /** F-042: entries in the followed-links history after a push or a pop (0 = Back leaves the book). */
  | { v: 1; type: 'history'; sessionId: string; depth: number }
  /** F-043: the footnote popup opened or closed (a tap outside closes it on the web side). */
  | { v: 1; type: 'footnote'; sessionId: string; open: boolean }
  /** F-044: an external http(s) or mailto link was tapped ([href] normalised; other schemes are never sent). */
  | { v: 1; type: 'externalLink'; sessionId: string; href: string }
  /** F-041: answer to `setBookmarks`: the bookmarks on the page now visible (same strings as sent). */
  | { v: 1; type: 'pageBookmarks'; sessionId: string; cfis: string[] }
  /**
   * F-060: the text selection settled (debounced): [text] exactly Selection.toString(), [cfi] its virtual range CFI,
   * [sectionIndex] its section. Cleared (collapsed, or gone): text '', cfi null, sectionIndex -1.
   */
  | { v: 1; type: 'selection'; sessionId: string; text: string; cfi: string | null; sectionIndex: number }
  /** F-061: a tap landed on the user annotation [id] (not on a link inside it); it is not a `tap`. */
  | { v: 1; type: 'annotationTapped'; sessionId: string; id: string }
  /** C-083: answer to `textOffsets`: [section, offset] per CFI, in order; [-1, -1] for one that does not resolve. */
  | { v: 1; type: 'textOffsets'; sessionId: string; id: number; offsets: [number, number][] }
