import { describe, expect, it } from 'vitest'
import { externalHref, isNoteTarget, noteElement, noteRef, sanitizeNote } from '../src/reader/links'

const OPS = 'http://www.idpf.org/2007/ops'
/** An XHTML section document like the book's (epub:type needs the OPS namespace). */
const xhtml = (body: string) => new DOMParser().parseFromString(
  `<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="${OPS}"><head><title>t</title></head><body>${body}</body></html>`,
  'application/xhtml+xml')

describe('externalHref (F-044)', () => {
  it.each([
    ['https://www.gutenberg.org/', 'https://www.gutenberg.org/'],
    ['http://example.com/a?b=1#c', 'http://example.com/a?b=1#c'],
    ['HTTPS://Example.COM', 'https://example.com/'],
    ['mailto:someone@example.com', 'mailto:someone@example.com'],
  ])('%s is an external link', (href, url) => expect(externalHref(href)).toBe(url))
  it.each([
    'javascript:alert(1)', 'JavaScript:void(0)', 'file:///sdcard/x', 'content://x/y', 'intent://x#Intent;end',
    'data:text/html,hi', 'blob:https://x/1', 'ftp://example.com/', 'https://', 'not a url', '',
  ])('%s is ignored', href => expect(externalHref(href)).toBeNull())
})

describe('noteRef (F-043)', () => {
  const a = (html: string) => xhtml(html).querySelector('a')!
  it('epub:type noteref and doc-noteref roles are note references', () => {
    expect(noteRef(a(`<p>x<a epub:type="noteref" href="n.xhtml#n1">1</a></p>`))).toBe('yes')
    expect(noteRef(a(`<p>x<a role="doc-noteref" href="#n1">1</a></p>`))).toBe('yes')
    expect(noteRef(a(`<p>x<a epub:type="biblioref glossref" href="#n1">1</a></p>`))).toBe('yes')
  })
  it('a superscript link is probably one; a backlink or a plain link is not', () => {
    expect(noteRef(a(`<p>x<sup><a href="#n1">1</a></sup></p>`))).toBe('maybe')
    expect(noteRef(a(`<p>x<a href="#n1"><sup>1</sup></a></p>`))).toBe('maybe')
    expect(noteRef(a(`<p>x<sup><a epub:type="backlink" href="#n1">1</a></sup></p>`))).toBe('no')
    expect(noteRef(a(`<p>See <a href="ch2.xhtml#target">the target</a></p>`))).toBe('no')
  })
})

describe('isNoteTarget and noteElement', () => {
  it('footnote, endnote and note types and roles are note targets', () => {
    const d = xhtml(`<aside epub:type="footnote" id="a"><p>A</p></aside><li role="doc-endnote" id="b">B</li><div epub:type="rearnote" id="c">C</div><p id="d">D</p>`)
    expect(['a', 'b', 'c', 'd'].map(id => isNoteTarget(d.getElementById(id)))).toEqual([true, true, true, false])
    expect(isNoteTarget(null)).toBe(false)
  })
  it('a target inside inline markup is the block around it', () => {
    const d = xhtml(`<p id="p">Note <span><a id="t">1</a></span> text</p>`)
    expect(noteElement(d.getElementById('t'))).toBe(d.getElementById('p'))
    expect(noteElement(d.getElementById('p'))).toBe(d.getElementById('p'))
  })
  it('a range target is the element where it starts; nothing is null', () => {
    const d = xhtml(`<aside id="n"><p>Text</p></aside>`)
    const r = d.createRange(); r.selectNodeContents(d.querySelector('p')!.firstChild!)
    expect(noteElement(r)).toBe(d.querySelector('p'))
    expect(noteElement(null)).toBeNull()
  })
})

describe('sanitizeNote (R-007)', () => {
  const resolve = (h: string) => (/^\w+:/.test(h) ? h : `OEBPS/${h}`)
  it('keeps text and simple markup, drops scripts, handlers, styles, media and unknown attributes', () => {
    const d = xhtml(`<aside id="n" epub:type="footnote"><p style="color:red" class="x" onclick="evil()"><b>Bold</b> text<script>evil()</script><img src="i.png" alt="[pic]"/><iframe src="x"></iframe><style>p{}</style><object data="x"></object><svg xmlns="http://www.w3.org/2000/svg"><text>svg</text></svg></p></aside>`)
    const frag = sanitizeNote(d.getElementById('n')!, document, resolve)
    const box = document.createElement('div'); box.append(frag)
    expect(box.innerHTML).toBe('<p><b>Bold</b> text[pic]</p>')
  })
  it('links keep their resolved target in data attributes, never an href', () => {
    const d = xhtml(`<aside id="n"><p><a href="ch1.xhtml#r5" onclick="x()">5.</a> see <a href="https://example.com/">site</a> <a href="javascript:evil()">bad</a></p></aside>`)
    const box = document.createElement('div'); box.append(sanitizeNote(d.getElementById('n')!, document, resolve))
    const links = [...box.querySelectorAll('a')]
    expect(links.map(l => l.getAttribute('href'))).toEqual([null, null, null])
    expect(links.map(l => l.getAttribute('data-er-href'))).toEqual(['OEBPS/ch1.xhtml#r5', 'https://example.com/', 'javascript:evil()'])
    expect(links.map(l => l.getAttributeNames().sort().join(','))).toEqual(Array(3).fill('data-er-href,role,tabindex'))
  })
  it('the note element itself is not copied when it is an aside or a list item, only its content', () => {
    const d = xhtml(`<ol><li id="n">Item <i>note</i></li></ol>`)
    const box = document.createElement('div'); box.append(sanitizeNote(d.getElementById('n')!, document, resolve))
    expect(box.innerHTML).toBe('Item <i>note</i>')
  })
})
