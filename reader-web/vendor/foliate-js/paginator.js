// LOCAL PATCH (M9 C-079/C-080, see PATCHES.md): stack mode (scrolled flow) is driven by the viewport: a deliberate
// jump resets the stack and is never "corrected" back (#jumping), layout changes restore the jump target only until the
// user scrolls (#restoring) and otherwise keep the text on screen in place (#holdScreen), and sections are loaded ahead
// of the viewport in both directions, through short sections (#ensureStack).
// LOCAL PATCH (M9 C-071, see PATCHES.md): a turn into a preloaded neighbour section slides (`animated`) instead of
// swapping instantly: #slideIn moves the old and the new slot side by side over 300 ms.
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
export const slideSign = (dir, rtl) => (dir > 0 ? -1 : 1) * (rtl ? -1 : 1)
export const slideFrame = (t, sign, width) => ({ old: sign * width * t + 0, next: -sign * width * (1 - t) + 0 })
const animate = (a, b, duration, ease, render) => new Promise(resolve => {
    let start
    const step = now => {
        if (document.hidden) {
            render(lerp(a, b, 1))
            return resolve()
        }
        start ??= now
        const fraction = Math.min(1, (now - start) / duration)
        render(lerp(a, b, ease(fraction)))
        if (fraction < 1) requestAnimationFrame(step)
        else resolve()
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
    destroy() {
        if (this.document) this.#observer.unobserve(this.document.body)
    }
}

// NOTE: everything here assumes the so-called "negative scroll type" for RTL
export class Paginator extends HTMLElement {
    static observedAttributes = [
        'flow', 'gap', 'margin',
        'max-inline-size', 'max-block-size', 'max-column-count',
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
    #mode = null // 'paged' | 'stack' | null: what the cached slots are laid out for
    #stackTop = 0 // offsetTop of the current slot when the scroll position was last set or fixed
    #slotStyle(slot, current) {
        slot.style.cssText = this.#stack ? ''
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
        const rel = this.#container.scrollTop - cur.slot.offsetTop
        mutate()
        if (this.#cache.get(this.#index) !== cur) { this.#fixStack(); return }
        this.#container.scrollTop = cur.slot.offsetTop + rel
        this.#stackTop = cur.slot.offsetTop
    }
    /** Keeps what is on screen in place when slots above the current one change height. */
    #fixStack() {
        if (!this.#stack) return
        const top = this.#slotOffset()
        const delta = top - this.#stackTop
        if (delta) this.#container.scrollTop += delta
        this.#stackTop = top
    }
    // local patch (C-079/C-080): stack-mode stability
    #jumps = 0 // deliberate jumps or re-layouts being applied (they can overlap): no following, no 'scroll' relocates, no stack work
    get #jumping() { return this.#jumps > 0 }
    #restoring = false // since the last deliberate jump the user has not scrolled: layout changes re-apply its anchor
    #screen = null // { view, el, top }: the element at the top of the viewport and its offset there (user scrolling)
    // after the last stacked slot: one screen of room while more sections follow, so a jump to a short section (or the
    // end of one) is not clamped by the scroll range into the section before it (C-080)
    #stackPad = Object.assign(document.createElement('div'), { className: 'stack-pad' })
    #noStack = new Set() // sections that failed to load into the stack (error, other writing mode): its end there
    #nextStacked(index, dir) {
        const i = this.#linearFrom(index, dir)
        return i == null || this.#noStack.has(i) ? null : i
    }
    #padStack() {
        const last = Math.max(-1, ...this.#cache.keys())
        const vh = this.#container.clientHeight
        let h = 0
        if (this.#stack && last >= 0) {
            // more to come: a screen of room; the book's (or the stack's) last section: just enough that a section
            // shorter than the screen can still be scrolled to the top, and no blank screen past the end
            if (this.#nextStacked(last, 1) != null) h = vh
            else h = Math.max(0, vh - (this.#cache.get(last)?.slot.offsetHeight ?? vh))
        }
        h = `${h}px`
        if (this.#stackPad.style.height !== h) this.#stackPad.style.height = h
        if (this.#stackPad.parentNode !== this.#container) this.#container.append(this.#stackPad)
    }
    /** Runs a deliberate navigation [f]; the flags drop two frames after it (its own scroll events are past by then). */
    async #navigate(f) {
        this.#jumps++
        this.#restoring = true
        this.#screen = null
        this.#noStack.clear()
        try { return await f() } finally {
            await new Promise(r => { requestAnimationFrame(() => requestAnimationFrame(r)); setTimeout(r, 200) })
            this.#jumps--
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
        this.#screen = el ? { view: e.view, el, top: this.#screenTop(e.view, el) } : null
    }
    /** A slot's height changed: the text on screen stays where it is (the captured element, else the current slot). */
    #holdScreen() {
        const s = this.#screen
        if (!s || !s.el.isConnected || this.#cache.get(this.#index)?.view !== s.view) { this.#fixStack(); return }
        const now = this.#screenTop(s.view, s.el)
        if (now != null && Math.abs(now - s.top) > 0.5) this.#container.scrollTop += now - s.top
        this.#stackTop = this.#slotOffset()
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
        const entries = [...this.#cache.values()].sort((a, b) => a.index - b.index)
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
    /** Stack mode: the slot at the top of the viewport becomes the current section (no scrolling). */
    #followStack() {
        if (!this.#stack || this.#jumping) return false
        const y = this.#container.scrollTop + 1
        for (const e of this.#cache.values()) {
            const { offsetTop, offsetHeight } = e.slot
            if (e.loaded && e.index !== this.#index && y >= offsetTop && y < offsetTop + offsetHeight) {
                this.#view = e.view
                this.#index = e.index
                this.#stackTop = offsetTop
                this.#background.style.background = getBackground(e.view.document)
                return true
            }
        }
        return false
    }
    #newEntry(index, current) {
        const slot = document.createElement('div')
        this.#slotStyle(slot, current)
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
        const after = [...this.#cache.values()].filter(e => e.index > index).sort((a, b) => a.index - b.index)[0]
        this.#padStack()
        this.#keepStack(() => this.#container.insertBefore(slot, after?.slot ?? this.#stackPad))
        this.#padStack()
        const entry = { index, slot, view, loaded: false }
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
    #preloadIndex(index) {
        if (index == null || this.#cache.has(index)) return
        const entry = this.#newEntry(index, false)
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
            if (entry.mismatch) throw new Error(`section ${index}: other writing mode, not preloaded`)
            this.dispatchEvent(new CustomEvent('create-overlayer', {
                detail: { doc: entry.view.document, index, attach: o => entry.view.overlayer = o },
            }))
            entry.loaded = true
            this.#ensureStack() // stack mode: the next one, if the text below is still short
        })().catch(e => {
            if (!entry.mismatch) console.warn(e)
            if (this.#stack) this.#noStack.add(index) // not retried on every scroll event (C-079 review)
            if (this.#cache.get(index) === entry) this.#evict(index)
        })
    }
    /** [below]: only preload the next section (safe while the user scrolls; nothing above moves). */
    #afterSwap(below = false) {
        if (!this.#preload) return
        if (below) { this.#preloadIndex(this.#adjacentIndex(1)); return }
        const keep = new Set([this.#index, this.#adjacentIndex(-1), this.#adjacentIndex(1)])
        for (const i of [...this.#cache.keys()]) if (!keep.has(i)) this.#evict(i)
        this.#preloadIndex(this.#adjacentIndex(1))
        this.#preloadIndex(this.#adjacentIndex(-1))
    }
    async #swapTo(index, anchor, select, deferAfterSwap = false) {
        const entry = this.#cache.get(index)
        if (!entry.loaded) await entry.ready
        if (!entry.loaded || this.#cache.get(index) !== entry) return false
        const cur = this.#cache.get(this.#index)
        if (cur) this.#slotStyle(cur.slot, false)
        this.#slotStyle(entry.slot, true)
        this.#view = entry.view
        this.#index = index
        this.#background.style.background = getBackground(entry.view.document)
        // synchronous for fraction anchors: the target page is scrolled into place before the next frame
        await this.scrollToAnchor((typeof anchor === 'function'
            ? anchor(entry.view.document) : anchor) ?? 0, select)
        // local patch (C-071): a boundary slide preloads/evicts after it ends (no layout work during it, `from` kept)
        if (!deferAfterSwap) this.#afterSwap()
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
                return { index: e?.index, top: el.offsetTop, height: el.offsetHeight, loaded: e?.loaded, css: e ? undefined : el.style.cssText.slice(0, 90), raw: el === this.#view?.element }
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
        this.addEventListener('load', ({ detail: { doc } }) => {
            // C-079: wheel and keys inside the book are the user's own scrolling too
            doc.addEventListener('wheel', () => this.#userScroll(), { passive: true })
            doc.addEventListener('keydown', () => this.#userScroll())
            doc.addEventListener('touchstart', this.#onTouchStart.bind(this), opts)
            doc.addEventListener('touchmove', this.#onTouchMove.bind(this), opts)
            doc.addEventListener('touchend', this.#onTouchEnd.bind(this))
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
            case 'gap':
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

            this.heads = null
            this.feet = null
            this.#header.replaceChildren()
            this.#footer.replaceChildren()

            this.#lastLayout = { flow, margin, gap, columnWidth }
            return { flow, margin, gap, columnWidth }
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
        const layout = this.#beforeRender({
            vertical: this.#docVertical,
            rtl: this.#docRtl,
            writingMode: this.#writingMode,
        })
        const mode = this.#stack ? 'stack' : this.#preload ? 'paged' : null
        const switched = this.#mode !== mode
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
        this.#userScroll()
        const touch = e.changedTouches[0]
        this.#touchState = {
            x: touch?.screenX, y: touch?.screenY,
            t: e.timeStamp,
            vx: 0, xy: 0,
            // local patch (host-swipe): where and when the gesture started, and whether a second finger joined
            x0: touch?.screenX, y0: touch?.screenY, t0: e.timeStamp, multi: e.touches.length > 1,
        }
    }
    #onTouchMove(e) {
        const state = this.#touchState
        if (!state) return // local patch: a move without a start (began outside) is ignored
        if (state.pinched) return
        state.pinched = globalThis.visualViewport.scale > 1
        if (this.scrolled || state.pinched) return
        if (e.touches.length > 1) {
            state.multi = true
            if (this.#touchScrolled) e.preventDefault()
            return
        }
        e.preventDefault()
        // local patch (host-swipe): the page does not follow the finger; the host turns it when the finger lifts
        if (this.hasAttribute('host-swipe')) return
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
        if (this.scrolled) return
        // local patch (host-swipe): report the finished one-finger gesture as a `swipe` event (finger displacement in
        // CSS px, positive = right/down, and its duration in ms) instead of scrollBy/snap, so every swipe can go through
        // the host's turn queue (a swipe during a turn animation or before the first layout is not lost)
        if (this.hasAttribute('host-swipe')) {
            const state = this.#touchState
            const touch = e?.changedTouches?.[0]
            if (!state || !touch || state.multi || state.pinched || e.touches?.length
                || globalThis.visualViewport?.scale > 1) return
            this.dispatchEvent(new CustomEvent('swipe', { detail: {
                dx: touch.screenX - state.x0, dy: touch.screenY - state.y0, dt: e.timeStamp - state.t0,
            } }))
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
        if ((reason === 'snap' || smooth) && this.hasAttribute('animated')) return animate(
            element[scrollProp], offset, 300, easeOutQuad,
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
        if (this.scrolled) detail.fraction = this.start / this.viewSize
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
            for (const i of [...this.#cache.keys()]) if (i !== target.index && i !== this.#index) this.#evict(i)
            if (target.index !== this.#index && this.#cache.has(this.#index) && this.#cache.get(target.index)?.loaded) {
                this.#evict(this.#index) // the target is laid out already: it takes over alone
            }
            return this.#goToTarget(target)
        })
    }
    async #goToTarget({ index, anchor, select}) {
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
                const from = this.#cache.get(this.#index)
                const slide = this.hasAttribute('animated') && !document.hidden && !this.#vertical && from
                    && this.#cache.get(target).loaded // a neighbour still loading swaps in as before (its rect would be stale)
                const before = slide ? from.view.element.getBoundingClientRect() : null
                if (await this.#swapTo(target, prev ? () => 1 : () => 0, undefined, !!before)) {
                    try {
                        if (before) await this.#slideIn(from, this.#cache.get(target), before, dir)
                    } finally {
                        if (before) this.#afterSwap()
                    }
                    return
                }
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
    /**
     * Local patch (C-071): [to] has just been swapped in (in flow, scrolled to its page); [from] is the slot that was on
     * screen, its view element at [before] (viewport rect) before the swap. The old slot is pinned where it was (absolute,
     * shifted back to its old screen position), then both move one container width in the turn's direction, eased like
     * an in-section turn. Both documents are laid out already (preloaded), so every frame shows real pages.
     */
    async #slideIn(from, to, before, dir) {
        const width = this.#container.getBoundingClientRect().width
        if (!to || !(width > 0)) return
        const old = from.slot
        // the container's box (inset 0: full height, so the view's height: 100% holds), its view visible past it
        old.style.cssText = 'position: absolute; inset: 0; overflow: visible; pointer-events: none; z-index: 1'
        const base = before.left - from.view.element.getBoundingClientRect().left
        const sign = slideSign(dir, this.#rtl)
        const frames = []
        this.#lastSlide = frames
        this.#sliding = { from, to }
        const set = t => {
            const f = slideFrame(t, sign, width)
            old.style.transform = `translateX(${base + f.old}px)`
            to.slot.style.transform = `translateX(${f.next}px)`
            frames.push({ t, old: f.old, next: f.next })
        }
        try {
            set(0)
            await animate(0, 1, 300, easeOutQuad, set)
        } finally {
            this.#sliding = null
            to.slot.style.transform = ''
            old.style.transform = ''
            if (this.#cache.get(this.#index)?.slot !== old) this.#slotStyle(old, false)
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
        this.#observer.unobserve(this)
        for (const i of [...this.#cache.keys()]) if (i !== this.#index) this.#evict(i)
        this.#view.destroy()
        this.#view = null
        this.sections[this.#index]?.unload?.()
        this.#mediaQuery.removeEventListener('change', this.#mediaQueryListener)
    }
}

customElements.define('foliate-paginator', Paginator)
