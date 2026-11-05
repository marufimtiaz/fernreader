// LOCAL PATCH (M9 C-079/C-080, see PATCHES.md): stack mode (scrolled flow) is driven by the viewport: a deliberate
// jump resets the stack and is never "corrected" back (#jumping), layout changes restore the jump target only until the
// user scrolls (#restoring) and otherwise keep the text on screen in place (#holdScreen), and sections are loaded ahead
// of the viewport in both directions, through short sections (#ensureStack).
// LOCAL PATCH (M9 C-085, see PATCHES.md): `chapter-stop` attribute ("Chapters: One at a time" in scroll mode): stack mode
// holds only the current section, so scrolling stops at its end; a control below its last line (above its first line)
// or a deliberate overscroll pull opens the next (previous) section (#edges, pullStep).
// LOCAL PATCH (v1.1 C-094, see PATCHES.md): the page follows the finger. A turn is a surface ({ set(t), finish(), reset() },
// t = progress 0..1): #scrollSurface inside a section, #sectionSurface at a boundary, #chapterSurface in One at a time. A
// bridge turn plays a surface from 0 to 1 (#slide); a drag sets it from the finger (#dragApply) and plays it to either
// end on release (#dragSettle). The next section is only staged: the swap is the last step, so a cancel costs nothing.
// LOCAL PATCH (M9 C-093, see PATCHES.md): in One at a time a clearly horizontal gesture (sideLock, decided on the first
// move past the touch slop) is reported as a `swipe`; the host answers with turnChapter(), which slides the adjacent
// chapter in. Those chapters wait laid out in hidden slots outside the stack (`side` entries).
// LOCAL PATCH (M9 C-071, see PATCHES.md): a turn into a preloaded neighbour section slides (`animated`) instead of
// swapping instantly: #sectionSurface moves the old and the new slot side by side over 300 ms.
// LOCAL PATCH (M6 Task 9, see PATCHES.md): `vertical-pages` attribute: vertical-rl sections paged right-to-left as
// horizontal pages (multicol rows with `column-wrap`) where supported.
// LOCAL PATCH (epub-reader M4, see PATCHES.md): `View` exported; `layout`, `styles`, `busy` getters; neighbour-section
// preloading enabled by the `preload` attribute (ported from the M0 S2 spike):
//   - #cache: index -> { slot, view, ready, loaded }. Every view lives in its own slot div inside #container;
//     the current slot is in flow, cached slots are absolute/hidden (an iframe must never be re-parented,
//     that would reload it).
//   - #preloadIndex(i): creates a hidden view for section i, loads and columnizes it with the current layout
//     and styles, dispatches 'load' and 'create-overlayer' as #display does. A neighbour whose writing mode
//     differs from the current one is dropped (its boundary takes the normal load path).
//   - #turnPage(dir): on the first/last page, if the neighbour is cached, #swapTo() it (hide current slot,
//     show target slot, scroll target to first/last page synchronously in the same task).
//   - #goTo: a cached target is swapped in; otherwise #display as before, with #createView keeping the old view.
//   - #display/#swapTo end with #afterSwap(): evict views farther than one linear section, preload neighbours.
//   - render(), setStyles(), destroy(): applied to every cached view.
//   - getContents(): also returns loaded hidden views (current first) so annotations/search hits reach them.
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))

const debounce = (f, wait, immediate) => {
    let timeout
    return (...args) => {
        const later = () => {
            timeout = null
            if (!immediate) f(...args)
        }
        const callNow = immediate && !timeout
        if (timeout) clearTimeout(timeout)
        timeout = setTimeout(later, wait)
        if (callNow) f(...args)
    }
}

const lerp = (min, max, x) => x * (max - min) + min
const easeOutQuad = x => 1 - (1 - x) * (1 - x)

// local patch (C-071): the boundary slide's geometry. [slideSign]: which way the content moves for a turn [dir]
// (1 = next): toward the line start, so left in left-to-right books, right in right-to-left ones. [slideFrame]: at
// progress [t] (0..1, eased) the old page's and the incoming page's offsets for a viewport [width]; they stay exactly one
// width apart, so no gap (blank) ever shows between them.
/** Local patch (C-071): the boundary slide's duration, as an in-section turn. */
export const SLIDE_MS = 300

/**
 * Stack mode (#holdScreen): how far the text held by [screen] ({ top, scrollTop } when captured) moved in the document,
 * which is what scrollTop must follow. Its offset in the viewport also changes when the user scrolls; that part is
 * taken out, or holding the text would undo the scroll made since the capture.
 */
export const heldShift = (screen, now, scrollTop) => (now - screen.top) + (scrollTop - screen.scrollTop)

export const slideSign = (dir, rtl) => (dir > 0 ? -1 : 1) * (rtl ? -1 : 1)
export const slideFrame = (t, sign, width) => ({ old: sign * width * t + 0, next: -sign * width * (1 - t) + 0 })
// local patch (C-094): a turn that follows the finger. [dragProgress]: the finger's movement [delta] (CSS px, positive =
// right or down) as progress toward the next page, -1..1, for pages of [size]; [rtl]: pages run right to left.
export const dragProgress = (delta, size, rtl) => size > 0 ? Math.max(-1, Math.min(1, (rtl ? delta : -delta) / size)) + 0 : 0
/** How far (of a page) the page can be pulled where nothing can come in yet. */
export const RESIST_MAX = 0.15
/** Where the page is for a pull of [t] (0..1) against an edge: half the finger's speed at first, then less and less. */
export const resisted = t => RESIST_MAX * (1 - Math.exp(-t / (2 * RESIST_MAX)))
/** The time over which a finger's speed is taken as it lifts (ms). */
const SPEED_WINDOW = 100
/**
 * The finger's speed as it lifted (px/ms), from [trail] ({ d: position, t: time }, oldest first, the lift last): over the
 * last [SPEED_WINDOW] ms, from the latest sample at least that old, so a finger held still before it lifts has none
 * and moves far apart (a slow frame) still give one.
 */
export const fingerSpeed = trail => {
    const last = trail.at(-1)
    const from = trail.findLast(s => s.t <= last.t - SPEED_WINDOW) ?? trail[0]
    return last.t > from.t ? (last.d - from.d) / (last.t - from.t) : 0
}
/** The samples [fingerSpeed] can still need once [t] is the newest time. */
const trimTrail = (trail, t) => { while (trail.length > 2 && trail[1].t <= t - SPEED_WINDOW) trail.shift() }
/** How long the rest of a turn takes, [share] being the part of a page left to go. */
export const settleMs = share => Math.min(SLIDE_MS, Math.max(60, SLIDE_MS * share))
// local patch (C-085): a deliberate overscroll pull in "one chapter at a time" scrolling. [p] holds the finger's y
// (screen CSS px) where the scroller was first seen at its top ([startFrom]) or end ([endFrom]) during this gesture;
// leaving that edge resets it. [next]/[prev]: how far the finger has pulled past the end (up) or the top (down).
export const pullStep = (p, { y, atStart, atEnd }) => {
    const endFrom = atEnd ? (p.endFrom ?? y) : null
    const startFrom = atStart ? (p.startFrom ?? y) : null
    return { startFrom, endFrom, next: endFrom == null ? 0 : endFrom - y, prev: startFrom == null ? 0 : y - startFrom }
}
/** How far a pull must go to count (CSS px): a fifth of the viewport, at least 96 px, so a fling or a nudge never does. */
export const pullThreshold = viewport => Math.max(96, viewport * 0.2)
/** 1 = open the next section, -1 = the previous one, 0 = no turn. */
export const pullTurn = (p, threshold) => p?.next >= threshold ? 1 : p?.prev >= threshold ? -1 : 0
// local patch (C-093): the direction lock of a sideways swipe in "one chapter at a time" scrolling. [lock]: null until
// the finger has moved [SIDE_LOCK_PX] from where it went down ([dx], [dy]: that movement, CSS px); Chromium holds back
// touchmoves inside its touch slop (8 dp), so the first move a page sees decides, and that one is also the only one
// that can still stop the native scroll. 'side': clearly horizontal, a chapter swipe so far; 'off': not one, for the
// rest of the gesture (a scroll never turns into a swipe, and a swipe that drifts out of the angle is dropped).
const SIDE_LOCK_PX = 8
const SIDE_RATIO = 2
export const sideLock = (lock, dx, dy) => {
    if (lock === 'off') return 'off'
    const x = Math.abs(dx), y = Math.abs(dy)
    if (lock == null && Math.max(x, y) < SIDE_LOCK_PX) return null
    return x >= SIDE_RATIO * y ? 'side' : 'off'
}
/**
 * Local patch (C-093): whether [el] is inside content that scrolls sideways by itself (a wide table or `pre` in an
 * overflow box). The document root is left out: a section's frame has `scrolling="no"`.
 */
export const scrollsSideways = el => {
    for (; el?.parentElement; el = el.parentElement) {
        if (el.scrollWidth <= el.clientWidth + 1) continue
        const { overflowX } = el.ownerDocument.defaultView.getComputedStyle(el)
        if (overflowX === 'auto' || overflowX === 'scroll') return true
    }
    return false
}
// local patch (C-094): [control].finish() ends the animation now, at its last step
const animate = (a, b, duration, ease, render, control) => new Promise(resolve => {
    let start
    let ended = false
    if (control) control.finish = () => {
        if (ended) return
        ended = true
        render(lerp(a, b, 1))
        resolve()
    }
    const step = now => {
        if (ended) return
        if (document.hidden) {
            render(lerp(a, b, 1))
            return resolve()
        }
        start ??= now
        const fraction = Math.min(1, (now - start) / duration)
        render(lerp(a, b, ease(fraction)))
        if (fraction < 1) requestAnimationFrame(step)
        else {
            ended = true
            resolve()
        }
    }
    if (document.hidden) {
        render(lerp(a, b, 1))
        return resolve()
    }
    requestAnimationFrame(step)
})

// collapsed range doesn't return client rects sometimes (or always?)
// try make get a non-collapsed range or element
const uncollapse = range => {
    if (!range?.collapsed) return range
    const { endOffset, endContainer } = range
    if (endContainer.nodeType === 1) {
        const node = endContainer.childNodes[endOffset]
        if (node?.nodeType === 1) return node
        return endContainer
    }
    if (endOffset + 1 < endContainer.length) range.setEnd(endContainer, endOffset + 1)
    else if (endOffset > 1) range.setStart(endContainer, endOffset - 1)
    else return endContainer.parentNode
    return range
}

const makeRange = (doc, node, start, end = start) => {
    const range = doc.createRange()
    range.setStart(node, start)
    range.setEnd(node, end)
    return range
}

// use binary search to find an offset value in a text node
const bisectNode = (doc, node, cb, start = 0, end = node.nodeValue.length) => {
    if (end - start === 1) {
        const result = cb(makeRange(doc, node, start), makeRange(doc, node, end))
        return result < 0 ? start : end
    }
    const mid = Math.floor(start + (end - start) / 2)
    const result = cb(makeRange(doc, node, start, mid), makeRange(doc, node, mid, end))
    return result < 0 ? bisectNode(doc, node, cb, start, mid)
        : result > 0 ? bisectNode(doc, node, cb, mid, end) : mid
}

const { SHOW_ELEMENT, SHOW_TEXT, SHOW_CDATA_SECTION,
    FILTER_ACCEPT, FILTER_REJECT, FILTER_SKIP } = NodeFilter

const filter = SHOW_ELEMENT | SHOW_TEXT | SHOW_CDATA_SECTION

// needed cause there seems to be a bug in `getBoundingClientRect()` in Firefox
// where it fails to include rects that have zero width and non-zero height
// (CSSOM spec says "rectangles [...] of which the height or width is not zero")
// which makes the visible range include an extra space at column boundaries
const getBoundingClientRect = target => {
    let top = Infinity, right = -Infinity, left = Infinity, bottom = -Infinity
    for (const rect of target.getClientRects()) {
        left = Math.min(left, rect.left)
        top = Math.min(top, rect.top)
        right = Math.max(right, rect.right)
        bottom = Math.max(bottom, rect.bottom)
    }
    return new DOMRect(left, top, right - left, bottom - top)
}

const getVisibleRange = (doc, start, end, mapRect) => {
    // first get all visible nodes
    const acceptNode = node => {
        const name = node.localName?.toLowerCase()
        // ignore all scripts, styles, and their children
        if (name === 'script' || name === 'style') return FILTER_REJECT
        if (node.nodeType === 1) {
            const { left, right } = mapRect(node.getBoundingClientRect())
            // no need to check child nodes if it's completely out of view
            if (right < start || left > end) return FILTER_REJECT
            // elements must be completely in view to be considered visible
            // because you can't specify offsets for elements
            if (left >= start && right <= end) return FILTER_ACCEPT
            // TODO: it should probably allow elements that do not contain text
            // because they can exceed the whole viewport in both directions
            // especially in scrolled mode
        } else {
            // ignore empty text nodes
            if (!node.nodeValue?.trim()) return FILTER_SKIP
            // create range to get rect
            const range = doc.createRange()
            range.selectNodeContents(node)
            const { left, right } = mapRect(range.getBoundingClientRect())
            // it's visible if any part of it is in view
            if (right >= start && left <= end) return FILTER_ACCEPT
        }
        return FILTER_SKIP
    }
    const walker = doc.createTreeWalker(doc.body, filter, { acceptNode })
    const nodes = []
    for (let node = walker.nextNode(); node; node = walker.nextNode())
        nodes.push(node)

    // we're only interested in the first and last visible nodes
    const from = nodes[0] ?? doc.body
    const to = nodes[nodes.length - 1] ?? from

    // find the offset at which visibility changes
    const startOffset = from.nodeType === 1 ? 0
        : bisectNode(doc, from, (a, b) => {
            const p = mapRect(getBoundingClientRect(a))
            const q = mapRect(getBoundingClientRect(b))
            if (p.right < start && q.left > start) return 0
            return q.left > start ? -1 : 1
        })
    const endOffset = to.nodeType === 1 ? 0
        : bisectNode(doc, to, (a, b) => {
            const p = mapRect(getBoundingClientRect(a))
            const q = mapRect(getBoundingClientRect(b))
            if (p.right < end && q.left > end) return 0
            return q.left > end ? -1 : 1
        })

    const range = doc.createRange()
    range.setStart(from, startOffset)
    range.setEnd(to, endOffset)
    return range
}

const selectionIsBackward = sel => {
    const range = document.createRange()
    range.setStart(sel.anchorNode, sel.anchorOffset)
    range.setEnd(sel.focusNode, sel.focusOffset)
    return range.collapsed
}

const setSelectionTo = (target, collapse) => {
    let range
    if (target.startContainer) range = target.cloneRange()
    else if (target.nodeType) {
        range = document.createRange()
        range.selectNode(target)
    }
    if (range) {
        const sel = range.startContainer.ownerDocument.defaultView.getSelection()
        if (sel) {
            sel.removeAllRanges()
            if (collapse === -1) range.collapse(true)
            else if (collapse === 1) range.collapse()
            sel.addRange(range)
        }
    }
}

const getDirection = doc => {
    const { defaultView } = doc
    const { writingMode, direction } = defaultView.getComputedStyle(doc.body)
    const vertical = writingMode === 'vertical-rl'
        || writingMode === 'vertical-lr'
    const rtl = doc.body.dir === 'rtl'
        || direction === 'rtl'
        || doc.documentElement.dir === 'rtl'
    return { vertical, rtl, writingMode }
}

// local patch (vertical-pages, F-081): with the `vertical-pages` attribute, a vertical-rl section in paginated flow is
// laid out as horizontal pages moving right-to-left: the root is a multicol container with one column per row
// (`column-height` = page width minus the gap, `column-wrap: wrap`), so rows of whole lines stack in the block
// direction (leftwards) and each row is one page. The paginator then pages it like a right-to-left horizontal book.
// Engines without `column-wrap` keep foliate's vertical (top-to-bottom) pages.
export const columnWrapSupported = () => !!globalThis.CSS?.supports?.('column-wrap', 'wrap')
    && globalThis.CSS.supports('column-height', '1px')
export const pagedVerticalRl = (layout, writingMode) => !!layout?.verticalPages
    && layout.flow !== 'scrolled' && writingMode === 'vertical-rl'
/**
 * LOCAL PATCH (C-072): the side inset (CSS px per side) that gives horizontal scrolled flow the text column of paged
 * flow: where the paged #container starts in the grid ([probeStart], i.e. the half-gap grid column) plus half of
 * paged flow's column gap, `g / (1 - g)` of the container's width ([probeSize]). `g` is the `gap` attribute as a
 * fraction.
 */
export const scrolledInlineInset = ({ probeStart, probeSize, g }) => probeStart + (-g / (g - 1) * probeSize) / 2

export const verticalPagesRootStyles = ({ height, gap, columnWidth }) => ({
    'box-sizing': 'border-box',
    'writing-mode': 'vertical-rl',
    'column-count': '1',
    'column-width': 'auto',
    'column-gap': '0px',
    'column-height': `${columnWidth}px`,
    'column-wrap': 'wrap',
    'row-gap': `${gap}px`,
    'column-fill': 'auto',
    'height': `${height}px`,
    'width': 'auto',
    'padding': `0 ${gap / 2}px`,
    'overflow': 'hidden',
    'overflow-wrap': 'break-word',
    'position': 'static', 'border': '0', 'margin': '0',
    'max-height': 'none', 'max-width': 'none',
    'min-height': 'none', 'min-width': 'none',
    '-webkit-line-box-contain': 'block glyphs replaced',
})
const clearVerticalPages = el => {
    for (const k of ['writing-mode', 'column-count', 'column-height', 'column-wrap', 'row-gap'])
        el.style.removeProperty(k)
}

const getBackground = doc => {
    const bodyStyle = doc.defaultView.getComputedStyle(doc.body)
    return bodyStyle.backgroundColor === 'rgba(0, 0, 0, 0)'
        && bodyStyle.backgroundImage === 'none'
        ? doc.defaultView.getComputedStyle(doc.documentElement).background
        : bodyStyle.background
}

const makeMarginals = (length, part) => Array.from({ length }, () => {
    const div = document.createElement('div')
    const child = document.createElement('div')
    div.append(child)
    child.setAttribute('part', part)
    return div
})

const setStylesImportant = (el, styles) => {
    const { style } = el
    for (const [k, v] of Object.entries(styles)) style.setProperty(k, v, 'important')
}

export class View {
    #loading = null // resolves the running load()
    #observer = new ResizeObserver(() => this.expand())
    #element = document.createElement('div')
    #iframe = document.createElement('iframe')
    #contentRange = document.createRange()
    #overlayer
    #vertical = false
    #rtl = false
    #writingMode = ''
    #vrl = false // local patch (vertical-pages): vertical-rl laid out as horizontal right-to-left pages
    #column = true
    #size
    #layout = {}
    constructor({ container, onExpand }) {
        this.container = container
        this.onExpand = onExpand
        this.#iframe.setAttribute('part', 'filter')
        this.#element.append(this.#iframe)
        Object.assign(this.#element.style, {
            boxSizing: 'content-box',
            position: 'relative',
            overflow: 'hidden',
            flex: '0 0 auto',
            width: '100%', height: '100%',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
        })
        Object.assign(this.#iframe.style, {
            overflow: 'hidden',
            border: '0',
            display: 'none',
            width: '100%', height: '100%',
        })
        // `allow-scripts` is needed for events because of WebKit bug
        // https://bugs.webkit.org/show_bug.cgi?id=218086
        this.#iframe.setAttribute('sandbox', 'allow-same-origin allow-scripts')
        this.#iframe.setAttribute('scrolling', 'no')
    }
    get element() {
        return this.#element
    }
    get document() {
        return this.#iframe.contentDocument
    }
    async load(src, afterLoad, beforeRender) {
        if (typeof src !== 'string') throw new Error(`${src} is not string`)
        return new Promise(resolve => {
            this.#loading = resolve
            this.#iframe.addEventListener('load', () => {
                const doc = this.document
                afterLoad?.(doc)

                // it needs to be visible for Firefox to get computed style
                this.#iframe.style.display = 'block'
                const { vertical, rtl, writingMode } = getDirection(doc)
                const background = getBackground(doc)
                this.#iframe.style.display = 'none'

                this.#vertical = vertical
                this.#rtl = rtl
                this.#writingMode = writingMode

                this.#contentRange.selectNodeContents(doc.body)
                const layout = beforeRender?.({ vertical, rtl, writingMode, background })
                this.#iframe.style.display = 'block'
                this.render(layout)
                this.#observer.observe(doc.body)

                // the resize observer above doesn't work in Firefox
                // (see https://bugzilla.mozilla.org/show_bug.cgi?id=1832939)
                // until the bug is fixed we can at least account for font load
                doc.fonts.ready.then(() => this.expand())

                resolve()
            }, { once: true })
            this.#iframe.src = src
        })
    }
    render(layout) {
        if (!layout) return
        this.#column = layout.flow !== 'scrolled'
        this.#layout = layout
        this.#vrl = pagedVerticalRl(layout, this.#writingMode)
        if (this.#vrl) this.#columnizeVerticalPages(layout)
        else if (this.#column) this.columnize(layout)
        else this.scrolled(layout)
    }
    scrolled({ gap, columnWidth }) {
        const vertical = this.#vertical
        const doc = this.document
        clearVerticalPages(doc.documentElement)
        setStylesImportant(doc.documentElement, {
            'box-sizing': 'border-box',
            'padding': vertical ? `${gap}px 0` : `0 ${gap}px`,
            'column-width': 'auto',
            'height': 'auto',
            'width': 'auto',
        })
        setStylesImportant(doc.body, {
            [vertical ? 'max-height' : 'max-width']: `${columnWidth}px`,
            'margin': 'auto',
        })
        this.setImageSize()
        this.expand()
    }
    #columnizeVerticalPages(layout) {
        this.#size = layout.width
        const doc = this.document
        // the paged root's earlier styles (another flow) must not stay: foliate's vertical layout fixes the width
        doc.documentElement.style.removeProperty('width')
        setStylesImportant(doc.documentElement, verticalPagesRootStyles(layout))
        setStylesImportant(doc.body, {
            'max-height': 'none',
            'max-width': 'none',
            'margin': '0',
        })
        this.setImageSize()
        this.expand()
    }
    columnize({ width, height, gap, columnWidth }) {
        const vertical = this.#vertical
        this.#size = vertical ? height : width

        const doc = this.document
        clearVerticalPages(doc.documentElement)
        setStylesImportant(doc.documentElement, {
            'box-sizing': 'border-box',
            'column-width': `${Math.trunc(columnWidth)}px`,
            'column-gap': `${gap}px`,
            'column-fill': 'auto',
            ...(vertical
                ? { 'width': `${width}px` }
                : { 'height': `${height}px` }),
            'padding': vertical ? `${gap / 2}px 0` : `0 ${gap / 2}px`,
            'overflow': 'hidden',
            // force wrap long words
            'overflow-wrap': 'break-word',
            // reset some potentially problematic props
            'position': 'static', 'border': '0', 'margin': '0',
            'max-height': 'none', 'max-width': 'none',
            'min-height': 'none', 'min-width': 'none',
            // fix glyph clipping in WebKit
            '-webkit-line-box-contain': 'block glyphs replaced',
        })
        setStylesImportant(doc.body, {
            'max-height': 'none',
            'max-width': 'none',
            'margin': '0',
        })
        this.setImageSize()
        this.expand()
    }
    setImageSize() {
        const { width, height, margin } = this.#layout
        const vertical = this.#vertical
        const doc = this.document
        if (this.#vrl) { // a page is one row: as wide as the row, as tall as the page
            for (const el of doc.body.querySelectorAll('img, svg, video')) setStylesImportant(el, {
                'max-height': `${height}px`,
                'max-width': `${this.#layout.columnWidth}px`,
                'object-fit': 'contain',
                'page-break-inside': 'avoid',
                'break-inside': 'avoid',
                'box-sizing': 'border-box',
            })
            return
        }
        for (const el of doc.body.querySelectorAll('img, svg, video')) {
            // preserve max size if they are already set
            const { maxHeight, maxWidth } = doc.defaultView.getComputedStyle(el)
            setStylesImportant(el, {
                'max-height': vertical
                    ? (maxHeight !== 'none' && maxHeight !== '0px' ? maxHeight : '100%')
                    : `${height - margin * 2}px`,
                'max-width': vertical
                    ? `${width - margin * 2}px`
                    : (maxWidth !== 'none' && maxWidth !== '0px' ? maxWidth : '100%'),
                'object-fit': 'contain',
                'page-break-inside': 'avoid',
                'break-inside': 'avoid',
                'box-sizing': 'border-box',
            })
        }
    }
    expand() {
        const { documentElement } = this.document
        if (this.#vrl) { // pages run leftwards from the root's right edge; the root's width follows its rows
            const contentRect = this.#contentRange.getBoundingClientRect()
            const rootRect = documentElement.getBoundingClientRect()
            const contentSize = rootRect.right - contentRect.left
            const pageCount = Math.max(1, Math.ceil(Math.round(contentSize) / this.#size))
            const expandedSize = pageCount * this.#size
            this.#element.style.padding = '0'
            this.#iframe.style.width = `${expandedSize}px`
            this.#element.style.width = `${expandedSize + this.#size * 2}px`
            this.#iframe.style.height = '100%'
            this.#element.style.height = '100%'
            if (this.#overlayer) {
                this.#overlayer.element.style.margin = '0'
                this.#overlayer.element.style.left = `${this.#size}px`
                this.#overlayer.element.style.top = '0'
                this.#overlayer.element.style.width = `${expandedSize}px`
                this.#overlayer.redraw()
            }
        } else if (this.#column) {
            const side = this.#vertical ? 'height' : 'width'
            const otherSide = this.#vertical ? 'width' : 'height'
            const contentRect = this.#contentRange.getBoundingClientRect()
            const rootRect = documentElement.getBoundingClientRect()
            // offset caused by column break at the start of the page
            // which seem to be supported only by WebKit and only for horizontal writing
            const contentStart = this.#vertical ? 0
                : this.#rtl ? rootRect.right - contentRect.right : contentRect.left - rootRect.left
            const contentSize = contentStart + contentRect[side]
            const pageCount = Math.ceil(contentSize / this.#size)
            const expandedSize = pageCount * this.#size
            this.#element.style.padding = '0'
            this.#iframe.style[side] = `${expandedSize}px`
            this.#element.style[side] = `${expandedSize + this.#size * 2}px`
            this.#iframe.style[otherSide] = '100%'
            this.#element.style[otherSide] = '100%'
            documentElement.style[side] = `${this.#size}px`
            if (this.#overlayer) {
                this.#overlayer.element.style.margin = '0'
                this.#overlayer.element.style.left = this.#vertical ? '0' : `${this.#size}px`
                this.#overlayer.element.style.top = this.#vertical ? `${this.#size}px` : '0'
                this.#overlayer.element.style[side] = `${expandedSize}px`
                this.#overlayer.redraw()
            }
        } else {
            const side = this.#vertical ? 'width' : 'height'
            const otherSide = this.#vertical ? 'height' : 'width'
            const contentSize = documentElement.getBoundingClientRect()[side]
            const expandedSize = contentSize
            const { margin } = this.#layout
            const padding = this.#vertical ? `0 ${margin}px` : `${margin}px 0`
            this.#element.style.padding = padding
            this.#iframe.style[side] = `${expandedSize}px`
            this.#element.style[side] = `${expandedSize}px`
            this.#iframe.style[otherSide] = '100%'
            this.#element.style[otherSide] = '100%'
            if (this.#overlayer) {
                this.#overlayer.element.style.margin = padding
                this.#overlayer.element.style.left = '0'
                this.#overlayer.element.style.top = '0'
                this.#overlayer.element.style[side] = `${expandedSize}px`
                this.#overlayer.redraw()
            }
        }
        this.onExpand()
    }
    set overlayer(overlayer) {
        this.#overlayer = overlayer
        this.#element.append(overlayer.element)
    }
    get overlayer() {
        return this.#overlayer
    }
    // local patch: a view can go while it loads (a jump drops the stacked sections): its document has no body yet, and its
    // frame, out of the page, never fires `load`, so whoever waits for the load is released here
    destroy() {
        const body = this.document?.body
        if (body) this.#observer.unobserve(body)
        this.#loading?.()
    }
}

/** Two frames, or 200 ms where frames don't run (a hidden page). */
const nextFrames = () => new Promise(r => { requestAnimationFrame(() => requestAnimationFrame(r)); setTimeout(r, 200) })

// NOTE: everything here assumes the so-called "negative scroll type" for RTL
export class Paginator extends HTMLElement {
    static observedAttributes = [
        'flow', 'gap', 'margin',
        'max-inline-size', 'max-block-size', 'max-column-count',
        'chapter-stop', // local patch (C-085)
    ]
    #root = this.attachShadow({ mode: 'closed' })
    #observer = new ResizeObserver(() => this.render())
    #top
    #background
    #container
    #header
    #footer
    #view
    #vertical = false
    #rtl = false
    // local patch (vertical-pages): the current section's own writing direction; #vertical/#rtl above are the page
    // axis the paginator scrolls (a vertical-rl section with vertical pages scrolls like a right-to-left book)
    #docVertical = false
    #docRtl = false
    #writingMode = ''
    #margin = 0
    #index = -1
    #anchor = 0 // anchor view to a fraction (0-1), Range, or Element
    #justAnchored = false
    #locked = false // while true, prevent any further navigation
    #styles
    #styleMap = new WeakMap()
    #mediaQuery = matchMedia('(prefers-color-scheme: dark)')
    #mediaQueryListener
    #scrollBounds
    #touchState
    #touchScrolled
    #lastVisibleRange
    #lastLayout
    #cache = new Map()
    // Stack mode: scrolled flow, horizontal writing. Slots are stacked in reading order in one scroller, so
    // scrolling continues across sections; the current section is the one at the top of the viewport.
    get #stack() { return this.hasAttribute('preload') && this.scrolled && !this.#vertical }
    get #preload() { return this.hasAttribute('preload') && (!this.scrolled || this.#stack) }
    // local patch (C-085): "Chapters: One at a time": the stack holds only the current section (no neighbours above or
    // below), framed by the edge controls; everything else (jumps, holding the text in place) is stack mode's
    get #single() { return this.#stack && this.hasAttribute('chapter-stop') }
    #mode = null // 'paged' | 'stack' | 'single' | null: what the cached slots are laid out for
    #stackTop = 0 // offsetTop of the current slot when the scroll position was last set or fixed
    // [side] (C-093): a chapter next to the current one in One at a time, laid out and hidden, outside the stack's flow
    #slotStyle(slot, current, side = false) {
        slot.style.cssText = this.#stack && !side ? ''
            : current ? 'height: 100%'
            : 'position: absolute; inset: 0; overflow: hidden; visibility: hidden; pointer-events: none'
    }
    #slotOffset() {
        return this.#stack ? (this.#cache.get(this.#index)?.slot.offsetTop ?? 0) : 0
    }
    /**
     * Runs [mutate] (adding or removing a slot) without moving what is on screen. The position inside the current
     * section is taken before: removing content can make the browser clamp scrollTop first.
     */
    #keepStack(mutate) {
        if (!this.#stack || !this.#cache.has(this.#index)) { mutate(); this.#fixStack(); return }
        const cur = this.#cache.get(this.#index)
        const was = cur.slot.offsetTop
        const rel = this.#container.scrollTop - was
        mutate()
        if (this.#cache.get(this.#index) !== cur) { this.#fixStack(); return }
        this.#container.scrollTop = cur.slot.offsetTop + rel
        this.#stackTop = cur.slot.offsetTop
        this.#screenMoved(cur.slot.offsetTop - was)
    }
    /** Keeps what is on screen in place when slots above the current one change height. */
    #fixStack() {
        if (!this.#stack) return
        const top = this.#slotOffset()
        const delta = top - this.#stackTop
        if (delta) this.#container.scrollTop += delta
        this.#stackTop = top
        this.#screenMoved(delta)
    }
    // local patch (C-079/C-080): stack-mode stability
    #scrolledAt = -Infinity // when the container last scrolled
    #jumps = 0 // deliberate jumps or re-layouts being applied (they can overlap): no following, no 'scroll' relocates, no stack work
    get #jumping() { return this.#jumps > 0 }
    #restoring = false // since the last deliberate jump the user has not scrolled: layout changes re-apply its anchor
    #screen = null // { view, el, top, scrollTop }: the element at the top of the viewport, its offset there and the scroll position then (user scrolling)
    // after the last stacked slot: one screen of room while more sections follow, so a jump to a short section (or the
    // end of one) is not clamped by the scroll range into the section before it (C-080)
    #stackPad = Object.assign(document.createElement('div'), { className: 'stack-pad' })
    #noStack = new Set() // sections that failed to load into the stack (error, other writing mode): its end there
    #nextStacked(index, dir) {
        const i = this.#linearFrom(index, dir)
        if (i == null || this.#noStack.has(i)) return null
        // C-085: One at a time stacks only the parts of the current chapter (a huge document the host split)
        if (this.#single) { const [f, l] = this.#span(index); return i >= f && i <= l ? i : null }
        return i
    }
    /** C-085: the first and last section of the chapter [index] is in; [chapterSpan] is the host's (parts of one document). */
    chapterSpan = null
    #span(index) { return this.chapterSpan?.(index) ?? [index, index] }
    /** The slots of the stack, without the hidden chapters beside it (C-093). */
    get #stacked() { return [...this.#cache.values()].filter(e => !e.side) }
    #padStack() {
        const last = Math.max(-1, ...this.#stacked.map(e => e.index))
        const vh = this.#container.clientHeight
        let h = 0
        if (this.#stack && last >= 0) {
            // more to come: a screen of room; the book's (or the stack's) last section: just enough that a section
            // shorter than the screen can still be scrolled to the top, and no blank screen past the end
            // (One at a time, C-085: room only while parts of the chapter follow; at its end the Next control)
            if (this.#nextStacked(last, 1) != null) h = vh
            else if (!this.#single) h = Math.max(0, vh - (this.#cache.get(last)?.slot.offsetHeight ?? vh))
        }
        h = `${h}px`
        if (this.#stackPad.style.height !== h) this.#stackPad.style.height = h
        if (this.#stackPad.parentNode !== this.#container) this.#container.append(this.#stackPad)
    }
    /** Runs a deliberate navigation [f]; the flags drop two frames after it (its own scroll events are past by then). */
    async #navigate(f) {
        this.#jumps++
        // A fling runs on the compositor and doesn't stop for a scrollTop set from here: its later scroll events would
        // move the page and the current section off the target. A scroller that can't be scrolled by the user ends it.
        // It ends at the next frames, so a jump made while the page moves waits for them, or the last steps would land on it.
        if (this.#stack) {
            this.#container.style.overflow = 'hidden'
            if (performance.now() - this.#scrolledAt < 100) await nextFrames()
        }
        this.#restoring = true
        this.#screen = null
        this.#noStack.clear()
        try { return await f() } finally {
            await nextFrames()
            this.#jumps--
            if (!this.#jumps) this.#container.style.overflow = ''
            this.#ensureStack()
        }
    }
    /** The user scrolls (touch, wheel, keys, a turn): from now on nothing re-applies a jump's anchor. */
    #userScroll() {
        this.#restoring = false
        this.#justAnchored = false
    }
    #screenTop(view, el) {
        const frame = view.element.querySelector('iframe')
        if (!frame) return null
        return el.getBoundingClientRect().top + frame.getBoundingClientRect().top - this.#container.getBoundingClientRect().top
    }
    /** Remembers which element of the current section is at the top of the viewport, and where. */
    #captureScreen() {
        if (!this.#stack || this.#jumping) return
        const e = this.#cache.get(this.#index)
        const doc = e?.view.document, frame = e?.view.element.querySelector('iframe')
        if (!doc || !frame) { this.#screen = null; return }
        const f = frame.getBoundingClientRect(), c = this.#container.getBoundingClientRect()
        const y = c.top + 1 - f.top
        let el = y >= 0 && y < f.height ? doc.elementFromPoint(f.width / 2, y) : null
        if (el === doc.body || el === doc.documentElement) el = null // a gap: no element to hold (the slot is held)
        this.#screen = el ? { view: e.view, el, top: this.#screenTop(e.view, el), scrollTop: this.#container.scrollTop } : null
    }
    /** A slot's height changed: the text on screen stays where it is (the captured element, else the current slot). */
    #holdScreen() {
        const s = this.#screen
        if (!s || !s.el.isConnected || this.#cache.get(this.#index)?.view !== s.view) { this.#fixStack(); return }
        const now = this.#screenTop(s.view, s.el)
        const moved = now == null ? 0 : heldShift(s, now, this.#container.scrollTop)
        if (Math.abs(moved) > 0.5) {
            this.#container.scrollTop += moved
            this.#screenMoved(moved)
        }
        this.#stackTop = this.#slotOffset()
    }
    /** The text on screen was kept in place by moving scrollTop by [delta]: that part of it isn't the user's scrolling. */
    #screenMoved(delta) {
        if (this.#screen) this.#screen.scrollTop += delta
    }
    #linearFrom(index, dir) {
        for (let i = index + dir; this.#canGoToIndex(i); i += dir) if (this.sections[i]?.linear !== 'no') return i
    }
    /**
     * Stack mode: sections are loaded until about three screens of text are below the viewport and two above (one at a
     * time, each when the previous has loaded, so short sections chain), and slots far from the viewport are dropped.
     */
    #ensureStack() {
        this.#padStack()
        if (!this.#stack || this.#jumping) return
        const entries = this.#stacked.sort((a, b) => a.index - b.index)
        if (!entries.length) return
        const el = this.#container, vh = el.clientHeight || 1, top = el.scrollTop
        const first = entries[0], last = entries.at(-1)
        if (entries.length < 12) { // a bound on live iframes, whatever the sections' sizes
            if (last.loaded && last.slot.offsetTop + last.slot.offsetHeight < top + 4 * vh) this.#preloadIndex(this.#nextStacked(last.index, 1))
            if (first.loaded && first.slot.offsetTop > top - 2 * vh) this.#preloadIndex(this.#nextStacked(first.index, -1))
        }
        const near = new Set([this.#index, this.#linearFrom(this.#index, -1), this.#linearFrom(this.#index, 1)])
        for (const e of entries) {
            if (near.has(e.index) || !e.loaded) continue
            const t = e.slot.offsetTop, b = t + e.slot.offsetHeight
            if (b < top - 6 * vh || t > top + 8 * vh) this.#evict(e.index)
        }
    }
    // local patch (C-085): the "Previous: <title>" control above the current section's first line and the "Next: <title>"
    // control below its last line (One at a time); [edgeLabel(index, dir)] is the host's label for section [index]
    edgeLabel = null
    #edgeHead = this.#makeEdge(-1)
    #edgeFoot = this.#makeEdge(1)
    #pull = null // the overscroll pull of the running gesture (pullStep), null when it can't be one
    #makeEdge(dir) {
        const el = Object.assign(document.createElement('div'), { className: 'chapter-edge' })
        const b = Object.assign(document.createElement('button'), { type: 'button' })
        b.addEventListener('click', () => this.#openAdjacent(dir))
        el.append(b)
        return el
    }
    /**
     * One at a time: the edge controls go right before the chapter's first section and right after its last one (a
     * chapter split into parts, C-085: around the whole run, each control only while that end's slot is laid out); the
     * text on screen is kept in place. Otherwise they go away.
     */
    #placeEdges() {
        const cur = this.#single ? this.#cache.get(this.#index) : null
        const head = this.#edgeHead, foot = this.#edgeFoot
        if (!cur && !head.isConnected && !foot.isConnected) return
        const [f, l] = this.#span(this.#index)
        this.#keepStack(() => {
            if (!cur) { head.remove(); foot.remove(); return }
            for (const [el, dir, end] of [[head, -1, f], [foot, 1, l]]) {
                const e = this.#cache.get(end)
                if (!e?.loaded && e !== cur) { el.remove(); continue }
                const target = this.#chapterTarget(dir) // the book's first and last sections have none
                el.hidden = target == null
                el.dataset.index = target ?? ''
                const label = target == null ? '' : String(this.edgeLabel?.(target, dir) ?? '')
                if (el.firstChild.textContent !== label) el.firstChild.textContent = label
                if (dir < 0 && e.slot.previousSibling !== el) this.#container.insertBefore(el, e.slot)
                if (dir > 0 && e.slot.nextSibling !== el) e.slot.after(el)
            }
        })
    }
    /** The section that opens the next chapter ([dir] 1: its first section) or the previous one (-1: its last section). */
    #chapterTarget(dir) {
        const [f, l] = this.#span(this.#index)
        return this.#linearFrom(dir > 0 ? l : f, dir) ?? null
    }
    /** One at a time: opens the next chapter at its top ([dir] 1) or the previous one at its end (-1), as a jump. */
    #openAdjacent(dir) {
        const index = this.#chapterTarget(dir)
        if (index == null || !this.#single) return
        return this.goTo({ index, anchor: dir > 0 ? 0 : 1 })
    }
    #pullDir() {
        const t = pullTurn(this.#pull, pullThreshold(this.#container.clientHeight))
        // only at the chapter's own end or top (its control laid out), not at the end of a part still loading
        const edge = t > 0 ? this.#edgeFoot : this.#edgeHead
        return t && edge.isConnected && this.#chapterTarget(t) != null ? t : 0
    }
    #armEdges(dir) {
        this.#edgeHead.classList.toggle('armed', dir < 0)
        this.#edgeFoot.classList.toggle('armed', dir > 0)
    }
    #pullMove(e) {
        if (!this.#pull) return
        const y = e.changedTouches?.[0]?.screenY
        if (e.touches?.length > 1 || y == null) { this.#pull = null; this.#armEdges(0); return }
        const el = this.#container
        this.#pull = pullStep(this.#pull, {
            y, atStart: el.scrollTop <= 2, atEnd: el.scrollTop + el.clientHeight >= el.scrollHeight - 2,
        })
        this.#armEdges(this.#pullDir())
    }
    #pullEnd(e) {
        const dir = this.#pull && this.#single && !e?.touches?.length && !(globalThis.visualViewport?.scale > 1) ? this.#pullDir() : 0
        this.#pull = null
        this.#armEdges(0)
        if (dir) this.#openAdjacent(dir)
    }
    /** Diagnostics for tests (C-085): the edge controls (viewport rects of their buttons, null if not shown) and the scroller. */
    get debugEdges() {
        const r = el => {
            if (!el.isConnected || el.hidden) return null
            const b = el.firstChild.getBoundingClientRect()
            return { label: el.firstChild.textContent, index: Number(el.dataset.index), x: b.left, y: b.top, w: b.width, h: b.height }
        }
        const el = this.#container
        return { single: this.#single, head: r(this.#edgeHead), foot: r(this.#edgeFoot), span: this.#span(this.#index),
            scrollTop: el.scrollTop, scrollHeight: el.scrollHeight, viewport: el.clientHeight, current: this.#index,
            start: this.#view ? this.start : null, viewSize: this.#view ? this.viewSize : null }
    }
    /** Stack mode: the slot at the top of the viewport becomes the current section (no scrolling). */
    #followStack() {
        if (!this.#stack || this.#jumping) return false
        const y = this.#container.scrollTop + 1
        for (const e of this.#cache.values()) {
            const { offsetTop, offsetHeight } = e.slot
            if (e.loaded && !e.side && e.index !== this.#index && y >= offsetTop && y < offsetTop + offsetHeight) {
                this.#view = e.view
                this.#index = e.index
                this.#stackTop = offsetTop
                this.#background.style.background = getBackground(e.view.document)
                return true
            }
        }
        return false
    }
    #newEntry(index, current, side = false) {
        const slot = document.createElement('div')
        this.#slotStyle(slot, current, side)
        const view = new View({
            container: this,
            // local patch (C-079): a jump's anchor is re-applied only until the user scrolls; otherwise the text on
            // screen stays put (a section or an image above it grew)
            onExpand: () => {
                if (this.#stack && !this.#restoring) this.#holdScreen()
                else if (this.#view === view) this.#scrollToAnchor(this.#anchor)
                else this.#fixStack()
            },
        })
        slot.append(view.element)
        // in reading order, so stacked slots read top to bottom
        const after = side ? null : this.#stacked.filter(e => e.index > index).sort((a, b) => a.index - b.index)[0]
        this.#padStack()
        this.#keepStack(() => this.#container.insertBefore(slot, after?.slot ?? this.#stackPad))
        this.#padStack()
        const entry = { index, slot, view, loaded: false, side }
        this.#cache.set(index, entry)
        return entry
    }
    #evict(index) {
        const e = this.#cache.get(index)
        if (!e) return
        this.#cache.delete(index)
        e.view.destroy()
        this.#keepStack(() => e.slot.remove())
        this.#padStack()
        this.sections[index]?.unload?.()
        if (this.#single) this.#placeEdges() // C-085: a chapter's end slot went (its control goes with it)
    }
    #afterLoadFor(index) {
        return doc => {
            if (doc.head) {
                const $styleBefore = doc.createElement('style')
                doc.head.prepend($styleBefore)
                const $style = doc.createElement('style')
                doc.head.append($style)
                this.#styleMap.set(doc, [$styleBefore, $style])
                this.#applyStyles(doc)
            }
            this.dispatchEvent(new CustomEvent('load', { detail: { doc, index } }))
        }
    }
    #applyStyles(doc) {
        const $$styles = this.#styleMap.get(doc)
        const styles = this.#styles
        if (!$$styles || styles == null) return
        const [$beforeStyle, $style] = $$styles
        if (Array.isArray(styles)) {
            $beforeStyle.textContent = styles[0]
            $style.textContent = styles[1]
        } else $style.textContent = styles
    }
    #preloadIndex(index, side = false) {
        if (index == null || this.#cache.has(index) || (side && this.#noStack.has(index))) return
        const entry = this.#newEntry(index, false, side)
        entry.ready = (async () => {
            const src = await this.sections[index].load()
            if (this.#cache.get(index) !== entry) return // evicted meanwhile (its unload released the section)
            if (!src) throw new Error(`no src for ${index}`)
            // Same layout as the current view; a different writing mode would need its own layout: not preloaded.
            await entry.view.load(src, this.#afterLoadFor(index), ({ vertical, rtl, writingMode }) => {
                if (vertical !== this.#docVertical || rtl !== this.#docRtl || writingMode !== this.#writingMode)
                    entry.mismatch = true
                return this.#lastLayout
            })
            if (this.#cache.get(index) !== entry) return // evicted while it loaded
            if (entry.mismatch) throw new Error(`section ${index}: other writing mode, not preloaded`)
            this.dispatchEvent(new CustomEvent('create-overlayer', {
                detail: { doc: entry.view.document, index, attach: o => entry.view.overlayer = o },
            }))
            entry.loaded = true
            if (this.#single) this.#placeEdges() // C-085: a part at the chapter's end or start is laid out
            this.#ensureStack() // stack mode: the next one, if the text below is still short
        })().catch(e => {
            if (!entry.mismatch) console.warn(e)
            if (this.#stack) this.#noStack.add(index) // not retried on every scroll event (C-079 review)
            if (this.#cache.get(index) === entry) this.#evict(index)
        })
    }
    /** [below]: only preload the next section (safe while the user scrolls; nothing above moves). */
    #afterSwap(below = false) {
        if (this.#single) { // C-085: the current chapter alone (its parts, if split), between its edge controls
            const [f, l] = this.#span(this.#index)
            const sides = [this.#chapterTarget(1), this.#chapterTarget(-1)] // C-093: ready to slide in
            for (const [i, e] of [...this.#cache]) if ((i < f || i > l) && !(e.side && sides.includes(i))) this.#evict(i)
            this.#placeEdges()
            this.#preloadIndex(this.#nextStacked(this.#index, 1))
            if (!below) this.#preloadIndex(this.#nextStacked(this.#index, -1))
            for (const i of sides) this.#preloadIndex(i, true)
            return
        }
        for (const [i, e] of [...this.#cache]) if (e.side) this.#evict(i) // left One at a time
        this.#placeEdges()
        if (!this.#preload) return
        if (below) { this.#preloadIndex(this.#adjacentIndex(1)); return }
        const keep = new Set([this.#index, this.#adjacentIndex(-1), this.#adjacentIndex(1)])
        for (const i of [...this.#cache.keys()]) if (!keep.has(i)) this.#evict(i)
        this.#preloadIndex(this.#adjacentIndex(1))
        this.#preloadIndex(this.#adjacentIndex(-1))
    }
    async #swapTo(index, anchor, select) {
        const entry = this.#cache.get(index)
        if (!entry.loaded) await entry.ready
        if (!entry.loaded || this.#cache.get(index) !== entry) return false
        const cur = this.#cache.get(this.#index)
        if (cur) this.#slotStyle(cur.slot, false)
        entry.side = false
        this.#slotStyle(entry.slot, true)
        this.#view = entry.view
        this.#index = index
        this.#background.style.background = getBackground(entry.view.document)
        // synchronous for fraction anchors: the target page is scrolled into place before the next frame
        await this.scrollToAnchor((typeof anchor === 'function'
            ? anchor(entry.view.document) : anchor) ?? 0, select)
        this.#afterSwap()
        return true
    }
    get layout() { return this.#lastLayout }
    /**
     * The layout paged flow has (or would have) at the current size: what #beforeRender computes for horizontal
     * writing, without its side effects. The page counter counts with it in either flow (C-002).
     */
    get pagedLayout() {
        // vertical pages use the horizontal grid; in scrolled flow a vertical book has the `vertical` class
        const unvertical = this.#verticalPages && this.#top.classList.contains('vertical')
        if (unvertical) this.#top.classList.remove('vertical')
        const { width, height } = this.#root.getElementById('probe').getBoundingClientRect()
        const style = getComputedStyle(this.#top)
        if (unvertical) this.#top.classList.add('vertical')
        const maxInlineSize = parseFloat(style.getPropertyValue('--_max-inline-size'))
        const maxColumnCount = parseInt(style.getPropertyValue('--_max-column-count-spread'))
        const margin = parseFloat(style.getPropertyValue('--_margin'))
        const g = parseFloat(style.getPropertyValue('--_gap')) / 100
        const gap = -g / (g - 1) * width
        const divisor = Math.min(maxColumnCount, Math.ceil(width / maxInlineSize))
        return { height, width, margin, gap, columnWidth: (width / divisor) - gap,
            ...(this.#verticalPages ? { verticalPages: true } : {}) }
    }
    get #verticalPages() { return this.hasAttribute('vertical-pages') && columnWrapSupported() }
    /** Local patch (vertical-pages): true while the current section is a vertical-rl one paged right-to-left. */
    get verticalPages() { return !this.scrolled && this.#verticalPages && this.#writingMode === 'vertical-rl' }
    /** Diagnostics for tests: the slots in DOM order and the scroll position. */
    get debugSlots() {
        return { scrollTop: this.#container.scrollTop, viewport: this.#container.clientHeight, current: this.#index, stackTop: this.#stackTop, mode: this.#mode,
            slots: [...this.#container.children].map(el => {
                const e = [...this.#cache.values()].find(x => x.slot === el)
                return { index: e?.index, top: el.offsetTop, height: el.offsetHeight, loaded: e?.loaded, side: e?.side, css: e ? undefined : el.style.cssText.slice(0, 90), raw: el === this.#view?.element }
            }) }
    }
    /** True while a page turn or a jump is running (navigation is locked). */
    get busy() { return this.#locked }
    get styles() { return this.#styles }
    get containerRect() { return this.#container.getBoundingClientRect() }
    constructor() {
        super()
        this.#root.innerHTML = `<style>
        :host {
            display: block;
            container-type: size;
        }
        :host, #top {
            box-sizing: border-box;
            position: relative;
            overflow: hidden;
            width: 100%;
            height: 100%;
        }
        #top {
            --_gap: 7%;
            --_margin: 48px;
            --_max-inline-size: 720px;
            --_max-block-size: 1440px;
            --_max-column-count: 2;
            --_max-column-count-portrait: 1;
            --_max-column-count-spread: var(--_max-column-count);
            --_half-gap: calc(var(--_gap) / 2);
            --_max-width: calc(var(--_max-inline-size) * var(--_max-column-count-spread));
            --_max-height: var(--_max-block-size);
            display: grid;
            grid-template-columns:
                minmax(var(--_half-gap), 1fr)
                var(--_half-gap)
                minmax(0, calc(var(--_max-width) - var(--_gap)))
                var(--_half-gap)
                minmax(var(--_half-gap), 1fr);
            grid-template-rows:
                minmax(var(--_margin), 1fr)
                minmax(0, var(--_max-height))
                minmax(var(--_margin), 1fr);
            &.vertical {
                --_max-column-count-spread: var(--_max-column-count-portrait);
                --_max-width: var(--_max-block-size);
                --_max-height: calc(var(--_max-inline-size) * var(--_max-column-count-spread));
            }
            @container (orientation: portrait) {
                & {
                    --_max-column-count-spread: var(--_max-column-count-portrait);
                }
                &.vertical {
                    --_max-column-count-spread: var(--_max-column-count);
                }
            }
        }
        #background {
            grid-column: 1 / -1;
            grid-row: 1 / -1;
        }
        #probe { /* where the paged #container is, in any flow (page counter) */
            grid-column: 2 / 5;
            grid-row: 2;
            visibility: hidden;
            pointer-events: none;
        }
        #container {
            grid-column: 2 / 5;
            grid-row: 2;
            overflow: hidden;
            position: relative;
        }
        :host([flow="scrolled"]) #container {
            grid-column: 1 / -1;
            grid-row: 1 / -1;
            overflow: auto;
            overflow-anchor: none; /* stack mode anchors itself (#fixStack); the browser's anchoring would add to it */
        }
        #header {
            grid-column: 3 / 4;
            grid-row: 1;
        }
        #footer {
            grid-column: 3 / 4;
            grid-row: 3;
            align-self: end;
        }
        #header, #footer {
            display: grid;
            height: var(--_margin);
        }
        /* local patch (C-085): One at a time's "Previous" / "Next" controls (colours from the host's reading theme) */
        .chapter-edge {
            display: flex;
            justify-content: center;
            padding: 32px 24px;
        }
        .chapter-edge[hidden] {
            display: none;
        }
        .chapter-edge button {
            min-height: 48px;
            max-width: 100%;
            padding: 12px 24px;
            border: 1px solid var(--er-edge-rule, currentColor);
            border-radius: 24px;
            background: transparent;
            color: var(--er-edge-fg, inherit);
            font: 500 16px/1.3 system-ui, sans-serif;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            transition: background-color 120ms;
        }
        .chapter-edge.armed button {
            background: var(--er-edge-rule, rgba(128, 128, 128, .25));
        }
        :is(#header, #footer) > * {
            display: flex;
            align-items: center;
            min-width: 0;
        }
        :is(#header, #footer) > * > * {
            width: 100%;
            overflow: hidden;
            white-space: nowrap;
            text-overflow: ellipsis;
            text-align: center;
            font-size: .75em;
            opacity: .6;
        }
        </style>
        <div id="top">
            <div id="background" part="filter"></div>
            <div id="probe"></div>
            <div id="header"></div>
            <div id="container"></div>
            <div id="footer"></div>
        </div>
        `

        this.#top = this.#root.getElementById('top')
        this.#background = this.#root.getElementById('background')
        this.#container = this.#root.getElementById('container')
        this.#header = this.#root.getElementById('header')
        this.#footer = this.#root.getElementById('footer')

        this.#observer.observe(this.#container)
        this.#container.addEventListener('scroll', () => this.dispatchEvent(new Event('scroll')))
        // Stack mode (C-079): follow the section at the top while scrolling and keep sections loaded ahead of the
        // viewport both ways (#ensureStack; slots inserted or dropped above keep the text in place, #keepStack).
        this.#container.addEventListener('scroll', () => {
            this.#scrolledAt = performance.now()
            if (!this.#stack || this.#jumping) return
            this.#followStack()
            this.#ensureStack()
            this.#captureScreen()
        })
        this.#container.addEventListener('wheel', () => this.#userScroll(), { passive: true })
        this.#container.addEventListener('scroll', debounce(() => {
            if (this.#jumping) return // the jump reports its own position
            if (this.#stack) this.#ensureStack()
            if (this.scrolled) {
                if (this.#justAnchored) this.#justAnchored = false
                else this.#afterScroll('scroll')
            }
        }, 250))

        const opts = { passive: false }
        this.addEventListener('touchstart', this.#onTouchStart.bind(this), opts)
        this.addEventListener('touchmove', this.#onTouchMove.bind(this), opts)
        this.addEventListener('touchend', this.#onTouchEnd.bind(this))
        this.addEventListener('touchcancel', this.#onTouchCancel.bind(this)) // local patch (C-094)
        this.addEventListener('load', ({ detail: { doc } }) => {
            // C-079: wheel and keys inside the book are the user's own scrolling too
            doc.addEventListener('wheel', () => this.#userScroll(), { passive: true })
            doc.addEventListener('keydown', () => this.#userScroll())
            doc.addEventListener('touchstart', this.#onTouchStart.bind(this), opts)
            doc.addEventListener('touchmove', this.#onTouchMove.bind(this), opts)
            doc.addEventListener('touchend', this.#onTouchEnd.bind(this))
            doc.addEventListener('touchcancel', this.#onTouchCancel.bind(this))
        })

        this.addEventListener('relocate', ({ detail }) => {
            if (detail.reason === 'selection') setSelectionTo(this.#anchor, 0)
            else if (detail.reason === 'navigation') {
                if (this.#anchor === 1) setSelectionTo(detail.range, 1)
                else if (typeof this.#anchor === 'number')
                    setSelectionTo(detail.range, -1)
                else setSelectionTo(this.#anchor, -1)
            }
        })
        const checkPointerSelection = debounce((range, sel) => {
            if (!sel.rangeCount) return
            const selRange = sel.getRangeAt(0)
            const backward = selectionIsBackward(sel)
            if (backward && selRange.compareBoundaryPoints(Range.START_TO_START, range) < 0)
                this.prev()
            else if (!backward && selRange.compareBoundaryPoints(Range.END_TO_END, range) > 0)
                this.next()
        }, 700)
        this.addEventListener('load', ({ detail: { doc } }) => {
            let isPointerSelecting = false
            doc.addEventListener('pointerdown', () => isPointerSelecting = true)
            doc.addEventListener('pointerup', () => isPointerSelecting = false)
            let isKeyboardSelecting = false
            doc.addEventListener('keydown', () => isKeyboardSelecting = true)
            doc.addEventListener('keyup', () => isKeyboardSelecting = false)
            doc.addEventListener('selectionchange', () => {
                if (this.scrolled) return
                const range = this.#lastVisibleRange
                if (!range) return
                const sel = doc.getSelection()
                if (!sel.rangeCount) return
                if (isPointerSelecting && sel.type === 'Range')
                    checkPointerSelection(range, sel)
                else if (isKeyboardSelecting) {
                    const selRange = sel.getRangeAt(0).cloneRange()
                    const backward = selectionIsBackward(sel)
                    if (!backward) selRange.collapse()
                    this.#scrollToAnchor(selRange)
                }
            })
            doc.addEventListener('focusin', e => this.scrolled ? null :
                // NOTE: `requestAnimationFrame` is needed in WebKit
                requestAnimationFrame(() => this.#scrollToAnchor(e.target)))
        })

        this.#mediaQueryListener = () => {
            if (!this.#view) return
            this.#background.style.background = getBackground(this.#view.document)
        }
        this.#mediaQuery.addEventListener('change', this.#mediaQueryListener)
    }
    attributeChangedCallback(name, _, value) {
        switch (name) {
            case 'flow':
                this.render()
                break
            case 'chapter-stop': // local patch (C-085): only scrolled flow stacks sections
                if (this.scrolled) this.render()
                break
            case 'gap':
                this.#top.style.setProperty('--_' + name, value)
                // LOCAL PATCH (C-082): in scrolled flow the container is full-width, so a new gap resizes nothing and
                // no resize re-renders it; render here so the side margins apply live (paged flow re-renders on resize)
                if (this.scrolled) this.render()
                break
            case 'margin':
            case 'max-block-size':
            case 'max-column-count':
                this.#top.style.setProperty('--_' + name, value)
                break
            case 'max-inline-size':
                // needs explicit `render()` as it doesn't necessarily resize
                this.#top.style.setProperty('--_' + name, value)
                this.render()
                break
        }
    }
    open(book) {
        this.bookDir = book.dir
        this.sections = book.sections
        book.transformTarget?.addEventListener('data', ({ detail }) => {
            if (detail.type !== 'text/css') return
            const w = innerWidth
            const h = innerHeight
            detail.data = Promise.resolve(detail.data).then(data => data
                // unprefix as most of the props are (only) supported unprefixed
                .replace(/(?<=[{\s;])-epub-/gi, '')
                // replace vw and vh as they cause problems with layout
                .replace(/(\d*\.?\d+)vw/gi, (_, d) => parseFloat(d) * w / 100 + 'px')
                .replace(/(\d*\.?\d+)vh/gi, (_, d) => parseFloat(d) * h / 100 + 'px')
                // `page-break-*` unsupported in columns; replace with `column-break-*`
                .replace(/page-break-(after|before|inside)\s*:/gi, (_, x) =>
                    `-webkit-column-break-${x}:`)
                .replace(/break-(after|before|inside)\s*:\s*(avoid-)?page/gi, (_, x, y) =>
                    `break-${x}: ${y ?? ''}column`))
        })
    }
    #createView(index) {
        if (!this.#preload && this.#cache.size) { // flow or writing mode left preloading: drop every slot
            for (const i of [...this.#cache.keys()]) this.#evict(i)
            this.#view = null
        }
        if (this.#preload) {
            // by view, not by #index: #display has already set #index to the target when it gets here
            const cur = [...this.#cache.values()].find(e => e.view === this.#view)
            if (cur) this.#slotStyle(cur.slot, false)
            else if (this.#view) { this.#view.destroy(); this.#view.element.remove() }
            this.#evict(index)
            return this.#newEntry(index, true).view
        }
        if (this.#view) {
            this.#view.destroy()
            this.#container.removeChild(this.#view.element)
        }
        this.#view = new View({
            container: this,
            onExpand: () => this.#scrollToAnchor(this.#anchor),
        })
        this.#container.append(this.#view.element)
        return this.#view
    }
    #beforeRender({ vertical, rtl, writingMode, background }) {
        this.#docVertical = vertical
        this.#docRtl = rtl
        this.#writingMode = writingMode
        const verticalPages = this.verticalPages
        if (verticalPages) { vertical = false; rtl = true }
        this.#vertical = vertical
        this.#rtl = rtl
        this.#top.classList.toggle('vertical', vertical)

        // set background to `doc` background
        // this is needed because the iframe does not fill the whole element
        this.#background.style.background = background

        const { width, height } = this.#container.getBoundingClientRect()
        const size = vertical ? height : width

        const style = getComputedStyle(this.#top)
        const maxInlineSize = parseFloat(style.getPropertyValue('--_max-inline-size'))
        const maxColumnCount = parseInt(style.getPropertyValue('--_max-column-count-spread'))
        const margin = parseFloat(style.getPropertyValue('--_margin'))
        this.#margin = margin

        const g = parseFloat(style.getPropertyValue('--_gap')) / 100
        // The gap will be a percentage of the #container, not the whole view.
        // This means the outer padding will be bigger than the column gap. Let
        // `a` be the gap percentage. The actual percentage for the column gap
        // will be (1 - a) * a. Let us call this `b`.
        //
        // To make them the same, we start by shrinking the outer padding
        // setting to `b`, but keep the column gap setting the same at `a`. Then
        // the actual size for the column gap will be (1 - b) * a. Repeating the
        // process again and again, we get the sequence
        //     x₁ = (1 - b) * a
        //     x₂ = (1 - x₁) * a
        //     ...
        // which converges to x = (1 - x) * a. Solving for x, x = a / (1 + a).
        // So to make the spacing even, we must shrink the outer padding with
        //     f(x) = x / (1 + x).
        // But we want to keep the outer padding, and make the inner gap bigger.
        // So we apply the inverse, f⁻¹ = -x / (x - 1) to the column gap.
        const gap = -g / (g - 1) * size

        const flow = this.getAttribute('flow')
        if (flow === 'scrolled') {
            // FIXME: vertical-rl only, not -lr
            this.setAttribute('dir', vertical ? 'rtl' : 'ltr')
            this.#top.style.padding = '0'
            const columnWidth = maxInlineSize
            // LOCAL PATCH (C-072): horizontal text gets the same side inset as paged flow (the paged #container's
            // offset in the grid plus half its column gap), so the text column, and so every line break, is the same
            // in both flows. Foliate's `gap` here (g / (1 - g) of the full width per side) made it ~2 % narrower.
            const scrolledGap = vertical ? gap : (() => {
                const probe = this.#root.getElementById('probe').getBoundingClientRect()
                const box = this.#container.getBoundingClientRect()
                return scrolledInlineInset({ probeStart: probe.left - box.left, probeSize: probe.width, g })
            })()

            this.heads = null
            this.feet = null
            this.#header.replaceChildren()
            this.#footer.replaceChildren()

            this.#lastLayout = { flow, margin, gap: scrolledGap, columnWidth }
            return { flow, margin, gap: scrolledGap, columnWidth }
        }

        const divisor = Math.min(maxColumnCount, Math.ceil(size / maxInlineSize))
        const columnWidth = (size / divisor) - gap
        this.setAttribute('dir', rtl ? 'rtl' : 'ltr')

        const marginalDivisor = vertical
            ? Math.min(2, Math.ceil(width / maxInlineSize))
            : divisor
        const marginalStyle = {
            gridTemplateColumns: `repeat(${marginalDivisor}, 1fr)`,
            gap: `${gap}px`,
            direction: this.bookDir === 'rtl' ? 'rtl' : 'ltr',
        }
        Object.assign(this.#header.style, marginalStyle)
        Object.assign(this.#footer.style, marginalStyle)
        const heads = makeMarginals(marginalDivisor, 'head')
        const feet = makeMarginals(marginalDivisor, 'foot')
        this.heads = heads.map(el => el.children[0])
        this.feet = feet.map(el => el.children[0])
        this.#header.replaceChildren(...heads)
        this.#footer.replaceChildren(...feet)

        const layout = { height, width, margin, gap, columnWidth,
            ...(this.#verticalPages ? { verticalPages: true } : {}) }
        this.#lastLayout = layout
        return layout
    }
    render() {
        if (!this.#view) return
        this.#dragAbort(true) // local patch (C-094): the surface's geometry goes with the layout
        const layout = this.#beforeRender({
            vertical: this.#docVertical,
            rtl: this.#docRtl,
            writingMode: this.#writingMode,
        })
        const mode = this.#single ? 'single' : this.#stack ? 'stack' : this.#preload ? 'paged' : null
        const switched = this.#mode !== mode
        // local patch (C-085): Continuous <-> One at a time changes no layout: the neighbours go or come (slots added
        // or dropped keep the text on screen in place, #keepStack) and nothing is re-anchored
        const stacked = m => m === 'stack' || m === 'single'
        if (switched && stacked(mode) && stacked(this.#mode) && this.#cache.has(this.#index)) {
            this.#mode = mode
            this.#afterSwap()
            return
        }
        if (switched) { // paged <-> scrolled: neighbours are laid out again for the new flow
            for (const i of [...this.#cache.keys()]) if (i !== this.#index) this.#evict(i)
            const cur = this.#cache.get(this.#index)
            this.#mode = mode
            if (cur) this.#slotStyle(cur.slot, true)
        }
        // local patch (C-079): in stack mode a re-layout (flow switch, settings, rotation) is applied like a jump to the
        // current anchor: no following or stack work until it has settled, and its anchor holds until the user scrolls
        const settling = this.#stack
        if (settling) {
            this.#jumps++
            if (switched) { this.#restoring = true; this.#noStack.clear() }
        }
        try {
            this.#view.render(layout)
            for (const e of this.#cache.values())
                if (e.view !== this.#view && e.loaded) e.view.render(layout)
            this.#scrollToAnchor(this.#anchor)
            if (switched) this.#afterSwap()
        } finally {
            // two frames (its own scroll events are past), or 200 ms if frames do not run (hidden WebView)
            if (settling) {
                let done = false
                const end = () => { if (done) return; done = true; this.#jumps--; this.#ensureStack() }
                requestAnimationFrame(() => requestAnimationFrame(end))
                setTimeout(end, 200)
            }
        }
    }
    get scrolled() {
        return this.getAttribute('flow') === 'scrolled'
    }
    get scrollProp() {
        const { scrolled } = this
        return this.#vertical ? (scrolled ? 'scrollLeft' : 'scrollTop')
            : scrolled ? 'scrollTop' : 'scrollLeft'
    }
    get sideProp() {
        const { scrolled } = this
        return this.#vertical ? (scrolled ? 'width' : 'height')
            : scrolled ? 'height' : 'width'
    }
    get size() {
        return this.#container.getBoundingClientRect()[this.sideProp]
    }
    get viewSize() {
        return this.#view.element.getBoundingClientRect()[this.sideProp]
    }
    get start() {
        return Math.abs(this.#container[this.scrollProp]) - this.#slotOffset()
    }
    get end() {
        return this.start + this.size
    }
    get page() {
        return Math.floor(((this.start + this.end) / 2) / this.size)
    }
    get pages() {
        return Math.round(this.viewSize / this.size)
    }
    scrollBy(dx, dy) {
        const delta = this.#vertical ? dy : dx
        const element = this.#container
        const { scrollProp } = this
        const [offset, a, b] = this.#scrollBounds
        const rtl = this.#rtl
        const min = rtl ? offset - b : offset - a
        const max = rtl ? offset + a : offset + b
        element[scrollProp] = Math.max(min, Math.min(max,
            element[scrollProp] + delta))
    }
    snap(vx, vy) {
        const velocity = this.#vertical ? vy : vx
        const [offset, a, b] = this.#scrollBounds
        const { start, end, pages, size } = this
        const min = Math.abs(offset) - a
        const max = Math.abs(offset) + b
        const d = velocity * (this.#rtl ? -size : size)
        const page = Math.floor(
            Math.max(min, Math.min(max, (start + end) / 2
                + (isNaN(d) ? 0 : d))) / size)

        this.#scrollToPage(page, 'snap').then(() => {
            const dir = page <= 0 ? -1 : page >= pages - 1 ? 1 : null
            if (dir) return this.#goTo({
                index: this.#adjacentIndex(dir),
                anchor: dir < 0 ? () => 1 : () => 0,
            })
        })
    }
    #onTouchStart(e) {
        this.#motion?.finish() // local patch (C-094): a touch finishes a settling turn at once
        this.#dragAbort() // a second finger, or a gesture whose end never came
        this.#userScroll()
        this.#pull = this.#single && e.touches.length === 1 ? { startFrom: null, endFrom: null } : null // C-085
        const touch = e.changedTouches[0]
        this.#touchState = {
            x: touch?.screenX, y: touch?.screenY,
            t: e.timeStamp,
            vx: 0, xy: 0,
            // local patch (host-swipe): where and when the gesture started, and whether a second finger joined
            x0: touch?.screenX, y0: touch?.screenY, t0: e.timeStamp, multi: e.touches.length > 1,
            lock: e.touches.length > 1 ? 'off' : null, // local patch (C-093): sideLock
        }
    }
    #onTouchMove(e) {
        const state = this.#touchState
        if (!state) return // local patch: a move without a start (began outside) is ignored
        if (state.pinched) return
        state.pinched = globalThis.visualViewport.scale > 1
        if (state.pinched) this.#dragAbort()
        if (this.#single && !state.pinched) { // local patch (C-085, C-093)
            this.#sideMove(e, state)
            this.#pullMove(e)
        }
        if (this.scrolled || state.pinched) return
        if (e.touches.length > 1) {
            state.multi = true
            this.#dragAbort()
            if (this.#touchScrolled) e.preventDefault()
            return
        }
        e.preventDefault()
        // local patch (host-swipe, C-094): the host's turns; the page follows a gesture locked along its axis
        if (this.hasAttribute('host-swipe')) {
            this.#sideMove(e, state)
            return
        }
        const touch = e.changedTouches[0]
        const x = touch.screenX, y = touch.screenY
        const dx = state.x - x, dy = state.y - y
        const dt = e.timeStamp - state.t
        state.x = x
        state.y = y
        state.t = e.timeStamp
        state.vx = dx / dt
        state.vy = dy / dt
        this.#touchScrolled = true
        this.scrollBy(dx, dy)
    }
    #onTouchEnd(e) {
        this.#touchScrolled = false
        if (this.#touchState?.drag) { // local patch (C-094): the page followed it, so it ends here
            if (!e.touches?.length) this.#dragRelease(e, this.#touchState)
            return
        }
        if (this.scrolled) { // local patch (C-085, C-093): a gesture is either a swipe or a pull, never both
            this.#sideEnd(e)
            this.#pullEnd(e)
            return
        }
        // local patch (host-swipe): report the finished one-finger gesture as a `swipe` event (finger displacement in
        // CSS px, positive = right/down, and its duration in ms) instead of scrollBy/snap, so every swipe can go through
        // the host's turn queue (a swipe during a turn animation or before the first layout is not lost)
        if (this.hasAttribute('host-swipe')) {
            this.#reportSwipe(e)
            return
        }

        // XXX: Firefox seems to report scale as 1... sometimes...?
        // at this point I'm basically throwing `requestAnimationFrame` at
        // anything that doesn't work
        requestAnimationFrame(() => {
            if (globalThis.visualViewport.scale === 1)
                this.snap(this.#touchState.vx, this.#touchState.vy)
        })
    }
    /** Local patch (host-swipe): the finished one-finger, unpinched gesture as a `swipe` event. */
    #reportSwipe(e) {
        const state = this.#touchState
        const touch = e?.changedTouches?.[0]
        if (!state || !touch || state.multi || state.pinched || e.touches?.length
            || globalThis.visualViewport?.scale > 1) return
        this.dispatchEvent(new CustomEvent('swipe', { detail: {
            dx: touch.screenX - state.x0, dy: touch.screenY - state.y0, dt: e.timeStamp - state.t0,
        } }))
    }
    /**
     * Local patch (C-093, C-094), a move in One at a time or in paged flow. Only a gesture that has locked along the page
     * axis is touched: it stops being a pull, the native scroll stays out of it (cancelling the first move is what does
     * that), and the page follows it. Text being selected and, in One at a time, content that scrolls sideways by itself
     * keep their gesture. A vertical one is never cancelled there, so it isn't delayed. A gesture the page can't follow
     * yet (a turn is ending) tries again on its next move; if it never can, it is reported when it ends.
     */
    #sideMove(e, state) {
        const touch = e.changedTouches?.[0]
        if (!touch || e.touches?.length > 1) {
            state.lock = 'off'
            this.#dragAbort()
            return
        }
        const dx = touch.screenX - state.x0, dy = touch.screenY - state.y0
        const main = this.#vertical ? dy : dx, cross = this.#vertical ? dx : dy
        if (state.drag) {
            if (e.cancelable) e.preventDefault()
            this.#dragMove(state, main, cross, e.timeStamp)
            return
        }
        const first = state.lock == null
        state.lock = sideLock(state.lock, main, cross)
        if (state.lock !== 'side') return
        const sel = first ? e.target.ownerDocument?.getSelection?.() : null
        if (first && ((sel && !sel.isCollapsed) || (this.#single && scrollsSideways(e.target)))) { state.lock = 'off'; return }
        this.#pull = null
        this.#armEdges(0)
        if (e.cancelable) e.preventDefault()
        if (this.#dragStart(state)) this.#dragMove(state, main, cross, e.timeStamp)
    }
    #sideEnd(e) {
        const state = this.#touchState, touch = e?.changedTouches?.[0]
        if (!this.#single || !state?.lock || !touch) return
        if (sideLock(state.lock, touch.screenX - state.x0, touch.screenY - state.y0) === 'side') this.#reportSwipe(e)
    }
    /** Local patch (C-094): the touch ended elsewhere (the system took it): the page goes back. */
    #onTouchCancel() {
        this.#dragAbort()
        this.#touchState = null
    }
    /**
     * Local patch (C-094): what the host decides about a drag: `ready(rtl, vertical)`, whether the page may follow now
     * (no turn queued, the swipe direction agrees with the pages), and `finishes({ main, cross, speed, size })`, the
     * outcome of a release. Without it no gesture is followed.
     */
    dragHost = null
    #drag = null // { p, t, dir, size, surface, released, done, resolve } from the first followed move until the turn has settled
    #lastDrag = null
    #motion = null // the running turn animation: { finish() }
    #going = 0 // jumps in progress in either flow
    /** A promise while a drag holds the page (the host's queue waits for it, or the page would be turned under the finger). */
    get dragging() { return this.#drag?.done ?? null }
    /** Diagnostics for tests (C-094): the last drag: what it moved, how far, and how it ended (`finished`: null while it runs). */
    get debugDrag() { return this.#lastDrag }
    get #dragRtl() { return !this.#vertical && this.#rtl }
    #dragStart(state) {
        if (this.#drag || this.#locked || this.#going || this.#jumping || !this.#view) return false
        if (!this.hasAttribute('animated') || document.hidden) return false // no animation: instant on release
        if (!this.dragHost?.ready(this.#rtl, this.#vertical)) return false
        const size = this.#single ? this.#container.getBoundingClientRect().width : this.size
        const d = { p: 0, t: 0, dir: 0, size, surface: null, released: false }
        d.done = new Promise(resolve => d.resolve = resolve)
        this.#drag = d
        this.#locked = true // the host's turns wait (dragging), the page counter too (busy)
        state.drag = d
        state.trail = [{ d: 0, t: state.t0 }]
        this.#lastDrag = { kinds: [], maxT: 0, active: true, finished: null }
        return true
    }
    #dragMove(state, main, cross, time) {
        const d = state.drag
        if (d.released) return
        state.main = main
        state.cross = cross
        state.trail.push({ d: main, t: time })
        trimTrail(state.trail, time)
        d.p = dragProgress(main, d.size, this.#dragRtl)
        this.#dragApply(d)
    }
    /** Puts the page where the finger is; a change of direction, or a section that has come in, changes the surface. */
    #dragApply(d) {
        const dir = Math.sign(d.p) || d.dir
        if (!dir) return
        if (!d.surface || d.surface.dir !== dir || d.surface.entry?.loaded) {
            d.surface?.reset()
            d.dir = dir
            d.surface = this.#dragSurface(dir)
            if (!d.surface) return
            this.#lastDrag.kinds.push(d.surface.kind + (d.surface.resists ? '-edge' : ''))
            d.surface.set(0)
            const s = d.surface
            s.entry?.ready?.then(() => { if (this.#drag === d && !d.released && d.surface === s) this.#dragApply(d) })
        }
        const t = Math.abs(d.p)
        d.t = d.surface.resists ? resisted(t) : t
        d.surface.set(d.t)
        this.#lastDrag.t = d.t
        this.#lastDrag.maxT = Math.max(this.#lastDrag.maxT, d.t)
        this.#lastDrag.dir = dir
    }
    /** What a drag moves toward [dir]. Where nothing can come in (yet), the page resists: `entry` is what it waits for. */
    #dragSurface(dir) {
        const index = this.#single ? this.#chapterTarget(dir) : this.#adjacentIndex(dir)
        if (!this.#single && !(dir < 0 ? this.page <= 1 : this.page >= this.pages - 2)) return this.#scrollSurface(dir)
        if (index != null) this.#preloadIndex(index, this.#single)
        const entry = index == null ? undefined : this.#cache.get(index)
        const stage = entry?.loaded ? this.#stage(entry, dir) : null
        if (stage) return stage
        const edge = this.#single ? this.#chapterSurface(null, dir) : this.#scrollSurface(dir)
        return edge && Object.assign(edge, { resists: true, target: index ?? null, entry })
    }
    #stage(entry, dir) { return this.#single ? this.#chapterSurface(entry, dir) : this.#sectionSurface(entry, dir) }
    #dragRelease(e, state) {
        const d = state.drag, touch = e?.changedTouches?.[0]
        if (d.released) return
        if (!touch) { this.#dragAbort(); return }
        const dx = touch.screenX - state.x0, dy = touch.screenY - state.y0
        this.#dragMove(state, this.#vertical ? dy : dx, this.#vertical ? dx : dy, e.timeStamp)
        const toward = slideSign(d.dir, this.#dragRtl) // the way the finger goes for this turn
        void this.#dragSettle(d, !!this.dragHost?.finishes({
            main: (state.main ?? 0) * toward, cross: state.cross ?? 0, speed: fingerSpeed(state.trail) * toward, size: d.size,
        }))
    }
    /** The drag is over or dropped: the page goes on to the end of the turn, or back; then the host's turns may run. */
    async #dragSettle(d, finish) {
        d.released = true
        try {
            let s = d.surface
            if (finish && s?.resists) { // the drag waits at the edge; it finishes when the section is there
                if (s.target == null) finish = false
                else {
                    await s.entry?.ready
                    const entry = this.#cache.get(s.target), { target } = s
                    s.reset()
                    s = d.surface = entry?.loaded ? this.#stage(entry, d.dir) : null
                    if (!s) { await this.#goTo({ index: target, anchor: d.dir < 0 ? () => 1 : () => 0 }); return }
                    s.set(d.t)
                }
            }
            if (!s) return
            const end = finish ? 1 : 0
            await this.#play(d.t, end, settleMs(Math.abs(end - d.t)), s.set)
            if (!finish) s.reset()
            else if (!(await s.finish())) await this.#goTo({ index: s.index, anchor: d.dir < 0 ? () => 1 : () => 0 })
        } catch (e) {
            console.warn('turn failed', e)
        } finally {
            this.#dragEnd(d, finish)
        }
    }
    #dragEnd(d, finished) {
        if (this.#drag !== d) return
        this.#drag = null
        this.#locked = false
        Object.assign(this.#lastDrag, { active: false, finished })
        d.resolve()
    }
    /** Drops a drag the finger still holds: the page goes back, at once if [now]. */
    #dragAbort(now = false) {
        const d = this.#drag
        if (!d || d.released) return
        if (!now) { void this.#dragSettle(d, false); return }
        d.released = true
        d.surface?.reset()
        this.#dragEnd(d, false)
    }
    // allows one to process rects as if they were LTR and horizontal
    #getRectMapper() {
        if (this.scrolled) {
            const size = this.viewSize
            const margin = this.#margin
            return this.#vertical
                ? ({ left, right }) =>
                    ({ left: size - right - margin, right: size - left - margin })
                : ({ top, bottom }) => ({ left: top + margin, right: bottom + margin })
        }
        const pxSize = this.pages * this.size
        return this.#rtl
            ? ({ left, right }) =>
                ({ left: pxSize - right, right: pxSize - left })
            : this.#vertical
                ? ({ top, bottom }) => ({ left: top, right: bottom })
                : f => f
    }
    async #scrollToRect(rect, reason) {
        if (this.scrolled) {
            const offset = this.#getRectMapper()(rect).left - this.#margin
            return this.#scrollTo(offset, reason)
        }
        const offset = this.#getRectMapper()(rect).left
        return this.#scrollToPage(Math.floor(offset / this.size) + (this.#rtl ? -1 : 1), reason)
    }
    async #scrollTo(offset, reason, smooth) {
        const element = this.#container
        const { scrollProp, size } = this
        if (this.#stack) { // offsets are relative to the current section; the scroller holds the whole stack
            offset = Math.max(0, offset + this.#slotOffset())
            this.#stackTop = this.#slotOffset()
        }
        if (element[scrollProp] === offset) {
            this.#scrollBounds = [offset, this.atStart ? 0 : size, this.atEnd ? 0 : size]
            this.#afterScroll(reason)
            return
        }
        // FIXME: vertical-rl only, not -lr
        if (this.scrolled && this.#vertical) offset = -offset
        if ((reason === 'snap' || smooth) && this.hasAttribute('animated')) return this.#play(
            element[scrollProp], offset, 300,
            x => element[scrollProp] = x,
        ).then(() => {
            this.#scrollBounds = [offset, this.atStart ? 0 : size, this.atEnd ? 0 : size]
            this.#afterScroll(reason)
        })
        else {
            element[scrollProp] = offset
            this.#scrollBounds = [offset, this.atStart ? 0 : size, this.atEnd ? 0 : size]
            this.#afterScroll(reason)
        }
    }
    async #scrollToPage(page, reason, smooth) {
        const offset = this.size * (this.#rtl ? -page : page)
        return this.#scrollTo(offset, reason, smooth)
    }
    async scrollToAnchor(anchor, select) {
        return this.#scrollToAnchor(anchor, select ? 'selection' : 'navigation')
    }
    async #scrollToAnchor(anchor, reason = 'anchor') {
        this.#anchor = anchor
        const rects = uncollapse(anchor)?.getClientRects?.()
        // if anchor is an element or a range
        if (rects) {
            // when the start of the range is immediately after a hyphen in the
            // previous column, there is an extra zero width rect in that column
            const rect = Array.from(rects)
                .find(r => r.width > 0 && r.height > 0) || rects[0]
            if (!rect) return
            await this.#scrollToRect(rect, reason)
            return
        }
        // if anchor is a fraction
        if (this.scrolled) {
            // the end of a section is its last screen (in stack mode the next section starts right below)
            await this.#scrollTo(Math.min(anchor * this.viewSize, Math.max(0, this.viewSize - this.size)), reason)
            return
        }
        const { pages } = this
        if (!pages) return
        const textPages = pages - 2
        const newPage = Math.round(anchor * (textPages - 1))
        await this.#scrollToPage(newPage + 1, reason)
    }
    #getVisibleRange() {
        if (this.scrolled) return getVisibleRange(this.#view.document,
            this.start + this.#margin, this.end - this.#margin, this.#getRectMapper())
        const size = this.#rtl ? -this.size : this.size
        return getVisibleRange(this.#view.document,
            this.start - size, this.end - size, this.#getRectMapper())
    }
    #afterScroll(reason) {
        const range = this.#getVisibleRange()
        this.#lastVisibleRange = range
        // don't set new anchor if relocation was to scroll to anchor
        if (reason !== 'selection' && reason !== 'navigation' && reason !== 'anchor')
            this.#anchor = range
        else this.#justAnchored = true

        const index = this.#index
        const detail = { reason, range, index }
        // local patch (C-085): above the section (its "Previous" control on screen) is its start
        if (this.scrolled) detail.fraction = Math.max(0, this.start) / this.viewSize
        else if (this.pages > 0) {
            const { page, pages } = this
            this.#header.style.visibility = page > 1 ? 'visible' : 'hidden'
            detail.fraction = (page - 1) / (pages - 2)
            detail.size = 1 / (pages - 2)
        }
        this.dispatchEvent(new CustomEvent('relocate', { detail }))
    }
    async #display(promise) {
        const { index, src, anchor, onLoad, select } = await promise
        const prevIndex = this.#index
        this.#index = index
        const hasFocus = this.#view?.document?.hasFocus()
        if (src) {
            const view = this.#createView(index)
            const afterLoad = doc => {
                if (doc.head) {
                    const $styleBefore = doc.createElement('style')
                    doc.head.prepend($styleBefore)
                    const $style = doc.createElement('style')
                    doc.head.append($style)
                    this.#styleMap.set(doc, [$styleBefore, $style])
                }
                onLoad?.({ doc, index })
            }
            const beforeRender = this.#beforeRender.bind(this)
            await view.load(src, afterLoad, beforeRender)
            if (this.#preload && this.#cache.get(index)?.view !== view) return // dropped by a later jump while it loaded
            this.dispatchEvent(new CustomEvent('create-overlayer', {
                detail: {
                    doc: view.document, index,
                    attach: overlayer => view.overlayer = overlayer,
                },
            }))
            this.#view = view
            if (this.#preload) this.#cache.get(index).loaded = true
        }
        await this.scrollToAnchor((typeof anchor === 'function'
            ? anchor(this.#view.document) : anchor) ?? 0, select)
        if (hasFocus) this.focusView()
        if (src) this.#afterSwap()
    }
    #canGoToIndex(index) {
        return index >= 0 && index <= this.sections.length - 1
    }
    async #goTo(target) {
        // local patch (C-080): every deliberate navigation (contents, search hit, link, bookmark, highlight, slider,
        // resume) comes here in both flows; in stack mode the old position's sections go first, so nothing restores it
        if (!this.#stack) return this.#goToTarget(target)
        return this.#navigate(async () => {
            // (side slots stay: they hold nothing the old position could restore, and #afterSwap drops those out of reach)
            for (const [i, e] of [...this.#cache]) if (i !== target.index && i !== this.#index && !e.side) this.#evict(i)
            if (target.index !== this.#index && this.#cache.has(this.#index) && this.#cache.get(target.index)?.loaded) {
                this.#evict(this.#index) // the target is laid out already: it takes over alone
            }
            return this.#goToTarget(target)
        })
    }
    async #goToTarget(target) {
        this.#going++ // local patch (C-094): no drag starts on a page that is being replaced
        try { await this.#goToTargetNow(target) } finally { this.#going-- }
    }
    async #goToTargetNow({ index, anchor, select}) {
        if (index === this.#index) await this.#display({ index, anchor, select })
        else if (this.#preload && this.#cache.has(index) && await this.#swapTo(index, anchor, select)) return
        else {
            const oldIndex = this.#index
            const preload = this.#preload
            const onLoad = detail => {
                if (!preload) this.sections[oldIndex]?.unload?.()
                this.setStyles(this.#styles)
                this.dispatchEvent(new CustomEvent('load', { detail }))
            }
            await this.#display(Promise.resolve(this.sections[index].load())
                .then(src => ({ index, src, anchor, onLoad, select }))
                .catch(e => {
                    console.warn(e)
                    console.warn(new Error(`Failed to load section ${index}`))
                    return {}
                }))
        }
    }
    async goTo(target) {
        if (this.#locked) return
        const resolved = await target
        if (this.#canGoToIndex(resolved.index)) return this.#goTo(resolved)
    }
    #scrollPrev(distance) {
        this.#userScroll()
        if (!this.#view) return true
        if (this.#stack) { // one screen up, across sections; only the very top goes to an unloaded section
            if (this.#container.scrollTop > 0) return this.#scrollTo(this.start - (distance ?? this.size), null, true)
            return true
        }
        if (this.scrolled) {
            if (this.start > 0) return this.#scrollTo(
                Math.max(0, this.start - (distance ?? this.size)), null, true)
            return true
        }
        if (this.atStart) return
        const page = this.page - 1
        return this.#scrollToPage(page, 'page', true).then(() => page <= 0)
    }
    #scrollNext(distance) {
        this.#userScroll()
        if (!this.#view) return true
        if (this.#stack) {
            const el = this.#container
            if (el.scrollTop + el.clientHeight < el.scrollHeight - 2) return this.#scrollTo(this.start + (distance ?? this.size), null, true)
            return true
        }
        if (this.scrolled) {
            if (this.viewSize - this.end > 2) return this.#scrollTo(
                Math.min(this.viewSize, distance ? this.start + distance : this.end), null, true)
            return true
        }
        if (this.atEnd) return
        const page = this.page + 1
        const pages = this.pages
        return this.#scrollToPage(page, 'page', true).then(() => page >= pages - 1)
    }
    get atStart() {
        return this.#adjacentIndex(-1) == null && this.page <= 1
    }
    get atEnd() {
        return this.#adjacentIndex(1) == null && this.page >= this.pages - 2
    }
    #adjacentIndex(dir) {
        for (let index = this.#index + dir; this.#canGoToIndex(index); index += dir)
            if (this.sections[index]?.linear !== 'no') return index
    }
    async #turnPage(dir, distance) {
        if (this.#locked) return
        this.#locked = true
        try { await this.#turnPageLocked(dir, distance) } finally { this.#locked = false }
    }
    async #turnPageLocked(dir, distance) {
        const prev = dir === -1
        if (this.#preload && this.#view && !this.scrolled) { // no view yet: the first `next()` of an open displays section 0
            const target = this.#adjacentIndex(dir)
            const atEdge = prev ? this.page <= 1 : this.page >= this.pages - 2
            if (atEdge && target != null && this.#cache.has(target)) {
                // local patch (C-071): with `animated`, the neighbour slides in like any other page turn
                const to = this.#cache.get(target)
                const slide = this.hasAttribute('animated') && !document.hidden && !this.#vertical
                    && to.loaded // a neighbour still loading swaps in as before (its size would be stale)
                    ? this.#sectionSurface(to, dir) : null
                if (slide ? await this.#slide(slide) : await this.#swapTo(target, prev ? () => 1 : () => 0)) return
            }
        }
        const shouldGo = await (prev ? this.#scrollPrev(distance) : this.#scrollNext(distance))
        if (shouldGo) await this.#goTo({
            index: this.#adjacentIndex(dir),
            anchor: prev ? () => 1 : () => 0,
        })
        if (shouldGo || !this.hasAttribute('animated')) await wait(100)
    }
    #lastSlide = []
    #sliding = null // { from, to } while a boundary slide runs
    /** Diagnostics for tests (C-071): the frames of the last boundary slide, `{ t, old, next }` offsets in CSS px. */
    get debugSlide() { return this.#lastSlide }
    /**
     * Diagnostics for tests (C-071), null when no slide runs: the on-screen size of the container and of both pages'
     * iframes, and whether the old page is still in the document (a test samples it on every frame of a slide).
     */
    get debugSlideGeometry() {
        const s = this.#sliding
        if (!s) return null
        const rect = e => { const r = e?.getBoundingClientRect(); return r ? { left: r.left, right: r.right, height: r.height } : null }
        return { container: rect(this.#container), old: rect(s.from.view.element.querySelector('iframe')),
            next: rect(s.to.view.element.querySelector('iframe')), oldConnected: s.from.slot.isConnected }
    }
    /** Local patch (C-094): plays a turn animation; a touch can finish it (#motion). */
    async #play(a, b, ms, render) {
        const motion = {}
        this.#motion = motion
        try { await animate(a, b, ms, easeOutQuad, render, motion) } finally { if (this.#motion === motion) this.#motion = null }
    }
    /** Local patch (C-071, C-094): a whole turn on surface [s], for an input that has no finger to follow. */
    async #slide(s) {
        s.set(0)
        await this.#play(0, 1, SLIDE_MS, s.set)
        return s.finish()
    }
    #track(from, to) {
        this.#sliding = { from, to }
        return this.#lastSlide = []
    }
    /** Local patch (C-094): a turn inside the section: the next page is already beside this one in the same scroller. */
    #scrollSurface(dir) {
        const el = this.#container, { scrollProp, size } = this
        const page = this.page
        const unit = this.#rtl ? -size : size
        const base = page * unit
        return {
            kind: 'page', dir,
            set: t => { el[scrollProp] = base + dir * t * unit },
            finish: async () => { await this.#scrollToPage(page + dir, 'page'); return true },
            reset: () => { el[scrollProp] = base },
        }
    }
    /**
     * Local patch (C-071, C-094): a turn into the preloaded section [to]. It is only staged: it shows the page it opens at
     * beside the current page, and both move one page in the turn's direction, the current section staying current. The
     * swap is the last step, in the frame of the last move (#swapTo is synchronous up to the scroll for a loaded section),
     * so a cancel just takes the stage down. Neither slot takes touches: one landing meanwhile goes to the paginator.
     */
    #sectionSurface(to, dir) {
        const from = this.#cache.get(this.#index)
        const el = this.#container, { scrollProp, size } = this
        if (!from || !(size > 0)) return null
        const pages = Math.round(to.view.element.getBoundingClientRect()[this.sideProp] / size)
        const page = dir > 0 ? 1 : Math.max(1, pages - 2)
        const move = this.#vertical ? 'translateY' : 'translateX'
        const scrolled = el[scrollProp] // a slot at inset 0 scrolls with the container
        const sign = slideSign(dir, this.#dragRtl)
        const frames = this.#track(from, to)
        to.slot.style.cssText = 'position: absolute; inset: 0; overflow: hidden; pointer-events: none'
        to.slot[scrollProp] = this.#rtl ? -page * size : page * size
        from.slot.style.pointerEvents = 'none'
        const takeDown = () => {
            this.#sliding = null
            to.slot[scrollProp] = 0
            from.slot.style.transform = ''
            from.slot.style.pointerEvents = ''
        }
        return {
            kind: 'section', dir, index: to.index,
            set: t => {
                const f = slideFrame(t, sign, size)
                from.slot.style.transform = `${move}(${f.old}px)`
                to.slot.style.transform = `${move}(${scrolled + f.next}px)`
                frames.push({ t, old: f.old, next: f.next, at: performance.now() }) // at: frame time (C-071 test hook)
            },
            finish: async () => {
                takeDown()
                if (this.#cache.get(to.index) === to && await this.#swapTo(to.index, dir < 0 ? () => 1 : () => 0)) return true
                this.#slotStyle(to.slot, false)
                return false
            },
            reset: () => {
                takeDown()
                this.#slotStyle(to.slot, false)
            },
        }
    }
    /**
     * Local patch (C-093): the chapter after ([dir] 1, at its top) or before (-1, at its end) comes in the way a page turn
     * does, then it is opened like the Next and Previous controls open it. The book's first and last chapters have
     * nothing on that side: nothing happens, as at the book's ends in paged flow.
     */
    async turnChapter(dir) {
        if (this.#locked) return
        this.#locked = true
        try {
            const index = this.#chapterTarget(dir)
            if (index == null || !this.#single) return
            if (this.hasAttribute('animated') && !document.hidden) {
                this.#preloadIndex(index, true) // only if the swipe came before its slot did
                const entry = this.#cache.get(index)
                await entry?.ready
                if (this.#chapterTarget(dir) !== index) return // a jump got in first
                const slide = entry?.loaded && this.#cache.get(index) === entry ? this.#chapterSurface(entry, dir) : null
                if (slide && await this.#slide(slide)) return
            }
            await this.#goTo({ index, anchor: dir > 0 ? 0 : 1 })
        } finally { this.#locked = false }
    }
    /**
     * Local patch (C-093, C-094): the boundary slide of scrolled flow. The stack stays as it is, scrolled where it was, and
     * moves with its edge controls; [to] (a side slot) is staged over the viewport showing the screen it opens at (the next
     * chapter its top, the previous one its end). Without [to] (nothing to bring in) only the stack moves. The swap is the
     * last step, in the frame of the last move (#goTo is synchronous up to the scroll for a loaded section), so the
     * stage's picture and the stack's are one; a jump that has to wait for a fling first keeps the stage on screen.
     */
    #chapterSurface(to, dir) {
        const el = this.#container
        const from = this.#cache.get(this.#index)
        const width = el.getBoundingClientRect().width
        if (!from || !(width > 0)) return null
        const moving = [...this.#stacked.map(e => e.slot), this.#edgeHead, this.#edgeFoot]
        const anchor = dir > 0 ? 0 : 1
        if (to) {
            const height = el.clientHeight
            to.slot.style.cssText = `position: absolute; top: ${el.scrollTop}px; left: 0; width: 100%; height: ${height}px; overflow: hidden; pointer-events: none`
            to.slot.scrollTop = anchor ? Math.max(0, to.view.element.getBoundingClientRect().height - height) : 0
        }
        el.style.overflow = 'hidden' // nothing scrolls under the slide, and the moving slots add no scroll range
        const sign = slideSign(dir, this.#rtl)
        const frames = to ? this.#track(from, to) : null
        // a touch during the slide lands on the paginator, not on a frame the swap destroys: its swipe must not be lost
        for (const m of moving) m.style.pointerEvents = 'none'
        const clear = () => {
            this.#sliding = null
            for (const m of moving) { m.style.transform = ''; m.style.pointerEvents = '' }
            if (to) to.slot.style.transform = ''
        }
        return {
            kind: 'chapter', dir, index: to?.index,
            set: t => {
                const f = slideFrame(t, sign, width)
                for (const m of moving) m.style.transform = `translateX(${f.old}px)`
                if (!to) return
                to.slot.style.transform = `translateX(${f.next}px)`
                frames.push({ t, old: f.old, next: f.next, at: performance.now() })
            },
            finish: async () => {
                const going = this.#goTo({ index: to.index, anchor })
                if (this.#index === to.index) clear() // else it waits for a fling to end, the stage on screen
                await going
                clear()
                return true
            },
            reset: () => {
                clear()
                if (to) {
                    to.slot.scrollTop = 0
                    this.#slotStyle(to.slot, false, true)
                }
                if (!this.#jumping) el.style.overflow = ''
            },
        }
    }
    prev(distance) {
        return this.#turnPage(-1, distance)
    }
    next(distance) {
        return this.#turnPage(1, distance)
    }
    prevSection() {
        return this.goTo({ index: this.#adjacentIndex(-1) })
    }
    nextSection() {
        return this.goTo({ index: this.#adjacentIndex(1) })
    }
    firstSection() {
        const index = this.sections.findIndex(section => section.linear !== 'no')
        return this.goTo({ index })
    }
    lastSection() {
        const index = this.sections.findLastIndex(section => section.linear !== 'no')
        return this.goTo({ index })
    }
    getContents() {
        if (this.#view) return [{
            index: this.#index,
            overlayer: this.#view.overlayer,
            doc: this.#view.document,
        // loaded hidden neighbours too (current first), so view.js draws annotations and search
        // results into them; otherwise a preloaded section shows no highlights after the swap.
        }, ...[...this.#cache.values()].filter(e => e.view !== this.#view && e.loaded).map(e => ({
            index: e.index, overlayer: e.view.overlayer, doc: e.view.document,
        }))]
        return []
    }
    setStyles(styles) {
        this.#styles = styles
        for (const e of this.#cache.values()) if (e.view !== this.#view && e.view.document) {
            this.#applyStyles(e.view.document)
            e.view.document.fonts?.ready?.then(() => e.view.expand())
        }
        const $$styles = this.#styleMap.get(this.#view?.document)
        if (!$$styles) return
        const [$beforeStyle, $style] = $$styles
        if (Array.isArray(styles)) {
            const [beforeStyle, style] = styles
            $beforeStyle.textContent = beforeStyle
            $style.textContent = style
        } else $style.textContent = styles

        // NOTE: needs `requestAnimationFrame` in Chromium
        requestAnimationFrame(() =>
            this.#background.style.background = getBackground(this.#view.document))

        // needed because the resize observer doesn't work in Firefox
        this.#view?.document?.fonts?.ready?.then(() => this.#view.expand())
    }
    focusView() {
        this.#view.document.defaultView.focus()
    }
    destroy() {
        this.#dragAbort(true)
        this.#motion?.finish()
        this.#observer.unobserve(this)
        for (const i of [...this.#cache.keys()]) if (i !== this.#index) this.#evict(i)
        this.#view.destroy()
        this.#view = null
        this.sections[this.#index]?.unload?.()
        this.#mediaQuery.removeEventListener('change', this.#mediaQueryListener)
    }
}

customElements.define('foliate-paginator', Paginator)
